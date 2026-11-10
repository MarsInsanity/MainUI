package io.github.marsinsanity.mainui.data

import java.io.File

/**
 * The files a game is made of, for deleting it from the collection: the rom itself plus what it
 * points to or comes with. A .cue / .gdi's tracks, an .m3u's discs (and their tracks), CloneCD's
 * .img / .sub next to a .ccd, Alcohol's .mdf next to an .mds, and the zip an image was unpacked
 * from (Xbox / GameCube, see ZipImages) or the image unpacked from a zip. Only existing files in
 * the rom's own folder (or below it) are listed, and never one that another game in that folder
 * still points to (two .cue sheets sharing a track).
 */
object RomFiles {
    /** FILE "Game (Track 1).bin" BINARY, or unquoted: FILE GAME.BIN BINARY. */
    private val CUE_FILE = Regex("FILE\\s+(?:\"([^\"]+)\"|(\\S+))", RegexOption.IGNORE_CASE)
    private val GDI_TRACK = Regex("^\\s*\\d+\\s+\\d+\\s+\\d+\\s+\\d+\\s+(\"[^\"]+\"|\\S+)")
    private val DESCRIPTORS = setOf("cue", "gdi", "m3u", "ccd", "mds")

    /** [rom] first, then the files that belong to it. [unzipTypes]: the system's unpacked image types ("iso"...). */
    fun related(rom: File, unzipTypes: Collection<String> = emptyList()): List<File> {
        val dir = rom.parentFile ?: return listOf(rom)
        if (!rom.isFile) return listOf(rom) // a folder game: the folder is the game
        val out = LinkedHashSet<File>()
        out += rom
        // What the rom points to, and what those point to (an .m3u's .cue sheets and their tracks).
        val queue = ArrayDeque(listOf(rom))
        while (queue.isNotEmpty()) {
            for (r in refs(queue.removeFirst())) if (r.isFile && inside(r, dir) && out.add(r)) queue += r
        }
        // Files that only go with it by name (next to their descriptor, which may be in a sub folder).
        val names = dir.listFiles()?.associateBy { it.name.lowercase() }.orEmpty()
        fun sibling(name: String) = names[name.lowercase()]?.takeIf { it.isFile }
        val base = rom.nameWithoutExtension
        for (f in out.toList()) companions(f, dir, names).filterTo(out) { inside(it, dir) }
        val types = unzipTypes.map { it.lowercase() }
        if (types.isNotEmpty()) {
            val ext = rom.extension.lowercase()
            if (ext == "zip") types.mapNotNullTo(out) { sibling("$base.$it") }
            else if (ext in types) sibling("$base.zip")?.let { out += it }
        }
        // Keep what other games in the folder still use: what their descriptors name, and what those
        // name or come with (a Game.ccd kept beside the deleted Game.cue still needs Game.img).
        val others = dir.listFiles().orEmpty().filter { it.isFile && it !in out && it.extension.lowercase() in DESCRIPTORS }
        val shared = HashSet<String>()
        val seen = HashSet<String>()
        val q = ArrayDeque(others)
        while (q.isNotEmpty()) {
            val o = q.removeFirst()
            if (!seen.add(canon(o))) continue
            for (r in refs(o) + companions(o, dir, names)) if (shared.add(canon(r))) q += r
        }
        return out.filter { it == rom || canon(it) !in shared }
    }

    /** CloneCD's .img / .sub beside a .ccd, Alcohol's .mdf beside an .mds ([names]: [dir]'s files). */
    private fun companions(f: File, dir: File, names: Map<String, File>): List<File> {
        val b = f.nameWithoutExtension
        val wanted = when (f.extension.lowercase()) {
            "ccd" -> listOf("$b.img", "$b.sub")
            "mds" -> listOf("$b.mdf")
            else -> return emptyList()
        }
        val here = if (f.parentFile == dir) names else f.parentFile?.listFiles()?.associateBy { it.name.lowercase() }.orEmpty()
        return wanted.mapNotNull { n -> here[n.lowercase()]?.takeIf { it.isFile } }
    }

    /** Files a descriptor (.cue, .gdi, .m3u) names, resolved next to it. */
    fun refs(f: File): List<File> {
        val ext = f.extension.lowercase()
        if (ext != "cue" && ext != "gdi" && ext != "m3u") return emptyList()
        if (!f.isFile || f.length() > 1_000_000) return emptyList()
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        val names = when (ext) {
            "cue" -> lines.mapNotNull { l -> CUE_FILE.find(l.removePrefix("\uFEFF"))?.groupValues?.let { it[1].ifEmpty { it[2] } } }
            "gdi" -> lines.drop(1).mapNotNull { GDI_TRACK.find(it)?.groupValues?.get(1)?.trim('"') }
            else -> lines.map { it.removePrefix("\uFEFF").trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        }
        return names.map { File(f.parentFile, it.replace('\\', '/')) }
    }

    private fun canon(f: File) = runCatching { f.canonicalPath }.getOrDefault(f.absolutePath)

    private fun inside(f: File, dir: File) = canon(f).startsWith(canon(dir) + File.separator)

    /** Deletes [files] (a folder with everything in it). Returns the ones that couldn't be deleted. */
    fun delete(files: List<File>): List<File> =
        files.filter { f -> f.exists() && !(if (f.isDirectory) f.deleteRecursively() else f.delete()) }
}
