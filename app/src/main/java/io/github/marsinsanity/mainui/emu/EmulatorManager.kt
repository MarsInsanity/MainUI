package io.github.marsinsanity.mainui.emu

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.core.content.FileProvider
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.core.Settings
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
    NONE;

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
    private val standalone = LinkedHashMap<String, EmuDef>()
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
    @Volatile private var lastCoreScan = 0L
    /** core basename -> display name / supported extensions read from RetroArch .info files (root). */
    private val coreInfo = HashMap<String, Pair<String, Set<String>>>()
    private val installedPkgs = HashMap<String, String?>() // emu id -> installed package
    private val installedComponents = HashMap<String, Pair<String, String?>?>()

    init { loadDefs() }

    fun loadDefs() {
        standalone.clear()
        val base = JSONObject(ctx.assets.open("emulators.json").bufferedReader().use { it.readText() })
        apply(base)
        val override = File(storage.dataRoot(), "emulators.json")
        if (override.isFile) runCatching { apply(JSONObject(override.readText())) }
    }

    private fun apply(root: JSONObject) {
        root.optJSONObject("retroarch")?.let { r ->
            retroarch = parseDef("retroarch", r)
            raCommandPort = r.optInt("commandPort", raCommandPort)
        }
        root.optJSONArray("standalone")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                standalone[o.getString("id")] = parseDef(o.getString("id"), o)
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
        )
    }

    /** Re-detect installed packages and RetroArch cores. Call off the main thread. */
    fun refresh() {
        val pm = ctx.packageManager
        fun installed(p: String) = runCatching { pm.getPackageInfo(p, 0); true }.getOrDefault(false)
        installedPkgs.clear()
        installedComponents.clear()
        for ((id, d) in standalone) {
            // First recipe component whose package is installed and whose activity exists.
            val c = d.components.firstOrNull { (pkg, act) ->
                installed(pkg) && (act == null || runCatching { pm.getActivityInfo(ComponentName(pkg, act), 0); true }.getOrDefault(false))
            } ?: d.components.firstOrNull { installed(it.first) }?.let { (pkg, _) ->
                // Package installed but the recipe's activity was renamed: use its launcher activity.
                pkg to pm.getLaunchIntentForPackage(pkg)?.component?.className
            }
            installedPkgs[id] = c?.first
            installedComponents[id] = c
        }
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
            raCores = null; corePaths = emptyMap(); coresComplete = false; raCoreDir = null
            return
        }
        val cfg = if (Root.knownAvailable() || Root.available()) Root.readFile(raConfigPath(ra))
        else runCatching { File(raConfigPath(ra)).readText() }.getOrNull()
        val scan = RetroArchCores.scan(ctx, ra, cfg)
        corePaths = scan.cores
        coresComplete = scan.complete
        raCoreDir = scan.coreDir
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
    fun installCore(core: String, progress: (String) -> Unit = {}): Result<String> = runCatching {
        val ra = raPackage ?: throw IllegalStateException("RetroArch is not installed")
        val dir = raCoreDir ?: "/data/data/$ra/cores"
        val local = localCores()[core]
        if (local != null) RetroArchCores.installFile(ctx, ra, dir, local, core, progress)
        else RetroArchCores.install(ctx, ra, dir, core, progress)
        refreshCores()
        "${coreDisplayName(core)} installed"
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

    fun isEmulatorPackage(pkg: String): Boolean =
        pkg in retroarch.packages || standalone.values.any { pkg in it.packages }

    fun standaloneDefs(): Collection<EmuDef> = standalone.values
    fun installedPackage(id: String): String? = installedPkgs[id]
    fun coreDisplayName(core: String): String = synchronized(coreInfo) { coreInfo[core]?.first } ?: prettyCore(core)

    fun nameOf(ref: String): String = when {
        ref.startsWith("ra:") -> coreDisplayName(ref.removePrefix("ra:"))
        ref.startsWith("sa:") -> standalone[ref.removePrefix("sa:")]?.name ?: ref.removePrefix("sa:")
        else -> ref
    }

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
                val exts = (synchronized(coreInfo) { coreInfo[c]?.second } ?: emptySet()) - GENERIC_EXTENSIONS
                // Unknown folders (no extension list) offer every core, so one can be picked by hand.
                if (sys.extensions.isEmpty() || exts.any { it in sys.extensions }) {
                    option(ref, false)?.let { out += it }
                }
            }
        }
        return out
    }

    /** Per game choice > per system choice > first installed recommended emulator. */
    fun choose(sys: SystemDef, game: Game?): EmuOption? {
        val opts = optionsFor(sys)
        val chosen = (game?.let { library.coreForGame(it.key) } ?: library.coreForSystem(sys.id))
        chosen?.let { ref -> (opts.firstOrNull { it.ref == ref } ?: option(ref, false))?.let { return it } }
        // Automatic choice: recommended emulators, or other cores whose .info lists this
        // game's extension (a Game Boy core claims .gb, which the GBA folder also accepts,
        // but it can't run a .gba file).
        val ext = game?.file?.let { if (it.isDirectory) null else it.extension.lowercase() }
        val fits = opts.filter { o ->
            o.recommended || ext == null || !o.isRetroArch ||
                (synchronized(coreInfo) { coreInfo[o.core]?.second }?.contains(ext) ?: false)
        }
        return fits.firstOrNull { it.installed == true }
            ?: fits.firstOrNull { it.installed == null }
            ?: fits.firstOrNull()
            ?: opts.firstOrNull()
    }

    fun raConfigPath(pkg: String) = "/storage/emulated/0/Android/data/$pkg/files/retroarch.cfg"
    fun raCorePath(pkg: String, core: String) =
        corePaths[core] ?: "${raCoreDir ?: "/data/data/$pkg/cores"}/$core${RetroArchCores.SUFFIX}"

    /** Builds the launch intent. Throws [IllegalStateException] with a user readable reason. */
    fun buildIntent(game: Game, opt: EmuOption): Intent {
        val rom = game.file ?: throw IllegalStateException("No rom file")
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
            runCatching { if (rom.isFile && rom.length() < 4096) rom.readText().lineSequence().firstOrNull()?.trim() else null }
                .getOrNull()?.takeIf { it.isNotEmpty() } ?: rom.nameWithoutExtension
        }
        fun sub(t: String): String = t
            .replace("%FILECONTENT%", if (t.contains("%FILECONTENT%")) fileContent else "")
            .replace("%GAMEDIR%", rom.parentFile?.absolutePath ?: "")
            .replace("%ROM%", rom.absolutePath)
            .replace("%ROMURI%", Uri.fromFile(rom).toString())
            .replace("%ROMPROVIDER%", providerUri(rom).toString())
            .replace("%ROMSAF%", storage.safUri(rom).toString())
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
            if (uri.scheme == "content") intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
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

    /**
     * One-time RetroArch setup for the in-game menu: enables the network command
     * interface (needed for pause/save/load/quit) and auto save/load states.
     * Needs root because RetroArch's config lives in its private app storage.
     */
    fun configureRetroArch(): String {
        val pkg = raPackage ?: return "RetroArch is not installed"
        if (!Root.available()) return "Root access is required"
        val path = raConfigPath(pkg)
        val cfg = Root.readFile(path) ?: return "Could not read $path (start RetroArch once first)"
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
        )
        if (settings.autoSaveOnExit) {
            wanted["savestate_auto_save"] = "\"true\""
            wanted["savestate_auto_load"] = "\"true\""
        }
        val lines = cfg.lines().toMutableList()
        for ((k, v) in wanted) {
            val i = lines.indexOfFirst { it.trimStart().startsWith("$k ") || it.trimStart().startsWith("$k=") }
            if (i >= 0) lines[i] = "$k = $v" else lines += "$k = $v"
        }
        Root.forceStop(pkg)
        return if (Root.writeFile(path, lines.joinToString("\n"))) "RetroArch configured for MainUI" else "Failed to write $path"
    }

    companion object {
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
