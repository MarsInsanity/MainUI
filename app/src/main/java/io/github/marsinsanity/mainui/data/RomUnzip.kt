package io.github.marsinsanity.mainui.data

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Unzips the .zip files in a system's folders for systems whose emulators can't open archives
 * (Game & Watch's .mgw, disc images...). Only zips holding files the system plays are touched;
 * each one is unpacked next to itself (its own folders kept), never over an existing file, and
 * through a temporary ".part" file, so a stopped or failed unzip leaves nothing half written.
 */
object RomUnzip {
    /** Kept free on top of what an unzip needs, so the storage never fills up completely. */
    const val MARGIN = 64L shl 20

    /** Files in a game zip that aren't worth unpacking. */
    private val JUNK = setOf("txt", "nfo", "diz", "url", "htm", "html", "pdf", "db", "ini", "md")

    /** What happens to the zips. */
    enum class Mode {
        /** All unpacked first, then every zip that came out whole deleted (none if it stopped part way). */
        DELETE_AFTER,
        /** Each zip deleted as soon as it is unpacked: room for only the biggest one is needed. */
        DELETE_EACH,
        /** The zips stay. */
        KEEP,
    }

    /** A zip with games in it: the entries to unpack and their unpacked size. */
    class Archive(val zip: File, val entries: List<String>, val bytes: Long, val games: Int)

    class Plan(val archives: List<Archive>) {
        val bytes get() = archives.sumOf { it.bytes }
        val largest get() = archives.maxOfOrNull { it.bytes } ?: 0L
        val games get() = archives.sumOf { it.games }
    }

    class Result(val unpacked: Int, val existing: Int, val zipsDone: Int, val zipsDeleted: Int,
                 val failed: List<Pair<File, String>>, val stopped: Boolean, val problem: String?)

    /**
     * What to unpack from a zip with these entries (name to size): its files when at least one
     * is a game the system plays ([accepts]), leaving out folders, Mac / hidden files and readmes.
     * Empty when it holds no game. The game count comes along.
     */
    fun pick(entries: List<Pair<String, Long>>, accepts: (String) -> Boolean): Pair<List<Pair<String, Long>>, Int> {
        val files = entries.filter { (n, _) ->
            !n.endsWith("/") && !n.startsWith("__MACOSX/") && !n.substringAfterLast('/').startsWith(".") &&
                n.substringAfterLast('.', "").lowercase() !in JUNK
        }
        val games = files.count { accepts(it.first.substringAfterLast('/')) }
        return if (games == 0) emptyList<Pair<String, Long>>() to 0 else files to games
    }

    /**
     * The zips under [dirs] (a few folders deep, skipping folders [skipDir] names: artwork,
     * BIOS...) that hold games, with what to unpack from each. Unreadable zips are left out.
     */
    fun scan(dirs: List<File>, accepts: (String) -> Boolean, skipDir: (String) -> Boolean = { false }): Plan {
        val out = ArrayList<Archive>()
        fun walk(d: File, depth: Int) {
            for (f in d.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { if (depth < 3 && !skipDir(f.name.lowercase())) walk(f, depth + 1); continue }
                if (!f.extension.equals("zip", true)) continue
                runCatching {
                    ZipFile(f).use { z ->
                        val entries = z.entries().asSequence().map { it.name to it.size.coerceAtLeast(0) }.toList()
                        val (files, games) = pick(entries, accepts)
                        // Unknown sizes count as the zip's own size: never less than that unpacked.
                        if (files.isNotEmpty()) out += Archive(f, files.map { it.first },
                            maxOf(files.sumOf { it.second }, f.length()), games)
                    }
                }
            }
        }
        dirs.forEach { walk(it, 0) }
        return Plan(out)
    }

    /**
     * Free space an unzip needs on the storage holding [archives], plus [MARGIN]: everything for
     * [Mode.KEEP] and [Mode.DELETE_AFTER] (the zips are all still there until the end), only the
     * biggest zip's contents for [Mode.DELETE_EACH] (each zip goes once it's unpacked).
     */
    fun spaceNeeded(archives: List<Archive>, mode: Mode): Long =
        (if (mode == Mode.DELETE_EACH) archives.maxOfOrNull { it.bytes } ?: 0L else archives.sumOf { it.bytes }) + MARGIN

    /** Where [entry] of a zip in [dir] goes, or null for a name pointing outside [dir] ("../x"). */
    fun target(dir: File, entry: String): File? {
        val rel = entry.replace('\\', '/').trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        val f = File(dir, rel)
        val base = dir.canonicalPath + File.separator
        return f.takeIf { runCatching { it.canonicalPath.startsWith(base) }.getOrDefault(false) }
    }

    /**
     * Unpacks [plan], zip by zip; what happens to the zips depends on [mode] (a zip is only ever
     * deleted when all of it unpacked). Before each zip its storage must have room for it (plus
     * [MARGIN]), else it stops there and says so. [fallback] can unpack a zip another way (root)
     * when writing its folder fails; it gets the zip and its folder and says whether that worked.
     * [progress] gets (zip number, zip count, zip name).
     */
    fun unpack(plan: Plan, mode: Mode, progress: (Int, Int, String) -> Unit, cancelled: () -> Boolean,
               fallback: ((File, File) -> Boolean)? = null): Result {
        var unpacked = 0; var existing = 0; var done = 0; var deleted = 0
        val failed = ArrayList<Pair<File, String>>()
        val whole = ArrayList<File>() // came out whole: deleted at the end for DELETE_AFTER
        // DELETE_AFTER deletes nothing unless the whole run got through.
        fun finish(stopped: Boolean, problem: String?): Result {
            if (mode == Mode.DELETE_AFTER && !stopped && problem == null) whole.forEach { if (it.delete()) deleted++ }
            return Result(unpacked, existing, done, deleted, failed, stopped, problem)
        }
        for ((i, a) in plan.archives.withIndex()) {
            if (cancelled()) return finish(true, null)
            progress(i + 1, plan.archives.size, a.zip.name)
            val dir = a.zip.parentFile ?: continue
            if (dir.usableSpace < a.bytes + MARGIN) {
                return finish(false, "Not enough space for ${a.zip.name}: it needs ${size(a.bytes + MARGIN)}, ${size(dir.usableSpace)} is free")
            }
            var ok = true
            var written = 0; var already = 0; var differs: String? = null
            try {
                ZipFile(a.zip).use { z ->
                    for (name in a.entries) {
                        if (cancelled()) throw StopException()
                        val e = z.getEntry(name) ?: continue
                        val dest = target(dir, name) ?: continue // outside the folder: never written
                        if (dest.exists()) {
                            // Unpacked before (same size): fine. Another file of that name: left alone, zip kept.
                            if (e.size < 0 || dest.length() == e.size) already++ else differs = dest.name
                            continue
                        }
                        dest.parentFile?.mkdirs()
                        val part = File(dest.parentFile, ".${dest.name}.part")
                        try {
                            z.getInputStream(e).use { input -> part.outputStream().use { input.copyTo(it, 1 shl 20) } }
                            if (!part.renameTo(dest)) throw IOException("could not rename ${part.name}")
                            written++
                        } finally {
                            part.delete()
                        }
                    }
                }
            } catch (e: StopException) {
                unpacked += written; existing += already
                return finish(true, null)
            } catch (e: Exception) {
                ok = fallback?.invoke(a.zip, dir) == true
                if (!ok) failed += a.zip to (e.message ?: e.toString())
            }
            unpacked += written; existing += already
            if (!ok) continue
            done++
            if (differs != null) { failed += a.zip to "kept: a different $differs is already there"; continue }
            when (mode) {
                Mode.DELETE_EACH -> if (a.zip.delete()) deleted++
                Mode.DELETE_AFTER -> whole += a.zip
                Mode.KEEP -> {}
            }
        }
        return finish(false, null)
    }

    private class StopException : Exception()

    fun size(bytes: Long) = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / 1073741824.0)
        bytes >= 1L shl 20 -> "${bytes shr 20} MB"
        else -> "${(bytes shr 10).coerceAtLeast(1)} KB"
    }
}
