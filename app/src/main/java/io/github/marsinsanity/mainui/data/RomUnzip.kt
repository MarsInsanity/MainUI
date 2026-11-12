package io.github.marsinsanity.mainui.data

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipFile

/**
 * Unpacks the .zip and .rar files in a system's folders for systems whose emulators can't open
 * archives (Game & Watch's .mgw, disc images...). Only archives holding files the system plays
 * are touched; each one is unpacked next to itself (its own folders kept), never over an
 * existing file, and through a temporary ".part" file, so a stopped or failed unpack leaves
 * nothing half written. RAR (RAR4 and RAR5, solid and multi-part) is read with junrar.
 */
object RomUnzip {
    /** Kept free on top of what an unpack needs, so the storage never fills up completely. */
    const val MARGIN = 64L shl 20

    /** Archive types MainUI can unpack. */
    val TYPES = setOf("zip", "rar")

    /** Files in a game archive that aren't worth unpacking. */
    private val JUNK = setOf("txt", "nfo", "diz", "url", "htm", "html", "pdf", "db", "ini", "md")

    /** "Game.part2.rar": a later part of a multi-part RAR, read through its first part. */
    private val LATER_PART = Regex("""\.part0*([2-9]|[1-9]\d+)\.rar$""", RegexOption.IGNORE_CASE)
    private val PART = Regex("""^(.*)\.part\d+\.rar$""", RegexOption.IGNORE_CASE)
    private val OLD_PART = Regex("""r\d\d""", RegexOption.IGNORE_CASE)

    /** What happens to the archives. */
    enum class Mode {
        /** All unpacked first, then every archive that came out whole deleted (none if it stopped part way). */
        DELETE_AFTER,
        /** Each archive deleted as soon as it is unpacked: room for only the biggest one is needed. */
        DELETE_EACH,
        /** The archives stay. */
        KEEP,
    }

    /**
     * An archive with games in it: the entries to unpack and their unpacked size. [parts]: all of
     * its files (a multi-part RAR has several), deleted together.
     */
    class Archive(val file: File, val entries: List<String>, val bytes: Long, val games: Int, val parts: List<File> = listOf(file)) {
        val isRar get() = file.extension.equals("rar", true)
    }

    class Plan(val archives: List<Archive>) {
        val bytes get() = archives.sumOf { it.bytes }
        val largest get() = archives.maxOfOrNull { it.bytes } ?: 0L
        val games get() = archives.sumOf { it.games }
    }

    class Result(val unpacked: Int, val existing: Int, val done: Int, val deleted: Int,
                 val failed: List<Pair<File, String>>, val stopped: Boolean, val problem: String?)

    /**
     * What to unpack from an archive with these entries (name to size): its files when at least
     * one is a game the system plays ([accepts]), leaving out folders, Mac / hidden files and
     * readmes. Empty when it holds no game. The game count comes along.
     */
    fun pick(entries: List<Pair<String, Long>>, accepts: (String) -> Boolean): Pair<List<Pair<String, Long>>, Int> {
        val files = entries.map { (n, s) -> n.replace('\\', '/') to s }.filter { (n, _) -> keeps(n) }
        val games = files.count { accepts(it.first.substringAfterLast('/')) }
        return if (games == 0) emptyList<Pair<String, Long>>() to 0 else files to games
    }

    /**
     * A RAR with a dictionary limit the app's memory can hold: a bigger one is refused (an
     * exception, reported) instead of an OutOfMemoryError on the unpacking thread.
     */
    private fun openRar(f: File) = com.github.junrar.Archive(f,
        com.github.junrar.ArchiveOptions.builder().maxDictionarySize(Runtime.getRuntime().maxMemory() / 2).build())

    /**
     * Entry names (with "/" separators, folders ending in "/") and unpacked sizes of [f]: a .zip or
     * .rar. For a multi-part RAR only the files starting in the first part (the rest are only seen
     * while unpacking; see [unpackRar]).
     */
    fun list(f: File): List<Pair<String, Long>> =
        if (f.extension.equals("rar", true)) openRar(f).use { a ->
            if (a.isEncrypted) throw IOException("it is password protected")
            a.fileHeaders.filter { !it.isSplitBefore }
                .map { h -> (h.fileName.replace('\\', '/') + if (h.isDirectory) "/" else "") to h.fullUnpackSize.coerceAtLeast(0) }
        } else openZip(f).use { z -> z.entries().asSequence().map { it.name to it.size.coerceAtLeast(0) }.toList() }

    /** Windows' and 7-Zip's zips keep non-ASCII names in the DOS code page, without the UTF-8 flag. */
    private fun openZip(f: File): ZipFile {
        try {
            val z = ZipFile(f)
            try { z.entries().asSequence().forEach { it.name }; return z } catch (e: IllegalArgumentException) { z.close() }
        } catch (e: java.util.zip.ZipException) {
        }
        return ZipFile(f, runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1))
    }

    /** Whether an archive file [name] is unpacked at all (folders, Mac / hidden files and readmes aren't). */
    private fun keeps(name: String) = !name.endsWith("/") && !name.startsWith("__MACOSX/") &&
        !name.substringAfterLast('/').startsWith(".") && name.substringAfterLast('.', "").lowercase() !in JUNK

    /** The files of the archive [f]: all parts of a multi-part RAR ("X.part1.rar", "X.part2.rar"... or "X.rar", "X.r00"...). */
    fun partsOf(f: File): List<File> {
        if (!f.extension.equals("rar", true)) return listOf(f)
        val siblings = f.parentFile?.listFiles().orEmpty()
        PART.find(f.name)?.let { m ->
            val base = m.groupValues[1]
            return listOf(f) + siblings.filter { it != f && PART.find(it.name)?.groupValues?.get(1)?.equals(base, true) == true }
                .sortedBy { it.name }
        }
        val base = f.nameWithoutExtension
        return listOf(f) + siblings.filter { it.nameWithoutExtension.equals(base, true) && OLD_PART.matches(it.extension) }
            .sortedBy { it.name }
    }

    /**
     * The archives under [dirs] (a few folders deep, skipping folders [skipDir] names: artwork,
     * BIOS...) of [types] that hold games, with what to unpack from each. [problems] gets the
     * ones that can't be read (password protected, damaged).
     */
    fun scan(dirs: List<File>, accepts: (String) -> Boolean, skipDir: (String) -> Boolean = { false },
             types: Set<String> = TYPES, problems: MutableList<Pair<File, String>>? = null): Plan {
        val out = ArrayList<Archive>()
        fun walk(d: File, depth: Int) {
            for (f in d.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) { if (depth < 3 && !skipDir(f.name.lowercase())) walk(f, depth + 1); continue }
                if (f.extension.lowercase() !in types || LATER_PART.containsMatchIn(f.name)) continue
                try {
                    val parts = partsOf(f)
                    // A multi-part set: the files starting in each part, read part by part (when a
                    // part can't be read on its own, its first part's files and an estimate).
                    val whole = if (parts.size > 1) runCatching { parts.flatMap { list(it) } }.getOrNull() else null
                    val (files, games) = pick(whole ?: list(f), accepts)
                    if (files.isNotEmpty()) {
                        // Only what still has to be written counts (a run stopped part way and started
                        // again); unknown sizes as the archive's own size, never less than that unpacked.
                        val todo = files.filter { (n, size) -> target(d, n)?.let { it.exists() && it.length() == size } != true }
                        val packed = parts.sumOf { it.length() }
                        val bytes = when {
                            todo.isEmpty() && (parts.size == 1 || whole != null) -> 0L
                            parts.size > 1 && whole == null -> maxOf(todo.sumOf { it.second }, packed + packed / 2)
                            else -> maxOf(todo.sumOf { it.second }, packed)
                        }
                        out += Archive(f, files.map { it.first }, bytes, games, parts)
                    }
                } catch (e: Exception) {
                    problems?.add(f to (e.message ?: e.javaClass.simpleName))
                }
            }
        }
        dirs.forEach { walk(it, 0) }
        return Plan(out)
    }

    /**
     * Free space an unpack needs on the storage holding [archives], plus [MARGIN]: everything for
     * [Mode.KEEP] and [Mode.DELETE_AFTER] (the archives are all still there until the end), only
     * the biggest one's contents for [Mode.DELETE_EACH] (each archive goes once it's unpacked).
     */
    fun spaceNeeded(archives: List<Archive>, mode: Mode): Long =
        (if (mode == Mode.DELETE_EACH) archives.maxOfOrNull { it.bytes } ?: 0L else archives.sumOf { it.bytes }) + MARGIN

    /** Where [entry] of an archive in [dir] goes, or null for a name pointing outside [dir] ("../x"). */
    fun target(dir: File, entry: String): File? {
        val rel = entry.replace('\\', '/').trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        val f = File(dir, rel)
        val base = dir.canonicalPath + File.separator
        return f.takeIf { runCatching { it.canonicalPath.startsWith(base) }.getOrDefault(false) }
    }

    /** Counts for one archive; [checkCrc]: existing files must match the archive's CRC to count as unpacked. */
    private class Tally(val checkCrc: Boolean) { var written = 0; var already = 0; var differs: String? = null }

    private fun crcOf(f: File): Long = java.util.zip.CRC32().also { c ->
        f.inputStream().use { input -> val buf = ByteArray(1 shl 20); while (true) { val n = input.read(buf); if (n < 0) break; c.update(buf, 0, n) } }
    }.value

    /**
     * Writes one entry to [dest] through a ".part" file. An existing [dest] is left alone: counted
     * as already unpacked when it has the entry's [size] (and, when the archive is to be deleted,
     * its [crc]), else noted as a different file, which keeps the archive. Returns whether it was written.
     */
    private fun write(dest: File, size: Long, crc: Long?, t: Tally, contentCrc: (() -> Long)? = null, copy: (OutputStream) -> Unit): Boolean {
        if (dest.exists()) {
            // No CRC in the header (a file split across RAR parts): the archive's content is read for one.
            val same = size >= 0 && dest.length() == size &&
                (!t.checkCrc || (crc ?: contentCrc?.invoke())?.let { crcOf(dest) == it } ?: true)
            if (same) t.already++ else t.differs = dest.name
            return false
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, ".${dest.name}.part")
        try {
            part.outputStream().buffered(1 shl 20).use(copy)
            if (!part.renameTo(dest)) throw IOException("could not rename ${part.name}")
            t.written++
            return true
        } finally {
            part.delete()
        }
    }

    private object Discard : OutputStream() {
        override fun write(b: Int) {}
        override fun write(b: ByteArray, off: Int, len: Int) {}
    }

    /** Nothing kept, only the CRC32 of what went through. */
    private class CrcOut : OutputStream() {
        val crc = java.util.zip.CRC32()
        override fun write(b: Int) = crc.update(b)
        override fun write(b: ByteArray, off: Int, len: Int) = crc.update(b, off, len)
    }

    private fun unpackZip(a: Archive, dir: File, t: Tally, cancelled: () -> Boolean) {
        openZip(a.file).use { z ->
            // Names as listed ("\\" made "/"): some Windows tools store "Game\\Game.iso".
            val byName = z.entries().asSequence().associateBy { it.name.replace('\\', '/') }
            for (name in a.entries) {
                if (cancelled()) throw StopException()
                // Never "done" (and deleted) with a file left in it.
                val e = byName[name] ?: throw IOException("can't find $name in it")
                val dest = target(dir, name) ?: continue // outside the folder: never written
                write(dest, e.size, e.crc.takeIf { it >= 0 }, t) { out -> z.getInputStream(e).use { it.copyTo(out, 1 shl 20) } }
            }
        }
    }

    /**
     * RAR entries are read in archive order, across all parts of a multi-part set: unpacking a
     * file that goes on in the next part moves to that part, whose files follow (so in a set every
     * file worth keeping is unpacked, not only the first part's listed ones). In a solid archive
     * each file needs the ones before it, so files not written (readmes, files already there) are
     * still unpacked, into nothing, as are split ones (to reach the next part).
     */
    private fun unpackRar(a: Archive, dir: File, t: Tally, cancelled: () -> Boolean) {
        val wanted = a.entries.toHashSet()
        val multi = a.parts.size > 1
        openRar(a.file).use { ar ->
            if (ar.isEncrypted) throw IOException("it is password protected")
            val solid = ar.mainHeader?.isSolid == true
            while (true) {
                if (cancelled()) throw StopException()
                val h = ar.nextFileHeader() ?: break
                if (h.isDirectory || h.isSplitBefore) continue // (the rest of a file from the part before)
                val name = h.fileName.replace('\\', '/')
                val want = if (multi) keeps(name) else name in wanted
                val dest = if (want) target(dir, name) else null // outside the folder: never written
                val crc = if (h.hasFileCrc() && !h.isSplitAfter) h.fileCRC.toLong() and 0xFFFFFFFFL else null
                // Each file is read out of the archive once at most (in a solid one, reading it
                // twice would be wrong): to write it, to check it against one already there, or into nothing.
                var extracted = false
                val contentCrc = { extracted = true; CrcOut().also { ar.extractFile(h, it) }.crc.value }
                val written = dest != null && write(dest, h.fullUnpackSize, crc, t, contentCrc) { out -> extracted = true; ar.extractFile(h, out) }
                if (!written && !extracted && (solid || h.isSplitAfter)) ar.extractFile(h, Discard)
            }
        }
    }

    /**
     * Unpacks [plan], archive by archive; what happens to the archives depends on [mode] (one is
     * only ever deleted, with all its parts, when all of it unpacked). Before each archive its
     * storage must have room for it (plus [MARGIN]), else it stops there and says so. [fallback]
     * can unpack a .zip another way (root) when writing its folder fails; it gets the zip and its
     * folder and says whether that worked. [progress] gets (archive number, count, name).
     */
    fun unpack(plan: Plan, mode: Mode, progress: (Int, Int, String) -> Unit, cancelled: () -> Boolean,
               fallback: ((File, File) -> Boolean)? = null): Result {
        var unpacked = 0; var existing = 0; var done = 0; var deleted = 0
        val failed = ArrayList<Pair<File, String>>()
        val whole = ArrayList<Archive>() // came out whole: deleted at the end for DELETE_AFTER
        fun delete(a: Archive) { if (a.parts.map { it.delete() }.first()) deleted++ }
        // DELETE_AFTER deletes nothing unless the whole run got through.
        fun finish(stopped: Boolean, problem: String?): Result {
            if (mode == Mode.DELETE_AFTER && !stopped && problem == null) whole.forEach(::delete)
            return Result(unpacked, existing, done, deleted, failed, stopped, problem)
        }
        for ((i, a) in plan.archives.withIndex()) {
            if (cancelled()) return finish(true, null)
            progress(i + 1, plan.archives.size, a.file.name)
            val dir = a.file.parentFile ?: continue
            if (dir.usableSpace < a.bytes + MARGIN) {
                return finish(false, "Not enough space for ${a.file.name}: it needs ${size(a.bytes + MARGIN)}, ${size(dir.usableSpace)} is free")
            }
            val t = Tally(checkCrc = mode != Mode.KEEP) // an archive to be deleted mustn't count a lookalike as its file
            var ok = true
            try {
                if (a.isRar) unpackRar(a, dir, t, cancelled) else unpackZip(a, dir, t, cancelled)
            } catch (e: StopException) {
                unpacked += t.written; existing += t.already
                return finish(true, null)
            } catch (e: OutOfMemoryError) {
                ok = false
                failed += a.file to "needs too much memory to unpack"
            } catch (e: Exception) {
                // The root fallback only for a folder MainUI can't write to (not a damaged archive).
                ok = !a.isRar && e is java.io.FileNotFoundException && fallback?.invoke(a.file, dir) == true
                if (!ok) failed += a.file to (e.message ?: e.javaClass.simpleName)
            }
            unpacked += t.written; existing += t.already
            if (!ok) continue
            done++
            val differs = t.differs
            if (differs != null) { failed += a.file to "kept: a different $differs is already there"; continue }
            when (mode) {
                Mode.DELETE_EACH -> delete(a)
                Mode.DELETE_AFTER -> whole += a
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
