package io.github.marsinsanity.mainui.emu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.core.Settings
import io.github.marsinsanity.mainui.core.SafGrants
import io.github.marsinsanity.mainui.core.Storage
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.Library
import io.github.marsinsanity.mainui.data.SystemDef
import io.github.marsinsanity.mainui.data.strings
import org.json.JSONObject
import java.io.File

/** How the in-game menu can reach an emulator's save states. */
enum class StateSupport {
    /** RetroArch network commands: slots, works while paused. */
    API,
    /** MainUI injects the emulator's save/load hotkeys (needs root, keys bound in the emulator). */
    HOTKEY,
    /** Only through the emulator's own menu. */
    MENU,
    /** The emulator has no save states. */
    NONE,
    /**
     * The MainUI State API (StateApi: the emulators' MainUI-Android builds): MainUI asks the
     * emulator itself, with slots and no root. Like HOTKEY, the game must be running in front.
     * Found from the installed app, never set in emulators.json.
     */
    DIRECT;

    companion object {
        fun parse(s: String?) = when (s?.lowercase()) {
            "api" -> API; "hotkey" -> HOTKEY; "none" -> NONE; else -> MENU
        }
    }
}

/** One launch recipe (RetroArch itself or a standalone emulator). */
data class EmuDef(
    val id: String,
    val name: String,
    /** package -> activity (null = launch intent / package only), in preference order. */
    val components: List<Pair<String, String?>>,
    val action: String?,
    val category: String?,
    val data: String?,
    val mime: String?,
    val extras: Map<String, String>,
    val flags: List<String>,
    val menu: String,
    val states: StateSupport,
    val save: String?,
    val load: String?,
    /** Where to get it ("get" in emulators.json): github, url, page, play, note, buildbot. */
    val get: Map<String, String> = emptyMap(),
    /** "root": true in emulators.json: the game activity isn't exported, so it is started with root. */
    val rootLaunch: Boolean = false,
    /**
     * "loadDelay" in "ingame" (ms): how long after its window appears a game is running, so the
     * load key works (auto load on start). 0 = MainUI's default.
     */
    val loadDelayMs: Long = 0,
    /** "slots" in "ingame": how many save slots its MainUI build has (the State API's slots 0 .. slots-1). */
    val slots: Int = 10,
    /**
     * "freeze" in "ingame": names of the helper programs that run the game (PICO-8 runs it in
     * proot / pico8_64 processes started by the app). Pausing then freezes just those, the way the
     * app pauses itself, instead of the app's own processes. null = freeze the app.
     */
    val freezeOnly: List<String>? = null,
) {
    val packages: List<String> get() = components.map { it.first }.distinct()
}

/** A selectable emulator for a system: "ra:<core>" or "sa:<id>". */
data class EmuOption(
    val ref: String,
    val name: String,
    /** null when we cannot tell (RetroArch cores without root). */
    val installed: Boolean?,
    val pkg: String?,
    val recommended: Boolean,
) {
    val isRetroArch get() = ref.startsWith("ra:")
    val core get() = ref.removePrefix("ra:")

    /** "Nintendo - Game Boy Advance (mGBA) (RetroArch)" -> "mGBA"; standalone names stay. */
    val shortName: String get() {
        if (!name.contains(" - ")) return name
        return Regex("""\(([^()]+)\)""").find(name.substringAfter(" - "))?.groupValues?.get(1) ?: name
    }
}

/**
 * Detects installed emulators and RetroArch cores and turns a [Game] into the
 * right launch Intent. Recipes come from assets/emulators.json and can be
 * overridden by <storage>/MainUI/emulators.json.
 */
class EmulatorManager(
    private val ctx: Context,
    private val storage: Storage,
    private val settings: Settings,
    private val library: Library,
) {
    lateinit var retroarch: EmuDef
        private set
    /**
     * Recipes by id. This map and the installed ones below are replaced whole (never changed in
     * place), so launches and menus reading them on other threads never see one half-rebuilt.
     */
    @Volatile private var standalone: Map<String, EmuDef> = emptyMap()
    var raCommandPort = 55355
        private set

    @Volatile var raPackage: String? = null
        private set
    /** Installed core basenames, null if unknown (no root). */
    @Volatile var raCores: Set<String>? = null
        private set
    /** core basename -> path RetroArch loads it from. */
    @Volatile private var corePaths: Map<String, String> = emptyMap()
    /** False when some core folders couldn't be read (no root): missing cores are then "unknown". */
    @Volatile private var coresComplete = false
    @Volatile var raCoreDir: String? = null
        private set
    /** Where this RetroArch reads core .info files (libretro_info_path), from the last core scan. */
    @Volatile var raInfoDir: String? = null
        private set
    @Volatile private var lastCoreScan = 0L
    /** core basename -> display name / supported extensions read from RetroArch .info files (root). */
    private val coreInfo = HashMap<String, Pair<String, Set<String>>>()
    @Volatile private var installedPkgs: Map<String, String?> = emptyMap() // emu id -> installed package
    @Volatile private var installedComponents: Map<String, Pair<String, String?>?> = emptyMap()
    /** loadDefs / refresh run one at a time (several threads start them). */
    private val defsLock = Any()

    init { loadDefs() }

    fun loadDefs() = synchronized(defsLock) {
        val defs = LinkedHashMap<String, EmuDef>()
        val base = JSONObject(ctx.assets.open("emulators.json").bufferedReader().use { it.readText() })
        apply(base, defs, trusted = true)
        val override = File(storage.dataRoot(), "emulators.json")
        if (override.isFile) runCatching { apply(JSONObject(override.readText()), defs, trusted = false) }
        standalone = defs
    }

    /**
     * [trusted]: MainUI's own recipes. The user's emulators.json is in shared storage, which
     * other apps can write: it can't start new things with root, only keep a recipe's root start.
     */
    private fun apply(root: JSONObject, standalone: MutableMap<String, EmuDef>, trusted: Boolean) {
        root.optJSONObject("retroarch")?.let { r ->
            retroarch = parseDef("retroarch", r)
            raCommandPort = r.optInt("commandPort", raCommandPort)
        }
        root.optJSONArray("standalone")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.getString("id")
                val d = parseDef(id, o)
                val base = standalone[id]
                standalone[id] = if (trusted || !d.rootLaunch ||
                    (base != null && base.rootLaunch && d.components.all { it in base.components })) d else d.copy(rootLaunch = false)
            }
        }
    }

    private fun parseDef(id: String, o: JSONObject): EmuDef {
        val extras = LinkedHashMap<String, String>()
        o.optJSONObject("extras")?.let { e -> e.keys().forEach { k -> extras[k] = e.getString(k) } }
        val ingame = o.optJSONObject("ingame")
        val components = ArrayList<Pair<String, String?>>()
        o.optJSONArray("components")?.strings()?.forEach { c ->
            val pkg = c.substringBefore('/')
            val act = c.substringAfter('/', "").takeIf { it.isNotEmpty() }
            components += pkg to act?.let { if (it.startsWith(".")) pkg + it else it }
        }
        // v1 format: "packages" + one "activity"
        val activity = o.optString("activity").takeIf { it.isNotEmpty() }
        o.optJSONArray("packages")?.strings()?.forEach { p -> components += p to activity }
        val save = ingame?.optString("save")?.takeIf { it.isNotEmpty() && it != "none" }
        val load = ingame?.optString("load")?.takeIf { it.isNotEmpty() && it != "none" }
        val states = ingame?.optString("states")?.takeIf { it.isNotEmpty() }?.let { StateSupport.parse(it) }
            ?: if (save != null && load != null) StateSupport.HOTKEY else StateSupport.MENU
        return EmuDef(
            id = id,
            name = o.optString("name", id),
            components = components,
            action = o.optString("action").takeIf { it.isNotEmpty() },
            category = o.optString("category").takeIf { it.isNotEmpty() },
            data = o.optString("data").takeIf { it.isNotEmpty() },
            mime = o.optString("type").takeIf { it.isNotEmpty() },
            extras = extras,
            flags = o.optJSONArray("flags")?.strings() ?: listOf("NEW_TASK", "CLEAR_TASK"),
            menu = ingame?.optString("menu", "back") ?: "back",
            states = if (id == "retroarch") StateSupport.API else states,
            save = save,
            load = load,
            get = o.optJSONObject("get")?.let { g -> g.keys().asSequence().associateWith { k -> g.optString(k) } } ?: emptyMap(),
            rootLaunch = o.optBoolean("root", false),
            loadDelayMs = ingame?.optLong("loadDelay", 0L) ?: 0L,
            freezeOnly = ingame?.optJSONArray("freeze")?.strings()?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() },
            slots = (ingame?.optInt("slots", 10) ?: 10).coerceIn(1, 100),
        )
    }

    /** Re-detect installed packages and RetroArch cores. Call off the main thread. */
    fun refresh() = synchronized(defsLock) {
        val pm = ctx.packageManager
        fun installed(p: String) = runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false)
        val pkgs = HashMap<String, String?>()
        val comps = HashMap<String, Pair<String, String?>?>()
        fun hasActivity(pkg: String, act: String) = runCatching { pm.getActivityInfo(ComponentName(pkg, act), 0); true }.getOrDefault(false)
        fun launcherOf(pkg: String) = (pm.getLaunchIntentForPackage(pkg) ?: pm.getLeanbackLaunchIntentForPackage(pkg))?.component?.className
        // Launchable apps, for emulators installed under an id the recipes don't list.
        val launchable = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                .map { it.packageName to pm.getApplicationLabel(it).toString() }
        }.getOrDefault(emptyList())
        // Packages a recipe names are that recipe's: never "another build" of a different one
        // (Citra MMJ's org.citra.emu, labelled "Citra", is not the Citra recipe's).
        val listed = HashSet<String>().apply { standalone.values.forEach { d -> d.components.forEach { add(it.first) } }; addAll(retroarch.packages) }
        // First recipe component whose package is installed and whose activity exists.
        val exact = standalone.mapValues { (_, d) -> d.components.firstOrNull { (pkg, act) -> installed(pkg) && (act == null || hasActivity(pkg, act)) } }
        for ((id, d) in standalone) {
            val c = exact[id]
                // (Not a package another recipe's activity is in: Lime3DS has the id Azahar's Play build keeps.)
                ?: d.components.firstOrNull { (p, _) -> installed(p) && exact.none { (o, oc) -> o != id && oc?.first == p } }?.let { (pkg, _) ->
                    // Package installed but the recipe's activity was renamed: use its launcher activity.
                    pkg to launcherOf(pkg)
                }
                // (A package another recipe names counts only when this recipe's own activity is in
                // it: Azahar's Play build keeps Lime3DS's id, Citra MMJ's org.citra.emu has no Citra activity.)
                ?: launchable.firstOrNull { (pkg, label) -> variantOf(d, pkg, label) &&
                    (pkg !in listed || d.components.any { (_, a) -> a != null && hasActivity(pkg, a) }) }?.let { (pkg, _) ->
                    // Another build of it (".debug", ".nightly", a fork): the recipe's activity classes
                    // keep their names under another application id; else its launcher activity.
                    pkg to (d.components.mapNotNull { it.second }.distinct().firstOrNull { hasActivity(pkg, it) } ?: launcherOf(pkg))
                }
            pkgs[id] = c?.first
            comps[id] = c
        }
        installedPkgs = pkgs
        installedComponents = comps
        variantPkgs = pkgs.values.filterNotNull().toSet()
        val ra = settings.raPackageOverride.takeIf { it.isNotBlank() && installed(it) }
            ?: retroarch.packages.firstOrNull { installed(it) }
        raPackage = ra
        refreshCores()
    }

    /**
     * Re-scans RetroArch's cores (cores added in RetroArch's Online Updater
     * show up without restarting MainUI). Call off the main thread.
     */
    fun refreshCores() {
        val ra = raPackage
        lastCoreScan = android.os.SystemClock.uptimeMillis()
        if (ra == null) {
            raCores = null; corePaths = emptyMap(); coresComplete = false; raCoreDir = null; raInfoDir = null
            return
        }
        val cfg = if (Root.knownAvailable() || Root.available()) Root.readFile(raConfigPath(ra))
        else runCatching { File(raConfigPath(ra)).readText() }.getOrNull()
        val scan = RetroArchCores.scan(ctx, ra, cfg)
        corePaths = scan.cores
        coresComplete = scan.complete
        raCoreDir = scan.coreDir
        raInfoDir = scan.infoDir
        raCores = if (scan.complete || scan.cores.isNotEmpty()) scan.cores.keys else null
        // .info files give us names + extensions so cores we don't know still show up.
        val info = HashMap<String, Pair<String, Set<String>>>()
        if (Root.knownAvailable()) {
            val lines = Root.exec("cd ${Root.q(scan.infoDir)} 2>/dev/null && grep -H -E '^(display_name|supported_extensions) *=' *.info").lines
            val tmp = HashMap<String, Pair<String?, Set<String>>>()
            for (line in lines) {
                val file = line.substringBefore(':').removeSuffix("_libretro.info")
                val kv = line.substringAfter(':')
                val key = kv.substringBefore('=').trim()
                val value = kv.substringAfter('=').trim().trim('"')
                val cur = tmp[file] ?: (null to emptySet())
                tmp[file] = if (key == "display_name") value to cur.second
                else cur.first to value.split('|').map { it.lowercase() }.toSet()
            }
            for ((k, v) in tmp) info[k] = (v.first ?: k) to v.second
        }
        synchronized(coreInfo) { coreInfo.clear(); coreInfo.putAll(info) }
    }

    /** Re-scan cores when the last scan is older than [maxAgeMs]. */
    fun refreshCoresIfStale(maxAgeMs: Long = 10_000) {
        if (android.os.SystemClock.uptimeMillis() - lastCoreScan > maxAgeMs) refreshCores()
    }

    /** Is [core] installed? null = can't tell (no root). */
    fun coreInstalled(core: String): Boolean? = when {
        raPackage == null -> false
        core in corePaths -> true
        coresComplete -> false
        else -> null
    }

    /**
     * Installs [core] into RetroArch (root): from MainUI/cores when a file for it
     * is there, otherwise downloaded from the libretro buildbot.
     */
    fun installCore(core: String, rescan: Boolean = true, progress: (String) -> Unit = {}): Result<String> = runCatching {
        val ra = raPackage ?: throw IllegalStateException("RetroArch is not installed")
        val dir = raCoreDir ?: "/data/data/$ra/cores"
        val local = localCores()[core]
        val infoDir = raInfoDir ?: "/data/data/$ra/info"
        if (local != null) RetroArchCores.installFile(ctx, ra, dir, local, core, progress, infoDir)
        else RetroArchCores.install(ctx, ra, dir, core, progress, infoDir)
        if (rescan) refreshCores()
        "${coreDisplayName(core)} installed"
    }

    /** Cores the buildbot has for this RetroArch that aren't installed yet. */
    fun missingRemoteCores(): List<RetroArchCores.Remote> {
        val ra = raPackage ?: throw IllegalStateException("RetroArch is not installed")
        refreshCores()
        val have = raCores ?: emptySet()
        return RetroArchCores.listRemote(RetroArchCores.abiOf(ctx, ra)).filter { it.core !in have }
    }

    /** Folder for cores that aren't on the buildbot (custom builds): <storage>/MainUI/cores. */
    val localCoreDir: File get() = storage.dataDir("cores")

    /** core -> file for every `*_libretro_android.so` (or .zip of one) in [localCoreDir]. */
    fun localCores(): Map<String, File> =
        (localCoreDir.listFiles() ?: emptyArray()).filter { it.isFile }.mapNotNull { f ->
            val n = f.name.removeSuffix(".zip").removeSuffix(".ZIP")
            if (n.endsWith(RetroArchCores.SUFFIX)) RetroArchCores.coreName(n) to f else null
        }.toMap()

    /** Every RetroArch core recommended for any system (for the core installer). */
    fun recommendedCores(): List<String> = systemsCoreRefs().distinct()

    private fun systemsCoreRefs(): List<String> =
        io.github.marsinsanity.mainui.MainUI.systems.defs.flatMap { d -> d.emulators.filter { it.startsWith("ra:") }.map { it.removePrefix("ra:") } }

    /** Packages of detected emulators, including builds found by [variantOf]. */
    @Volatile private var variantPkgs: Set<String> = emptySet()

    /**
     * Is [pkg] an emulator: a recipe's package, a detected one, or another build of a known
     * emulator (see [variantOf]; [label] is looked up when not given). Emulators are never
     * sorted into the Android games.
     */
    fun isEmulatorPackage(pkg: String, label: String? = null): Boolean {
        if (pkg in retroarch.packages || pkg in variantPkgs || standalone.values.any { pkg in it.packages }) return true
        val l = label ?: runCatching { ctx.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
        return standalone.values.any { variantOf(it, pkg, l) } || variantOf(retroarch, pkg, l)
    }

    /**
     * [pkg] (an app called [label]) is another build of emulator [d] than its recipe lists:
     * its id extends one of the recipe's ("com.rfandango.haku_x.debug", "org.vita3k.emulator.debug",
     * "com.armsx2.nightly"), or the app is named like it ("hakuX Debug", "ARMSX2 Nightly", "Vita3K").
     */
    fun variantOf(d: EmuDef, pkg: String, label: String?): Boolean = isVariant(d.packages, d.name, d.id, pkg, label)



    fun standaloneDefs(): Collection<EmuDef> = standalone.values
    fun installedPackage(id: String): String? = installedPkgs[id]

    /** The emulator ref for the installed package [pkg]: "ra:" for RetroArch, "sa:<id>" for a recipe's; null if none. */
    fun refForPackage(pkg: String): String? =
        if (pkg == raPackage) "ra:" else installedPkgs.entries.firstOrNull { it.value == pkg }?.let { "sa:${it.key}" }
    fun coreDisplayName(core: String): String = synchronized(coreInfo) { coreInfo[core]?.first } ?: prettyCore(core)

    fun nameOf(ref: String): String = when {
        ref.startsWith("ra:") -> coreDisplayName(ref.removePrefix("ra:"))
        ref.startsWith("sa:") -> standalone[ref.removePrefix("sa:")]?.name ?: ref.removePrefix("sa:")
        else -> ref
    }

    /** The emulator [ref] ("sa:<id>" / "ra:<core>") as a launch option, whatever system it is for. */
    fun optionFor(ref: String): EmuOption? = option(ref, false)

    /** True when [ref] is known to be installed. */
    fun isInstalled(ref: String): Boolean = option(ref, false)?.installed == true

    private fun option(ref: String, recommended: Boolean): EmuOption? = when {
        ref.startsWith("ra:") -> {
            val core = ref.removePrefix("ra:")
            EmuOption(ref, "${coreDisplayName(core)} (RetroArch)", coreInstalled(core), raPackage, recommended)
        }
        ref.startsWith("sa:") -> {
            val d = standalone[ref.removePrefix("sa:")] ?: return null
            val pkg = installedPkgs[d.id]
            EmuOption(ref, d.name, pkg != null, pkg, recommended)
        }
        else -> null
    }

    /**
     * Emulators for a system, OnionOS style: recommended ones first (default
     * first), then every other installed RetroArch core that claims one of the
     * system's file extensions.
     */
    fun optionsFor(sys: SystemDef): List<EmuOption> {
        val out = sys.emulators.mapNotNull { option(it, true) }.toMutableList()
        val cores = raCores
        if (cores != null) {
            val known = out.map { it.ref }.toSet()
            for (c in cores.sorted()) {
                val ref = "ra:$c"
                if (ref in known) continue
                // Containers (.zip, .chd, .iso...) say nothing about the system: a Saturn core
                // listing .zip must not show up for NES. Match on the specific extensions only.
                val all = synchronized(coreInfo) { coreInfo[c]?.second } ?: emptySet()
                val exts = all - GENERIC_EXTENSIONS
                // A core with only containers (MAME 2003-Plus: zip) is offered for systems that
                // only list containers (Arcade), to be picked by hand; it is never chosen by itself.
                // Unknown folders (no extension list) offer every core, so one can be picked by hand.
                if (sys.extensions.isEmpty() || exts.any { it in sys.extensions } ||
                    (exts.isEmpty() && (sys.extensions - GENERIC_EXTENSIONS).isEmpty() && all.any { it in sys.extensions })) {
                    option(ref, false)?.let { out += it }
                }
            }
        }
        return out
    }

    /**
     * Per game choice > per system choice > first installed recommended emulator.
     * Without [gameOverride] / [systemOverride] that choice is skipped: what "Default" means
     * in the game's / the system's core picker.
     */
    fun choose(sys: SystemDef, game: Game?, gameOverride: Boolean = true, systemOverride: Boolean = true): EmuOption? {
        val opts = optionsFor(sys)
        // A choice whose emulator no longer exists (a recipe removed since) falls through to the
        // next one: the game's to the system's, the system's to the automatic choice.
        fun resolve(ref: String?) = ref?.let { r -> opts.firstOrNull { it.ref == r } ?: option(r, false) }
        if (gameOverride) game?.let { resolve(library.coreForGame(it.key)) }?.let { return it }
        if (systemOverride) resolve(library.coreForSystem(sys.id))?.let { return it }
        // Automatic choice: recommended emulators, or other cores whose .info lists this
        // game's extension (a Game Boy core claims .gb, which the GBA folder also accepts,
        // but it can't run a .gba file).
        val ext = game?.file?.let(::launchFile)?.let { if (it.isDirectory) null else it.extension.lowercase() }
        // A container (.zip) only counts for a core made for this system (one of its own
        // extensions), not one that merely opens zips.
        // (Without a file extension, for the system's Default or a folder game: only cores made
        // for the system, never one that merely opens containers.)
        val fits = opts.filter { o ->
            o.recommended || !o.isRetroArch ||
                (synchronized(coreInfo) { coreInfo[o.core]?.second }?.let { info ->
                    val own = sys.extensions.isEmpty() || (info - GENERIC_EXTENSIONS).any { it in sys.extensions }
                    if (ext == null) own else ext in info && (ext !in GENERIC_EXTENSIONS || own)
                } ?: (ext == null && sys.extensions.isEmpty()))
        }
        // Nothing that fits: none (MainUI says so), never whichever core sorts first.
        return fits.firstOrNull { it.installed == true }
            ?: fits.firstOrNull { it.installed == null }
            ?: fits.firstOrNull()
    }

    fun raConfigPath(pkg: String) = "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg"
    fun raCorePath(pkg: String, core: String) =
        corePaths[core] ?: "${raCoreDir ?: "/data/data/$pkg/cores"}/$core${RetroArchCores.SUFFIX}"

    /**
     * What a game's emulator gets: for a folder named like a rom ("Game.m3u/"), the file of the
     * same name inside it, as ES-DE does; the folder itself when there is none (PS3 games).
     */
    private fun launchFile(f: File): File = if (f.isDirectory) File(f, f.name).takeIf { it.isFile } ?: f else f

    /** Builds the launch intent. Throws [IllegalStateException] with a user readable reason. */
    fun buildIntent(game: Game, opt: EmuOption): Intent {
        val rom = game.file?.let(::launchFile) ?: throw IllegalStateException("No rom file")
        if (opt.isRetroArch) {
            val pkg = raPackage ?: throw IllegalStateException("RetroArch is not installed")
            return fill(retroarch, pkg, rom, opt.core, retroarch.components.first().second)
        }
        val def = standalone[opt.ref.removePrefix("sa:")] ?: throw IllegalStateException("Unknown emulator ${opt.ref}")
        val comp = installedComponents[def.id] ?: throw IllegalStateException("${def.name} is not installed")
        return fill(def, comp.first, rom, null, comp.second)
    }

    fun defFor(ref: String): EmuDef? = if (ref.startsWith("ra:")) retroarch else standalone[ref.removePrefix("sa:")]

    private fun fill(def: EmuDef, pkg: String, rom: File, core: String?,
                     activity: String? = def.components.firstOrNull { it.first == pkg }?.second): Intent {
        val fileContent by lazy {
            runCatching { if (rom.isFile && rom.length() < 4096) rom.readText().removePrefix("\uFEFF").lineSequence().firstOrNull()?.trim() else null }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: rom.nameWithoutExtension
        }
        val rpcsxGame by lazy { Ps3Games.rpcsxPath(rom) }
        // Through a folder the emulator was given access to, or Android refuses it the file.
        val romSaf by lazy {
            val uid = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
            storage.safUri(rom, if (uid != null) SafGrants.trees(pkg, uid) else emptyList()).toString()
        }
        fun sub(t: String): String = t
            .replace("%FILECONTENT%", if (t.contains("%FILECONTENT%")) fileContent else "")
            .replace("%RPCSX_GAME%", if (t.contains("%RPCSX_GAME%")) rpcsxGame else "")
            .replace("%GAMEDIR%", rom.parentFile?.absolutePath ?: "")
            .replace("%ROM%", rom.absolutePath)
            .replace("%ROMURI%", Uri.fromFile(rom).toString())
            .replace("%ROMPROVIDER%", providerUri(rom).toString())
            .replace("%ROMSAF%", if (t.contains("%ROMSAF%")) romSaf else "")
            .replace("%BASENAME%", rom.nameWithoutExtension)
            .replace("%PKG%", pkg)
            .replace("%CORE%", core ?: "")
            .replace("%CORE_PATH%", if (core != null) raCorePath(pkg, core) else "")
            .replace("%RA_CONFIG%", raConfigPath(pkg))

        val intent = Intent(def.action ?: Intent.ACTION_MAIN)
        if (activity != null) intent.component = ComponentName(pkg, activity) else intent.setPackage(pkg)
        def.category?.let { intent.addCategory(it) }
        def.data?.let { d ->
            val uri = Uri.parse(sub(d))
            if (def.mime != null) intent.setDataAndType(uri, def.mime) else intent.data = uri
            // Only MainUI's own files can be shared this way. Granting a storage (SAF) link MainUI
            // doesn't hold makes Android refuse to start the emulator; it has its own folder grant.
            // (Only the rom's own link: an edited recipe must not get MainUI's other files.)
            if (uri.scheme == "content" && uri == providerUri(rom))
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        for ((k, v) in def.extras) {
            when {
                v.startsWith("bool:") -> intent.putExtra(k, v.removePrefix("bool:").toBoolean())
                v.startsWith("int:") -> intent.putExtra(k, v.removePrefix("int:").toIntOrNull() ?: 0)
                v.startsWith("array:") -> intent.putExtra(k, v.removePrefix("array:").split(',').map { sub(it) }.toTypedArray())
                else -> intent.putExtra(k, sub(v))
            }
        }
        if (def.id == "retroarch") {
            // RetroArch wants the current IME so its on-screen keyboard keeps working.
            AndroidSettings.Secure.getString(ctx.contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
                ?.let { intent.putExtra("IME", it) }
        }
        for (f in def.flags) intent.addFlags(FLAGS[f] ?: 0)
        if (def.flags.isEmpty()) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent
    }

    fun providerUri(f: File): Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)

    /** Brings an already running emulator task to the front without restarting it. */
    fun resumeIntent(pkg: String, activity: String?): Intent? {
        if (activity != null) {
            return Intent(Intent.ACTION_MAIN).setComponent(ComponentName(pkg, activity))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        return ctx.packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    private fun raConfigStamp() = "$RA_CONFIG_VERSION:${settings.autoSaveOnExit}"

    /**
     * Applies [configureRetroArch] by itself when this MainUI wants settings it hasn't written
     * yet (new version, auto save switched), so nobody has to press "Configure RetroArch" again.
     * Only while RetroArch isn't running: configuring stops it. Call off the main thread.
     */
    fun ensureRetroArchConfigured() {
        val pkg = raPackage ?: return
        if (!Root.knownAvailable() && !Root.available()) return
        // Per RetroArch package, and only while its config still has MainUI's settings: a
        // reinstalled or reset RetroArch (or another build) gets them again.
        if (settings.prefs.getString("$RA_CONFIG_STAMP:$pkg", null) == raConfigStamp() &&
            runCatching { Root.Shell.run("grep -q '^input_android_physical_keyboard *= *\"MainUI Keyboard\"' ${Root.q(raConfigPath(pkg))} && echo yes", 5000) }
                .getOrNull()?.out?.contains("yes") == true) return
        if (Root.exec("pidof $pkg").out.isNotBlank()) return
        val (ok, msg) = configureRetroArch()
        io.github.marsinsanity.mainui.core.SessionLog.log("RetroArch configured by itself: $ok $msg")
    }

    /**
     * One-time RetroArch setup for the in-game menu: enables the network command
     * interface (needed for pause/save/load/quit) and auto save/load states.
     * Needs root because RetroArch's config lives in its private app storage.
     */
    fun configureRetroArch(): Pair<Boolean, String> {
        val pkg = raPackage ?: return false to "RetroArch is not installed"
        if (!Root.available()) return false to "Root access is required"
        val path = raConfigPath(pkg)
        // Stop it first: RetroArch rewrites its config when it exits normally.
        Root.forceStop(pkg)
        val cfg = Root.readFile(path)
            ?: return false to "Could not read retroarch.cfg completely, so nothing was changed. Start RetroArch once, then try again.\n\n$path"
        val wanted = linkedMapOf(
            "network_cmd_enable" to "\"true\"",
            "network_cmd_port" to "\"$raCommandPort\"",
            "savestate_auto_index" to "\"false\"",
            "savestate_thumbnail_enable" to "\"true\"",
            // RetroArch pauses itself while MainUI's menu or Game Switcher has focus
            // (official Android builds have no pause command).
            "pause_nonactive" to "\"true\"",
            // Flush in-game saves (SRAM) every 10 s so a force stopped game keeps them
            "autosave_interval" to "\"10\"",
            // Official builds have no network commands: MainUI presses these keyboard hotkeys
            // (RetroArch's defaults) for Save / Load / Native Menu. A keyboard hotkey-enable key
            // or merged hotkey devices would block them; a controller's hotkey button doesn't.
            "input_menu_toggle" to "\"f1\"",
            "input_save_state" to "\"f2\"",
            "input_load_state" to "\"f4\"",
            // Slot keys (RetroArch's defaults): the in-game menu's slot picker steps with them.
            "input_state_slot_increase" to "\"f7\"",
            "input_state_slot_decrease" to "\"f6\"",
            "input_enable_hotkey" to "\"nul\"",
            "input_hotkey_device_merge" to "\"false\"",
            // MainUI's virtual keyboard (F keys only, no letters) is a keyboard, not a gamepad:
            // otherwise RetroArch can give it a controller port, port 1 when it shows up before
            // the real controller, and then reads its F keys as buttons rather than hotkeys.
            "input_android_physical_keyboard" to "\"MainUI Keyboard\"",
        )
        // MainUI 0.1.59-0.1.61 bound Save / Load State to controller "buttons" F2 / F4 (key codes
        // 132 / 134) and pressed them as the controller, which Android 12+ doesn't allow (see
        // RetroArchController.hotkey). Those binds are taken out again; the user's own stay.
        for ((k, code) in listOf("input_save_state_btn" to android.view.KeyEvent.KEYCODE_F2, "input_load_state_btn" to android.view.KeyEvent.KEYCODE_F4)) {
            if (RetroArchCores.cfgValue(cfg, k) == code.toString()) wanted[k] = "\"nul\""
        }
        if (settings.autoSaveOnExit) {
            wanted["savestate_auto_save"] = "\"true\""
            wanted["savestate_auto_load"] = "\"true\""
            // Only a starting point: RetroArch sets the slot to 0 (or the game's last slot) whenever
            // it loads a game. MainUI copies each Save into the auto slot instead, which RetroArch
            // loads by itself the next time (see RetroArchController.saveByKey).
            wanted["state_slot"] = "\"-1\""
        } else {
            wanted["savestate_auto_save"] = "\"false\""
            wanted["savestate_auto_load"] = "\"false\""
            wanted["state_slot"] = "\"0\""
        }
        val merged = RetroArchCores.mergeConfig(cfg, wanted)
            ?: return false to "retroarch.cfg looks incomplete, so nothing was changed.\n\n$path"
        val w = Root.writeFile(path, merged, backup = true)
        if (w.ok) settings.prefs.edit().putString("$RA_CONFIG_STAMP:$pkg", raConfigStamp()).apply()
        return if (w.ok) true to "RetroArch configured for MainUI"
        else false to "Could not save retroarch.cfg:\n${w.error}\n\n$path"
    }

    companion object {
        private val BUILD_WORDS = setOf("debug", "nightly", "dev", "beta", "alpha", "canary", "preview", "test", "release", "stable", "unofficial")

        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        /**
         * [pkg] (an app called [label]) is another build of the emulator with recipe [packages],
         * [name] and [id]: its id extends a recipe's ("com.rfandango.haku_x.debug"), or it is named
         * exactly like it or like it plus build words ("hakuX Debug", "ARMSX2 Nightly 2"), never
         * just starting with the name ("Dolphin Browser" is not Dolphin).
         */
        fun isVariant(packages: List<String>, name: String, id: String, pkg: String, label: String?): Boolean {
            if (packages.any { pkg.startsWith("$it.") }) return true
            val l = label?.let(::norm) ?: return false
            val names = listOf(norm(name), norm(id)).filter { it.length >= 4 }.distinct()
            return names.any { n -> l == n || (l.startsWith("$n ") && l.removePrefix("$n ").split(' ').all { it in BUILD_WORDS || it.any(Char::isDigit) }) }
        }

        /** Bump when [configureRetroArch] writes something new. */
        const val RA_CONFIG_VERSION = 6
        private const val RA_CONFIG_STAMP = "ra_config_stamp"

        /** Archive / disc image extensions that many unrelated cores accept. */
        val GENERIC_EXTENSIONS = setOf("zip", "7z", "rar", "gz", "bin", "iso", "img", "chd", "cue", "m3u", "ccd", "mds", "cso", "pbp", "toc", "ecm")

        private val FLAGS = mapOf(
            "NEW_TASK" to Intent.FLAG_ACTIVITY_NEW_TASK,
            "CLEAR_TASK" to Intent.FLAG_ACTIVITY_CLEAR_TASK,
            "CLEAR_TOP" to Intent.FLAG_ACTIVITY_CLEAR_TOP,
            "NO_HISTORY" to Intent.FLAG_ACTIVITY_NO_HISTORY,
            "SINGLE_TOP" to Intent.FLAG_ACTIVITY_SINGLE_TOP,
            "NO_ANIMATION" to Intent.FLAG_ACTIVITY_NO_ANIMATION,
        )

        private val CORE_NAMES = mapOf(
            "mgba" to "mGBA", "gpsp" to "gpSP", "vbam" to "VBA-M", "vba_next" to "VBA Next",
            "gambatte" to "Gambatte", "tgbdual" to "TGB Dual", "snes9x" to "Snes9x", "snes9x2010" to "Snes9x 2010",
            "snes9x2005" to "Snes9x 2005", "snes9x2002" to "Snes9x 2002", "nestopia" to "Nestopia", "fceumm" to "FCEUmm",
            "genesis_plus_gx" to "Genesis Plus GX", "genesis_plus_gx_wide" to "Genesis Plus GX Wide", "picodrive" to "PicoDrive",
            "pcsx_rearmed" to "PCSX ReARMed", "swanstation" to "SwanStation", "mupen64plus_next_gles3" to "Mupen64Plus-Next",
            "parallel_n64" to "ParaLLEl N64", "flycast" to "Flycast", "fbneo" to "FinalBurn Neo", "mame2003_plus" to "MAME 2003-Plus",
            "mame2010" to "MAME 2010", "mame" to "MAME", "ppsspp" to "PPSSPP", "melondsds" to "melonDS DS", "melonds" to "melonDS",
            "desmume" to "DeSmuME", "citra" to "Citra", "dolphin" to "Dolphin", "yabasanshiro" to "YabaSanshiro", "yabadabadoo" to "Yabadabadoo",
            "armgba" to "armGBA", "armnes" to "armNES", "armnes_arcade" to "armNES Arcade", "armn64" to "armN64",
            "mednafen_saturn" to "Beetle Saturn", "dosbox_pure" to "DOSBox Pure", "scummvm" to "ScummVM",
            "mednafen_pce_fast" to "Beetle PCE Fast", "mednafen_ngp" to "Beetle NeoPop", "mednafen_wswan" to "Beetle WonderSwan",
            "mednafen_vb" to "Beetle VB", "handy" to "Handy", "stella" to "Stella", "stella2014" to "Stella 2014",
            "prosystem" to "ProSystem", "atari800" to "Atari800", "fake08" to "fake-08", "tic80" to "TIC-80", "opera" to "Opera",
            "neocd" to "NeoCD", "geolith" to "Geolith", "pokemini" to "PokeMini", "bluemsx" to "blueMSX", "fmsx" to "fMSX",
            "puae" to "PUAE", "vice_x64" to "VICE x64", "fuse" to "Fuse", "cap32" to "Caprice32", "hatari" to "Hatari",
        )

        fun prettyCore(core: String) = CORE_NAMES[core] ?: core.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}
