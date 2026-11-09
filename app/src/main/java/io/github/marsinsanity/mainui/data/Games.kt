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
    @Volatile private var systemsCache: List<GameSystem>? = null

    fun systems(includeUnknown: Boolean, refresh: Boolean = false): List<GameSystem> {
        if (!refresh) systemsCache?.let { return it }
        val s = registry.discover(includeUnknown)
        systemsCache = s
        return s
    }

    fun invalidate() {
        systemsCache = null
        dirCache.clear()
        gamelists.clear()
    }

    fun gamelist(dir: File): GameListXml {
        val key = dir.absolutePath
        val cached = gamelists[key]
        val f = File(dir, "gamelist.xml")
        if (cached != null && cached.loaded && (!f.exists() || f.lastModified() <= cachedMtime[key] ?: 0)) return cached
        val gl = GameListXml(dir).load()
        gamelists[key] = gl
        cachedMtime[key] = f.lastModified()
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
        val dirs = if (dir != null) listOf(dir) else system.dirs
        val merged = LinkedHashMap<String, Game>()
        for (d in dirs) for (g in listDir(system, d)) merged.putIfAbsent(g.name.lowercase() + "|" + g.isFolder, g)
        return merged.values.sortedWith(compareBy<Game>({ !it.isFolder }, { sortKey(it.name) }))
    }

    private fun sortKey(name: String): String = name.lowercase().removePrefix("the ")

    private fun listDir(system: GameSystem, dir: File): List<Game> {
        val gl = meta(systemRootFor(system, dir))
        val stamp = gl.stamp() * 7 + MainUIRef.metadataSource().hashCode()
        val cached = dirCache[dir.absolutePath]
        if (cached != null && cached.mtime == dir.lastModified() && cached.metaStamp == stamp) return cached.games
        val files = dir.listFiles() ?: return emptyList()
        val hiddenByCue = discFilesToHide(files)
        val out = ArrayList<Game>(files.size)
        for (f in files) {
            val n = f.name
            if (n.startsWith(".") || n == "gamelist.xml" || n.endsWith(".bak") || n.endsWith(".tmp") ||
                n.endsWith(".dat", true)) continue
            if (f.isDirectory) {
                if (n.lowercase() in SystemRegistry.IGNORED_FOLDERS || n.lowercase() in MEDIA_FOLDERS) continue
                val entry = gl.get(f)
                if (entry?.hidden == true) continue
                // Folders named like a rom (e.g. "Game.m3u/") count as a game, like ES-DE.
                if (f.extension.isNotEmpty() && system.def.accepts(f)) {
                    out += Game(system.id, f, entry?.name ?: f.nameWithoutExtension, imagePath = entry?.image, desc = entry?.desc)
                } else {
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

    /** gamelist.xml lives in the system's top folder even for games in sub folders. */
    fun systemRootFor(system: GameSystem, dir: File): File =
        system.dirs.firstOrNull { dir.absolutePath == it.absolutePath || dir.absolutePath.startsWith(it.absolutePath + "/") } ?: dir

    /** Hide .bin/.img tracks referenced by .cue and discs referenced by .m3u. */
    private fun discFilesToHide(files: Array<File>): Set<String> {
        val hide = HashSet<String>()
        for (f in files) {
            val ext = f.extension.lowercase()
            if (ext == "cue" || ext == "gdi") {
                runCatching {
                    f.useLines { lines ->
                        lines.take(200).forEach { line ->
                            val m = Regex("FILE\\s+\"([^\"]+)\"", RegexOption.IGNORE_CASE).find(line)
                                ?: if (ext == "gdi") Regex("\\s(\\S+\\.(?:bin|raw))\\s", RegexOption.IGNORE_CASE).find(line) else null
                            if (m != null) hide += m.groupValues[1].substringAfterLast('/').lowercase()
                        }
                    }
                }
            } else if (ext == "m3u") {
                runCatching {
                    f.useLines { lines -> lines.take(50).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEach { hide += it.substringAfterLast('/').lowercase() } }
                }
            }
        }
        return hide
    }

    fun countGames(system: GameSystem): Int {
        var n = 0
        fun walk(d: File, depth: Int, gl: MetaLookup) {
            val files = d.listFiles() ?: return
            val hidden = discFilesToHide(files)
            for (f in files) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (depth < 3 && f.name.lowercase() !in MEDIA_FOLDERS && f.name.lowercase() !in SystemRegistry.IGNORED_FOLDERS) {
                        if (f.extension.isNotEmpty() && system.def.accepts(f)) n++ else walk(f, depth + 1, gl)
                    }
                } else if (system.def.accepts(f) && f.name != "gamelist.xml" && !f.name.endsWith(".dat", true) && f.name.lowercase() !in hidden &&
                    gl.get(f)?.hidden != true) n++
            }
        }
        system.dirs.forEach { walk(it, 0, meta(it)) }
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
        val candidates = ArrayList<File>(24)
        for (folder in IMAGE_FOLDERS) {
            val d = if (relDir.isEmpty()) File(sysRoot, folder) else File(File(sysRoot, folder), relDir)
            for (suffix in IMAGE_SUFFIXES) for (ext in IMAGE_EXTS) candidates += File(d, "$base$suffix.$ext")
        }
        for (c in candidates) for (m in storage.mirrors(c)) if (m.isFile) return m
        return null
    }

    companion object {
        val MEDIA_FOLDERS = setOf("images", "imgs", "media", "videos", "manuals", "snap", "snaps", "boxart", "box2dfront",
            "screenshots", "wheel", "marquees", "downloaded_images", "downloaded_media")
        val IMAGE_FOLDERS = listOf("images", "Imgs", "media/images", "media/box2dfront", "media/screenshots",
            "downloaded_images", "boxart")
        val IMAGE_SUFFIXES = listOf("", "-image", "-thumb", "-boxart")
        val IMAGE_EXTS = listOf("png", "jpg", "jpeg", "webp")

        private val TAGS = Regex("\\s*[\\(\\[][^\\)\\]]*[\\)\\]]")

        /** "Super Game (USA) [!]" -> "Super Game" like MainUI's short names. */
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
}
