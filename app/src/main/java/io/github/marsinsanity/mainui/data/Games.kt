package io.github.marsinsanity.mainui.data

import io.github.marsinsanity.mainui.core.Storage
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** One entry in a game list: a rom file, a sub folder, or an Android app. */
data class Game(
    val systemId: String,
    val file: File?,
    val name: String,
    val isFolder: Boolean = false,
    val pkg: String? = null,
    val imagePath: String? = null,
    val desc: String? = null,
) {
    /** Stable identity used by recents, favorites, screenshots and core overrides. */
    val key: String get() = if (pkg != null) "android:$pkg" else "$systemId:${file?.absolutePath}"
    val isAndroid get() = pkg != null
}

/**
 * Scans rom folders, applies gamelist.xml names/hidden flags and finds artwork.
 * Results are cached per directory and invalidated by directory mtime.
 */
class GameRepository(private val registry: SystemRegistry, private val storage: Storage) {

    private class DirCache(val mtime: Long, val metaStamp: Long, val games: List<Game>)

    private val dirCache = ConcurrentHashMap<String, DirCache>()
    private val gamelists = ConcurrentHashMap<String, GameListXml>()
    /** What [systems] found, for which "unknown folders" setting and rom roots. */
    private class SystemsCache(val includeUnknown: Boolean, val roots: List<String>, val systems: List<GameSystem>)
    @Volatile private var systemsCache: SystemsCache? = null

    fun systems(includeUnknown: Boolean, refresh: Boolean = false): List<GameSystem> {
        // Found again when the rom roots change (an SD card mounted after the first look, at
        // boot, or swapped) or when asked with the other "unknown folders" setting.
        val roots = runCatching { storage.romRoots().map { it.absolutePath } }.getOrDefault(emptyList())
        val c = systemsCache
        val found = c?.takeIf { !refresh && it.includeUnknown == includeUnknown && it.roots == roots }?.systems
            ?: registry.discover(includeUnknown).also { systemsCache = SystemsCache(includeUnknown, roots, it) }
        return withMenuSystems(found)
    }

    /** Folderless systems kept by id, so their game counts aren't recounted every time. */
    private val menuSystems = ConcurrentHashMap<String, GameSystem>()

    /**
     * Adds the systems that have no folder but a [SystemDef.menu] entry whose emulator is
     * installed (PICO-8 with the PICO-8 app: Splore). Checked on every call, so installing or
     * removing the emulator shows / hides the system without a rescan.
     */
    private fun withMenuSystems(found: List<GameSystem>): List<GameSystem> {
        val extra = registry.defs.filter { d ->
            found.none { it.id == d.id } && (d.menu.any { MainUIRef.emulatorInstalled(it.emulator) } ||
                // Ports: where Android apps can be put, before there is a ports folder (adding one creates it).
                (d.id == AndroidPorts.SYSTEM && MainUIRef.showPortsSystem()))
        }
        if (extra.isEmpty()) return found
        return (found + extra.map { d -> menuSystems.getOrPut(d.id) { GameSystem(d, emptyList()) } }).sortedBy { it.name.lowercase() }
    }

    /**
     * The game a [SystemMenuItem] launches: its file name in the system's first folder, or where
     * that folder would be (the file needn't exist; the emulator only reads its name).
     */
    fun menuGame(system: GameSystem, item: SystemMenuItem): Game {
        val dir = system.dirs.firstOrNull()
            ?: File(storage.romRoots().firstOrNull() ?: File("/storage/emulated/0/Roms"), system.def.folders.firstOrNull() ?: system.id)
        return Game(system.id, File(dir, item.rom), item.name)
    }

    /** The ports folders of the rom roots (see [AndroidPorts]). */
    fun portDirs(): List<File> = AndroidPorts.dirs(storage.romRoots())

    /** Packages of the Android apps in the Ports system. */
    fun portPackages(): Set<String> = runCatching { AndroidPorts.packages(portDirs()) }.getOrDefault(emptySet())

    /** Puts the app [pkg] into the Ports system (see [AndroidPorts.add]); the lists are read again. */
    fun addPort(label: String, pkg: String, write: (File, String) -> Unit = { f, t -> f.writeText(t) }): File =
        AndroidPorts.add(storage.romRoots(), label, pkg, write).also { invalidate() }

    /** Takes the app [pkg] out of the Ports system. Returns the files that couldn't be deleted. */
    fun removePort(pkg: String): List<File> = AndroidPorts.remove(storage.romRoots(), pkg).also { invalidate() }

    fun invalidate() {
        systemsCache = null
        menuSystems.clear()
        dirCache.clear()
        gamelists.clear()
        listings.clear()
    }

    fun gamelist(dir: File): GameListXml {
        val key = dir.absolutePath
        val cached = gamelists[key]
        val f = File(dir, "gamelist.xml")
        // Any change counts (deleted, or replaced by an older copy); its time is read before the
        // load, so a save during the load is seen next time.
        val m = if (f.isFile) f.lastModified() else 0L
        if (cached != null && cached.loaded && cachedMtime[key] == m) return cached
        val gl = GameListXml(dir).load()
        gamelists[key] = gl
        cachedMtime[key] = m
        return gl
    }

    private val cachedMtime = ConcurrentHashMap<String, Long>()
    private val dats = ConcurrentHashMap<String, LogiqxDat>()

    /** The Logiqx DAT of a system folder, reloaded when it changes. */
    fun dat(dir: File): LogiqxDat? {
        val f = LogiqxDat.locate(dir) ?: run { dats.remove(dir.absolutePath); return null }
        val cached = dats[dir.absolutePath]
        if (cached != null && cached.file == f && cached.stamp() == f.lastModified() && cachedDatMtime[dir.absolutePath] == f.lastModified()) return cached
        val d = LogiqxDat(f).load()
        dats[dir.absolutePath] = d
        cachedDatMtime[dir.absolutePath] = f.lastModified()
        return d
    }
    private val cachedDatMtime = ConcurrentHashMap<String, Long>()

    /**
     * Metadata for a system folder according to Settings > Library > Metadata:
     * gamelist.xml, a Logiqx DAT, or both (gamelist.xml wins, the DAT fills gaps).
     */
    fun meta(dir: File): MetaLookup {
        return when (MainUIRef.metadataSource()) {
            "gamelist" -> gamelist(dir)
            "logiqx" -> dat(dir) ?: EmptyLookup
            else -> {
                val gl = gamelist(dir)
                val d = dat(dir)
                if (d == null) gl else if (!gl.file.isFile) d else CombinedLookup(gl, d)
            }
        }
    }

    /**
     * Lists one directory of a system. [dir] null = the system's root folders
     * merged across every rom root (SD card + internal storage).
     */
    fun list(system: GameSystem, dir: File? = null): List<Game> {
        val dirs = if (dir != null) mirrorsOf(system, dir) else system.dirs
        // The same file (or folder) on several rom roots is listed once: keyed by its file name,
        // never by display name, which region versions, revisions and discs share.
        val merged = LinkedHashMap<String, Game>()
        for (d in dirs) for (g in listDir(system, d)) merged.putIfAbsent((g.file?.name ?: g.name).lowercase() + "|" + g.isFolder, g)
        // Hidden games (Hide ROM, known BIOS sets) unless Settings > Library > Show hidden games.
        val shown = if (MainUIRef.showHidden()) merged.values.toList() else merged.values.filter { !isHidden(it) }
        return distinctNames(shown).sortedWith(compareBy<Game>({ !it.isFolder }, { sortKey(it.name) }))
    }

    /** Hidden with Hide ROM, or a known BIOS set (neogeo.zip...) nobody unhid. */
    fun isHidden(g: Game): Boolean = !g.isFolder && !g.isAndroid && MainUIRef.hidden(g.key, isBios(g.file))

    private fun isHiddenFile(system: GameSystem, f: File) = MainUIRef.hidden("${system.id}:${f.absolutePath}", isBios(f))

    /** A sub folder and the folders at the same place in the system's other rom roots (SD + internal). */
    private fun mirrorsOf(system: GameSystem, dir: File): List<File> {
        val root = system.dirs.firstOrNull { dir.absolutePath == it.absolutePath || dir.absolutePath.startsWith(it.absolutePath + "/") }
            ?: return listOf(dir)
        val rel = dir.relativeTo(root).path
        return listOf(dir) + system.dirs.filter { it.absolutePath != root.absolutePath }.map { if (rel.isEmpty()) it else File(it, rel) }
            .filter { it.isDirectory }
    }

    /** Games whose display names are the same (USA / Europe, Rev 1 / Rev 2, Disc 1 / 2) show their file names. */
    private fun distinctNames(games: List<Game>): List<Game> {
        val clash = games.filter { !it.isFolder }.groupBy { it.name.lowercase() }.filterValues { it.size > 1 }
        if (clash.isEmpty()) return games
        val renamed = HashMap<Game, String>()
        for (group in clash.values) {
            val base = group.associateWith { it.file?.nameWithoutExtension ?: it.name }
            val unique = base.values.map { it.lowercase() }.toSet().size == group.size
            for (g in group) renamed[g] = if (unique) base.getValue(g) else g.file?.name ?: g.name
        }
        return games.map { g -> renamed[g]?.let { g.copy(name = it) } ?: g }
    }

    private fun sortKey(name: String): String = name.lowercase().removePrefix("the ")

    private fun listDir(system: GameSystem, dir: File): List<Game> {
        val gl = meta(systemRootFor(system, dir))
        val stamp = gl.stamp() * 7 + MainUIRef.metadataSource().hashCode()
        val cached = dirCache[dir.absolutePath]
        if (cached != null && cached.mtime == dir.lastModified() && cached.metaStamp == stamp) return cached.games
        val files = dir.listFiles() ?: return emptyList()
        val hiddenByCue = discFilesToHide(system, files) + unpackedZips(system, files)
        val out = ArrayList<Game>(files.size)
        for (f in files) {
            val n = f.name
            if (n.startsWith(".") || n == "gamelist.xml" || n.endsWith(".bak") || n.endsWith(".tmp") ||
                isMetaDat(system, f)) continue
            if (f.isDirectory) {
                if (n.lowercase() in SystemRegistry.IGNORED_FOLDERS || n.lowercase() in MEDIA_FOLDERS) continue
                val entry = gl.get(f)
                if (entry?.hidden == true) continue
                // Folders named like a rom (e.g. "Game.m3u/") count as a game, like ES-DE.
                if (f.extension.isNotEmpty() && system.def.accepts(f)) {
                    out += Game(system.id, f, entry?.name ?: f.nameWithoutExtension, imagePath = entry?.image, desc = entry?.desc)
                } else if (entry != null || hasRoms(system, f, 0)) {
                    // Folders without any playable file (e.g. RetroArch's per-core save
                    // folders like "Gambatte/" next to the roms) are not shown.
                    out += Game(system.id, f, entry?.name ?: n, isFolder = true, imagePath = entry?.image)
                }
                continue
            }
            if (!system.def.accepts(f)) continue
            if (n.lowercase() in hiddenByCue) continue
            val entry = gl.get(f)
            if (entry?.hidden == true) continue
            out += Game(system.id, f, entry?.name?.takeIf { it.isNotBlank() } ?: cleanName(f.nameWithoutExtension),
                imagePath = entry?.image ?: entry?.thumbnail, desc = entry?.desc)
        }
        dirCache[dir.absolutePath] = DirCache(dir.lastModified(), stamp, out)
        return out
    }

    private fun hasRoms(system: GameSystem, dir: File, depth: Int): Boolean {
        val files = dir.listFiles() ?: return false
        for (f in files) {
            if (f.name.startsWith(".")) continue
            if (f.isDirectory) {
                if (f.extension.isNotEmpty() && system.def.accepts(f)) return true
            } else if (system.def.accepts(f) && !isMetaDat(system, f) && f.name != "gamelist.xml") return true
        }
        if (depth >= 2) return false
        return files.any { it.isDirectory && !it.name.startsWith(".") && it.name.lowercase() !in MEDIA_FOLDERS &&
            it.name.lowercase() !in SystemRegistry.IGNORED_FOLDERS && hasRoms(system, it, depth + 1) }
    }

    /**
     * A .dat is metadata (a Logiqx DAT), unless the system plays .dat files (ONScripter's
     * nscript.dat, Naomi images): then only MainUI's own DAT and the folder's XML DAT (the one
     * read for names, which the scraper writes into) are metadata.
     */
    private fun isMetaDat(system: GameSystem, f: File): Boolean {
        if (!f.name.endsWith(".dat", true)) return false
        if ("dat" !in system.def.extensions || f.name.endsWith(".mainui.dat", true)) return true
        val dir = f.parentFile ?: return false
        return LogiqxDat.locate(dir)?.absolutePath == f.absolutePath
    }

    /** gamelist.xml lives in the system's top folder even for games in sub folders. */
    fun systemRootFor(system: GameSystem, dir: File): File =
        system.dirs.firstOrNull { dir.absolutePath == it.absolutePath || dir.absolutePath.startsWith(it.absolutePath + "/") } ?: dir

    /** Zips whose image was unpacked beside them for launching (Xbox, see ZipImages): only the image is listed. */
    private fun unpackedZips(system: GameSystem, files: Array<File>): Set<String> {
        val types = system.def.unzip
        if (types.isEmpty()) return emptySet()
        val names = files.mapTo(HashSet()) { it.name.lowercase() }
        // (Only a single-image zip: a multi-disc one still holds discs that weren't unpacked.)
        return files.filter { f -> f.extension.equals("zip", true) && types.any { t -> "${f.nameWithoutExtension}.$t".lowercase() in names } &&
            runCatching { io.github.marsinsanity.mainui.emu.ZipImages.imageCount(f, types) }.getOrDefault(2) <= 1 }
            .mapTo(HashSet()) { it.name.lowercase() }
    }

    /** Hide .bin/.img tracks referenced by .cue and discs referenced by .m3u. */
    private fun discFilesToHide(system: GameSystem, files: Array<File>): Set<String> {
        val hide = HashSet<String>()
        for (f in files) {
            val ext = f.extension.lowercase()
            if (ext == "cue" || ext == "gdi") {
                runCatching {
                    f.useLines { lines ->
                        lines.take(200).forEach { raw ->
                            val line = raw.removePrefix("\uFEFF")
                            // FILE "Game (Track 1).bin" BINARY, or unquoted: FILE GAME.BIN BINARY
                            val m = CUE_FILE.find(line)
                                ?: if (ext == "gdi") GDI_TRACK.find(line) else null
                            if (m != null) hide += m.groupValues[1].ifEmpty { m.groupValues.getOrElse(2) { "" } }
                                .replace('\\', '/').substringAfterLast('/').lowercase()
                        }
                    }
                }
            } else if ((ext == "ccd" || ext == "mds") && system.def.accepts(f)) {
                // CloneCD (.ccd + .img + .sub) and Alcohol (.mds + .mdf): the descriptor is the game.
                val base = f.nameWithoutExtension.lowercase()
                if (ext == "ccd") { hide += "$base.img"; hide += "$base.sub" } else hide += "$base.mdf"
            } else if (ext == "m3u") {
                runCatching {
                    // (A Windows editor's byte order mark must not hide disc 1 from the match.)
                    f.useLines { lines -> lines.take(50).map { it.removePrefix("\uFEFF").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { hide += it.replace('\\', '/').substringAfterLast('/').lowercase() } }
                }
            }
        }
        return hide
    }

    fun countGames(system: GameSystem): Int {
        var n = 0
        val others = HashMap<String, Int>()
        // A game on both the SD card and internal storage is listed once (the lists merge), so
        // it is counted once: by its path inside the system folder.
        val seen = HashSet<String>()
        fun count(rel: String) { if (seen.add(rel.lowercase())) n++ }
        fun walk(d: File, rel: String, depth: Int, gl: MetaLookup) {
            val files = d.listFiles() ?: return
            val hidden = discFilesToHide(system, files) + unpackedZips(system, files)
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth < 3 && f.name.lowercase() !in MEDIA_FOLDERS && f.name.lowercase() !in SystemRegistry.IGNORED_FOLDERS) {
                        if (f.extension.isNotEmpty() && system.def.accepts(f)) count("$rel/${f.name}") else walk(f, "$rel/${f.name}", depth + 1, gl)
                    }
                } else if (system.def.accepts(f) && f.name != "gamelist.xml" && !isMetaDat(system, f) && f.name.lowercase() !in hidden &&
                    gl.get(f)?.hidden != true && !isHiddenFile(system, f)) count("$rel/${f.name}")
                // Files it could play but that are hidden (BIOS sets, disc tracks) aren't "unplayable" ones.
                else if (!system.def.accepts(f) && f.extension.isNotEmpty() && f.name != "gamelist.xml" && f.extension.lowercase() !in NOT_GAMES)
                    others.merge(f.extension.lowercase(), 1, Int::plus)
            }
        }
        system.dirs.forEach { walk(it, "", 0, meta(it)) }
        system.otherFiles = others
        system.gameCount = n
        return n
    }

    /** Recursive search over every system for names/filenames containing [query]. */
    fun search(query: String, systems: List<GameSystem>, limit: Int = 500): List<Game> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<Game>()
        for (s in systems) {
            fun walk(dir: File?, depth: Int) {
                for (g in list(s, dir)) {
                    if (out.size >= limit) return
                    if (g.isFolder) {
                        if (depth < 3) walk(g.file, depth + 1)
                    } else if (g.name.lowercase().contains(q) || g.file?.name?.lowercase()?.contains(q) == true) {
                        out += g
                    }
                }
            }
            walk(null, 0)
            if (out.size >= limit) break
        }
        return out.sortedBy { it.name.lowercase() }
    }

    /**
     * Finds artwork for a game. Order: gamelist.xml image, then the usual
     * Skraper/ES/Onion folders, on the rom's volume and mirrored on other volumes.
     */
    fun imageFor(system: GameSystem?, game: Game): File? {
        val file = game.file ?: return null
        val sysRoot = if (system != null) systemRootFor(system, file.parentFile ?: file) else file.parentFile ?: return null
        game.imagePath?.let { p ->
            val gl = meta(sysRoot)
            val f = gl.resolve(p)
            if (f != null) storage.mirrors(f).firstOrNull { it.isFile }?.let { return it }
        }
        val base = file.nameWithoutExtension
        val relDir = runCatching { file.parentFile!!.relativeTo(sysRoot).path }.getOrDefault("")
        // One listing per image folder instead of a file check per name: 7 folders x 4 suffixes x
        // 4 types x every volume is over 200 checks per game, very slow on an SD card.
        for (folder in IMAGE_FOLDERS) {
            val d = if (relDir.isEmpty()) File(sysRoot, folder) else File(File(sysRoot, folder), relDir)
            for (m in storage.mirrors(d)) {
                val names = folderNames(m) ?: continue
                for (suffix in IMAGE_SUFFIXES) for (ext in IMAGE_EXTS) {
                    // Android's shared storage and SD cards ignore case, so the lookup does too.
                    names["$base$suffix.$ext".lowercase()]?.let { return File(m, it) }
                }
            }
        }
        return null
    }

    private class Listing(val at: Long, val names: Map<String, String>?)
    private val listings = ConcurrentHashMap<String, Listing>()

    /** Lowercase name -> file name in an image folder (null if it doesn't exist), remembered for a few seconds. */
    private fun folderNames(dir: File): Map<String, String>? {
        val now = System.currentTimeMillis()
        listings[dir.path]?.let { if (now - it.at < 5000) return it.names }
        val names = dir.list()?.associateBy { it.lowercase() }
        if (listings.size > 2000) listings.clear()
        listings[dir.path] = Listing(now, names)
        return names
    }

    companion object {
        val MEDIA_FOLDERS = setOf("images", "imgs", "media", "videos", "manuals", "snap", "snaps", "boxart", "box2dfront",
            "screenshots", "wheel", "marquees", "downloaded_images", "downloaded_media")
        val IMAGE_FOLDERS = listOf("images", "Imgs", "media/images", "media/box2dfront", "media/screenshots",
            "downloaded_images", "boxart")
        val IMAGE_SUFFIXES = listOf("", "-image", "-thumb", "-boxart")
        val IMAGE_EXTS = listOf("png", "jpg", "jpeg", "webp")
        /** Files that are never games, left out of "no playable files" hints. */
        private val CUE_FILE = Regex("FILE\\s+(?:\"([^\"]+)\"|(\\S+))", RegexOption.IGNORE_CASE)
        private val GDI_TRACK = Regex("\\s(\\S+\\.(?:bin|raw))\\s", RegexOption.IGNORE_CASE)

        val NOT_GAMES = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "txt", "nfo", "xml", "dat", "bak", "tmp", "md",
            "pdf", "htm", "html", "db", "ini", "cfg", "log", "srm", "sav", "state", "auto", "ds_store")

        private val TAGS = Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]")

        /** "Super Game (USA) [!]" -> "Super Game" like MainUI's short names. */
        /**
         * Arcade BIOS / device sets: they must sit next to the games that need them, but can't be
         * played themselves, so they are hidden from the lists (unhide one with Unhide ROM).
         */
        val BIOS_SETS = setOf("neogeo", "pgm", "skns", "decocass", "isgsm", "naomi", "naomi2", "naomigd", "awbios",
            "hod2bios", "f355bios", "f355dlx", "airlbios", "cpzn1", "cpzn2", "qsound", "qsound_hle", "stvbios", "nss",
            "playch10", "megatech", "megaplay", "cvs", "coh1000a", "coh1000c", "coh1000t", "coh1000w", "coh1001l", "coh1002e",
            "coh1002m", "coh1002v", "coh3002c", "coh3002t", "taitofx1", "taitogn", "tps", "sys246", "sys256", "konamigx",
            "crysbios", "ym2608", "ym2413", "segasp", "triforce", "chihiro", "hng64", "aleck64", "ar_bios", "bubsys",
            "neocdz", "atarisy1", "galgbios", "gp_110", "sammymdl", "zn1", "zn2", "pgm2", "allied", "acpsx", "bios")

        /** A known BIOS set's archive (neogeo.zip, pgm.7z...). */
        fun isBios(f: File?): Boolean = f != null && f.extension.lowercase() in setOf("zip", "7z") &&
            f.nameWithoutExtension.lowercase() in BIOS_SETS

        fun cleanName(s: String): String {
            val c = TAGS.replace(s, "").trim()
            return if (c.isEmpty()) s else c
        }
    }
}

/** Used when the chosen metadata file does not exist: plain file names. */
object EmptyLookup : MetaLookup {
    override fun get(romFile: File): GameListXml.Entry? = null
    override fun resolve(value: String?): File? = null
    override fun stamp(): Long = 0L
}

/** Indirection so this file stays free of Android singletons in unit tests. */
object MainUIRef {
    @Volatile var metadataSource: () -> String = { "both" }
    /** Whether the game with this key is hidden (bios: a known BIOS set, hidden unless shown). */
    @Volatile var hidden: (key: String, bios: Boolean) -> Boolean = { _, bios -> bios }
    /** Settings > Library > Show hidden games. */
    @Volatile var showHidden: () -> Boolean = { false }
    /** Whether the emulator "sa:<id>" / "ra:<core>" is installed (for [SystemDef.menu]). */
    @Volatile var emulatorInstalled: (ref: String) -> Boolean = { false }
    /** Settings > User interface > Show Ports system (listed before its folder exists). */
    @Volatile var showPortsSystem: () -> Boolean = { true }
}
