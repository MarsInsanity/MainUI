package io.github.marsinsanity.mainui.data

import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/** Read access shared by gamelist.xml and Logiqx DAT files. */
interface MetaLookup {
    /** Metadata for a rom file (name, desc, image, hidden...), as gamelist.xml style fields. */
    fun get(romFile: File): GameListXml.Entry?
    /** Resolves an image path written in the metadata file. */
    fun resolve(value: String?): File?
    /** Changes whenever the underlying file(s) change; used for cache invalidation. */
    fun stamp(): Long
}

/** gamelist.xml first, then the DAT (Settings > Library > Metadata: "Both"). */
class CombinedLookup(private val first: MetaLookup, private val second: MetaLookup) : MetaLookup {
    override fun get(romFile: File): GameListXml.Entry? {
        val a = first.get(romFile) ?: return second.get(romFile)
        val b = second.get(romFile) ?: return a
        // Fill gaps in the gamelist entry from the DAT
        for ((k, v) in b.fields) if (!a.fields.containsKey(k)) a.fields[k] = v
        return a
    }
    override fun resolve(value: String?) = first.resolve(value) ?: second.resolve(value)
    override fun stamp() = first.stamp() * 31 + second.stamp()
}

/**
 * Logiqx XML ROM management datafile (the "datafile.dtd" format used by
 * No-Intro / Redump / MAME DATs and by Skraper's generic profile).
 *
 * ```
 * <datafile>
 *   <header><name>Nintendo - Game Boy</name>...</header>
 *   <game name="Tetris (World)">
 *     <description>Tetris (World)</description>
 *     <year>1989</year><manufacturer>Nintendo</manufacturer>
 *     <rom name="Tetris (World).gb" size="32768" crc="..." md5="..." sha1="..."/>
 *   </game>
 * </datafile>
 * ```
 *
 * Games are matched to files by `<rom name>` (disc / multi-file sets match any
 * of their files) or, for arcade style zip sets, by the game name. Elements
 * outside the DTD that some tools add (image, thumbnail, desc, genre, players,
 * rating...) are read too and exposed with their gamelist.xml names.
 */
class LogiqxDat(val file: File) : MetaLookup {

    /** A <rom> or <disk> ([tag]); [attrs] keeps every attribute as written (status, merge, serial...). */
    class Rom(val name: String, val size: Long?, val crc: String?, val md5: String?, val sha1: String?,
              val tag: String = "rom", val attrs: LinkedHashMap<String, String>? = null)

    class Game(
        var name: String,
        val isMachine: Boolean = false,
        /** Its element as written: game, machine (MAME) or software (software lists). */
        val tag: String = if (isMachine) "machine" else "game",
        val fields: LinkedHashMap<String, String> = LinkedHashMap(),
        val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
        val roms: MutableList<Rom> = mutableListOf(),
        /** Children kept as written (attributes, nested tags, repeats such as several <release>). */
        val raw: MutableList<String> = mutableListOf(),
    )

    val header = LinkedHashMap<String, String>()
    /** Header children with attributes / nested tags (<clrmamepro forcenodump=.../>), as written. */
    private val headerRaw = mutableListOf<String>()
    /** Top-level elements other than the header and games, as written. */
    private val others = mutableListOf<String>()
    /** Why the file couldn't be read completely (null = it was): then it is never overwritten. */
    var parseError: String? = null
        private set
    private val games = ArrayList<Game>()
    private val byFile = HashMap<String, Game>()
    private val byName = HashMap<String, Game>()
    private val byCrc = HashMap<String, Game>()
    private var loadedMtime = -1L

    val dir: File get() = file.parentFile ?: file

    /** Loaded with everything kept for writing back ([load] keepRaw): only such a DAT may be saved. */
    private var keptRaw = false
    /** The root element as read (written back the same), and the file's DOCTYPE as written. */
    private var rootTag = "datafile"
    private val rootAttrs = LinkedHashMap<String, String>()
    private var doctype: String? = null

    /**
     * Reads the DAT. [keepRaw]: also keep what MainUI doesn't use (every rom attribute, other
     * elements as written) so [save] can write it back; off for DATs that are only read for
     * names (an arcade DAT has 100 000+ roms: that would cost tens of MB).
     */
    fun load(keepRaw: Boolean = false, streamAbove: Long = STREAM_ABOVE): LogiqxDat {
        keptRaw = keepRaw
        reset()
        loadedMtime = file.lastModified()
        if (!file.isFile) return this
        // A big DAT only read for names (a full arcade set: 20-60 MB) is parsed straight from the
        // file: read whole, as text and its sanitized copy, it took several times its size at once
        // and never loaded on a 2 GB device (the names silently missing).
        if (!keepRaw && file.length() > streamAbove) {
            try {
                openUtf8(file).use { parse(it, keepRaw = false) }
                return this
            } catch (e: Exception) {
                if (e is java.nio.charset.CharacterCodingException || e.cause is java.nio.charset.CharacterCodingException) {
                    reset() // Not UTF-8: read whole below, in its own encoding.
                } else {
                    parseError = e.message ?: e.javaClass.simpleName
                    runCatching { android.util.Log.w("MainUI.Logiqx", "Failed to parse ${file.path}: ${e.message}") }
                    return this
                }
            } catch (e: OutOfMemoryError) {
                reset(); parseError = "too big to read"
                runCatching { android.util.Log.w("MainUI.Logiqx", "${file.path} is too big to read") }
                return this
            }
        }
        // (A UTF-8 byte order mark is not text: the parser would take it as junk before the declaration.)
        var lossy = false
        val raw = runCatching { GameListXml.readXmlText(file) { lossy = true } }.getOrElse { parseError = "unreadable: ${it.message}"; return this }
        if (GameListXml.isEmptyXml(raw)) return this
        doctype = doctypeOf(raw)
        try {
            // DOCTYPE declarations are fine for the pull parser; external DTDs are never fetched.
            parse(StringReader(GameListXml.sanitize(raw)), keepRaw)
            if (lossy) parseError = "not valid UTF-8 (read, but not rewritten)"
        } catch (e: Exception) {
            parseError = e.message ?: e.javaClass.simpleName
            runCatching { android.util.Log.w("MainUI.Logiqx", "Failed to parse ${file.path}: ${e.message} (it won't be overwritten)") }
        } catch (e: OutOfMemoryError) {
            // A full arcade set's DAT: unreadable, and remembered as such (not read again for every game).
            reset()
            parseError = "too big to read"
            runCatching { android.util.Log.w("MainUI.Logiqx", "${file.path} is too big to read") }
        }
        return this
    }

    private fun reset() {
        rootTag = "datafile"; rootAttrs.clear(); doctype = null
        games.clear(); header.clear(); byFile.clear(); byName.clear(); byCrc.clear()
        headerRaw.clear(); others.clear(); parseError = null
    }

    /** The file as UTF-8 (strict: anything else throws, for the whole-file path), sanitized as it is read. */
    private fun openUtf8(f: File): java.io.Reader {
        val ins = java.io.BufferedInputStream(f.inputStream(), 1 shl 16)
        ins.mark(4)
        val b = ByteArray(3)
        val n = ins.read(b)
        if (!(n == 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte())) ins.reset()
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return GameListXml.Companion.SanitizingReader(java.io.InputStreamReader(ins, dec))
    }

    /**
     * Reads the DAT from [input] (already sanitized). Read only (not [keepRaw]): only what the lists
     * use is kept (the fields [READ_FIELDS], the file names of its roms; no hashes, sizes or other
     * elements): an arcade DAT has 100 000+ roms.
     */
    private fun parse(input: java.io.Reader, keepRaw: Boolean) {
        val p = GameListXml.newParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(input)
        run {
            var game: Game? = null
            val romNames = ArrayList<String>()
            var inHeader = false
            var depth = 0
            var sawRoot = false
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val tag = p.name
                        if (depth == 1) {
                            sawRoot = true
                            // Written back as it was (<datafile>, <mame build=...>, <softwarelist name=...>).
                            rootTag = tag
                            rootAttrs.clear()
                            for (i in 0 until p.attributeCount) rootAttrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                        }
                        when {
                            depth == 2 && tag == "header" -> inHeader = true
                            depth == 2 && (tag == "game" || tag == "machine" || tag == "software") -> {
                                game = Game(p.getAttributeValue(null, "name") ?: "", isMachine = tag == "machine", tag = tag)
                                if (keepRaw) for (i in 0 until p.attributeCount) game.attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                                romNames.clear()
                            }
                            depth == 2 && tag != "header" -> { if (keepRaw) others += GameListXml.readElement(p).raw else skip(p); depth-- }
                            depth == 3 && inHeader -> {
                                val el = GameListXml.readElement(p); depth--
                                if (!keepRaw) header[tag] = el.text
                                else if (el.simple && tag !in header) header[tag] = el.text else headerRaw += el.raw
                            }
                            depth == 3 && game != null && (tag == "rom" || tag == "disk") && !keepRaw -> {
                                p.getAttributeValue(null, "name")?.let { romNames += it }
                            }
                            depth == 3 && game != null && (tag == "rom" || tag == "disk") -> {
                                val attrs = LinkedHashMap<String, String>().also { a ->
                                    for (i in 0 until p.attributeCount) a[p.getAttributeName(i)] = p.getAttributeValue(i)
                                }
                                game.roms += Rom(
                                    p.getAttributeValue(null, "name") ?: "",
                                    p.getAttributeValue(null, "size")?.toLongOrNull(),
                                    p.getAttributeValue(null, "crc")?.lowercase(),
                                    p.getAttributeValue(null, "md5")?.lowercase(),
                                    p.getAttributeValue(null, "sha1")?.lowercase(),
                                    tag, attrs,
                                )
                            }
                            depth == 3 && game != null && !keepRaw -> {
                                // Read only: the text of what the lists use (an attributed <description>
                                // still names the game); the rest (MAME's inputs, dipswitches...) skipped.
                                if (tag in READ_FIELDS) game.fields[tag] = GameListXml.readElement(p).text else skip(p)
                                depth--
                            }
                            depth == 3 && game != null -> {
                                val el = GameListXml.readElement(p); depth--
                                if (el.simple && tag !in game.fields) game.fields[tag] = el.text else game.raw += el.raw
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2) {
                            if (inHeader) inHeader = false
                            game?.let { if (keepRaw) add(it) else add(it, romNames) }
                            game = null
                        }
                        depth--
                    }
                }
                ev = p.next()
            }
            // Text that isn't XML at all (a clrmamepro DAT, some other file): not ours to rewrite.
            if (!sawRoot) throw org.xmlpull.v1.XmlPullParserException("no XML elements in it")
            // Cut short (a copy that didn't finish): the parser just stops, with elements still open.
            if (depth != 0) throw org.xmlpull.v1.XmlPullParserException("unexpected end of file")
        }
    }

    /** Past the element just started (its END_TAG consumed), keeping nothing of it. */
    private fun skip(p: XmlPullParser) {
        var d = 1
        while (d > 0) when (p.next()) {
            XmlPullParser.START_TAG -> d++
            XmlPullParser.END_TAG -> d--
            XmlPullParser.END_DOCUMENT -> throw org.xmlpull.v1.XmlPullParserException("unexpected end of file")
        }
    }

    private fun fileKey(name: String) = name.substringAfterLast('/').substringAfterLast('\\').lowercase()

    private fun add(g: Game) {
        games += g
        byName[g.name.lowercase()] = g
        for (r in g.roms) {
            byFile.putIfAbsent(fileKey(r.name), g)
            r.crc?.let { byCrc.putIfAbsent(it, g) }
        }
    }

    /** Read only: indexed by its roms' file names, which aren't kept. */
    private fun add(g: Game, romNames: List<String>) {
        games += g
        byName[g.name.lowercase()] = g
        for (n in romNames) byFile.putIfAbsent(fileKey(n), g)
    }

    fun all(): List<Game> = games

    fun find(romFile: File): Game? =
        byFile[romFile.name.lowercase()]
            ?: byName[romFile.nameWithoutExtension.lowercase()]
            ?: byName[romFile.name.lowercase()]

    /** Lookup by CRC32 (lowercase hex) for files whose name differs from the DAT. */
    fun findByCrc(crc: String): Game? = byCrc[crc.lowercase()]

    override fun get(romFile: File): GameListXml.Entry? {
        val g = find(romFile) ?: return null
        return toEntry(g, romFile)
    }

    private fun toEntry(g: Game, romFile: File): GameListXml.Entry {
        val e = GameListXml.Entry("./" + romFile.name)
        val f = g.fields
        // <description> is the display title in Logiqx DATs
        (f["description"]?.takeIf { it.isNotBlank() } ?: g.name.takeIf { it.isNotBlank() })?.let { e.fields["name"] = it }
        (f["desc"] ?: f["synopsis"] ?: f["comment"])?.let { e.fields["desc"] = it }
        f["year"]?.let { y -> e.fields["releasedate"] = y.filter { it.isDigit() }.take(8).padEnd(8, '0').let { d ->
            if (d.length >= 4) d.substring(0, 4) + (d.substring(4, 6).takeIf { it != "00" } ?: "01") + (d.substring(6, 8).takeIf { it != "00" } ?: "01") + "T000000" else y } }
        (f["manufacturer"] ?: f["publisher"])?.let { e.fields["publisher"] = it }
        f["developer"]?.let { e.fields["developer"] = it }
        for (k in listOf("image", "thumbnail", "marquee", "video", "genre", "players", "rating", "hidden", "favorite")) {
            f[k]?.let { e.fields[k] = it }
        }
        // Skraper style alternative names for artwork
        if (!e.fields.containsKey("image")) (f["boxart"] ?: f["cover"] ?: f["screenshot"])?.let { e.fields["image"] = it }
        return e
    }

    override fun resolve(value: String?): File? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        return when {
            v.startsWith("/") -> File(v)
            else -> File(dir, v.removePrefix("./"))
        }
    }

    override fun stamp(): Long = if (file.isFile) file.lastModified() else 0L

    // ---- writing (used by the scraper when Logiqx is the chosen format) ----------

    fun put(romFile: File, name: String?, desc: String?, year: String?, manufacturer: String?, hashes: io.github.marsinsanity.mainui.scraper.Hashes?) {
        val g = find(romFile) ?: Game(romFile.nameWithoutExtension).also {
            it.roms += Rom(romFile.name, romFile.length().takeIf { romFile.isFile }, hashes?.crc, hashes?.md5, hashes?.sha1)
            add(it)
        }
        name?.let { g.fields["description"] = it }
        year?.let { g.fields["year"] = it.take(4) }
        manufacturer?.let { g.fields["manufacturer"] = it }
        desc?.let { g.fields["comment"] = it.replace('\n', ' ').take(2000) }
    }

    /**
     * Writes the DAT back. Throws (and writes nothing) when the file on disk couldn't be read
     * completely (not XML, cut short...): rewriting it would lose whatever wasn't read.
     */
    fun save() {
        parseError?.let { throw java.io.IOException("${file.path} could not be read ($it): not overwriting it") }
        if (!keptRaw) throw IllegalStateException("${file.name} was loaded read-only (load(keepRaw = true) to write it)")
        val esc = GameListXml::escape
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>\n")
        // The file's own DOCTYPE (MAME's inline DTD...), else Logiqx's for a Logiqx DAT.
        val dt = doctype
        if (dt != null) sb.append(dt).append('\n')
        else if (rootTag == "datafile") sb.append("<!DOCTYPE datafile PUBLIC \"-//Logiqx//DTD ROM Management Datafile//EN\" \"http://www.logiqx.com/Dats/datafile.dtd\">\n")
        sb.append('<').append(rootTag)
        for ((k, v) in rootAttrs) sb.append(' ').append(k).append("=\"").append(esc(v)).append('"')
        sb.append(">\n\t<header>\n")
        if (header.isEmpty()) {
            header["name"] = dir.name
            header["description"] = dir.name
            header["author"] = "MainUI"
        }
        for ((k, v) in header) sb.append("\t\t<").append(k).append('>').append(esc(v)).append("</").append(k).append(">\n")
        for (r in headerRaw) sb.append("\t\t").append(r).append('\n')
        sb.append("\t</header>\n")
        for (o in others) sb.append('\t').append(o).append('\n')
        // Logiqx element order: comment, description, year, manufacturer, ..., rom
        val order = listOf("comment", "description", "year", "manufacturer")
        for (g in games) {
            val tag = g.tag
            sb.append("\t<").append(tag)
            val attrs = LinkedHashMap(g.attrs).apply { this["name"] = g.name }
            for ((k, v) in attrs) sb.append(' ').append(k).append("=\"").append(esc(v)).append('"')
            sb.append(">\n")
            val keys = order.filter { g.fields.containsKey(it) } + g.fields.keys.filter { it !in order }
            for (k in keys) sb.append("\t\t<").append(k).append('>').append(esc(g.fields.getValue(k))).append("</").append(k).append(">\n")
            for (r in g.raw) sb.append("\t\t").append(r).append('\n')
            for (r in g.roms) {
                sb.append("\t\t<").append(r.tag)
                val attrs = r.attrs ?: LinkedHashMap<String, String>().apply {
                    put("name", r.name)
                    r.size?.let { put("size", it.toString()) }
                    r.crc?.let { put("crc", it) }
                    r.md5?.let { put("md5", it) }
                    r.sha1?.let { put("sha1", it) }
                }
                for ((k, v) in attrs) sb.append(' ').append(k).append("=\"").append(esc(v)).append('"')
                sb.append("/>\n")
            }
            sb.append("\t</").append(tag).append(">\n")
        }
        sb.append("</").append(rootTag).append(">\n")
        val tmp = File(dir, "${file.name}.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        tmp.writeText(sb.toString())
        if (file.exists()) {
            val bak = File(dir, file.name + ".bak")
            if (!bak.exists()) file.copyTo(bak, overwrite = false)
        }
        if (!tmp.renameTo(file)) { file.writeText(sb.toString()); tmp.delete() }
        loadedMtime = file.lastModified()
    }

    companion object {
        /** Bigger read-only DATs are parsed from the file as it is read (see [load]). */
        const val STREAM_ABOVE = 4L shl 20

        /** What a read-only DAT keeps of a game's children: what [toEntry] uses. */
        private val READ_FIELDS = setOf("description", "desc", "synopsis", "comment", "year", "manufacturer", "publisher",
            "developer", "image", "thumbnail", "marquee", "video", "genre", "players", "rating", "hidden", "favorite",
            "boxart", "cover", "screenshot")

        /** The `<!DOCTYPE ...>` at the start of [raw] (with its inline DTD, if any), or null. */
        fun doctypeOf(raw: String): String? {
            val start = raw.indexOf("<!DOCTYPE")
            if (start < 0) return null
            // Only before the first element: not one quoted in some text further on.
            val firstEl = Regex("<[A-Za-z_]").find(raw)?.range?.first ?: raw.length
            if (start > firstEl) return null
            var depth = 0
            for (i in start until raw.length) when (raw[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return raw.substring(start, i + 1)
            }
            return null
        }

        /**
         * Finds the DAT for a system folder: <folder>/<folder>.dat, any *.dat, or an
         * *.xml whose root element is <datafile> (Skraper's generic profile writes XML).
         */
        fun locate(dir: File): File? {
            // Listing the folder and peeking into its .xml files is slow on big folders: remembered
            // until the folder changes (adding / removing / renaming a file changes its mtime).
            val key = dir.absolutePath
            val mtime = dir.lastModified()
            located[key]?.let { (m, f) -> if (m == mtime && (f == null || f.isFile)) return f }
            return scan(dir).also { located[key] = mtime to it }
        }

        private val located = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, File?>>()

        private fun scan(dir: File): File? {
            val files = dir.listFiles() ?: return null
            // MainUI's own DAT, written when the folder's DAT couldn't be read (see forWriting).
            files.firstOrNull { it.isFile && it.name.equals("${dir.name}.mainui.dat", true) }?.let { return it }
            // Only XML DATs: a clrmamepro text DAT or a .dat rom image (Naomi) is no metadata of ours.
            fun head(f: File, n: Int) = runCatching { f.bufferedReader().use { r -> CharArray(n).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrDefault("")
            // (MAME's -listxml has a 7 KB inline DTD before its <mame> root.)
            fun isXmlDat(f: File) = head(f, 16384).let { it.contains("<datafile") || it.contains("<mame") || it.contains("<softwarelist") || GameListXml.isEmptyXml(it) && f.length() < 4096 }
            files.firstOrNull { it.isFile && it.name.equals("${dir.name}.dat", true) && isXmlDat(it) }?.let { return it }
            files.filter { it.isFile && it.extension.equals("dat", true) && !it.name.equals("${dir.name}.dat", true) && it.length() < 512L * 1024 * 1024 }
                .sortedBy { it.name.length }.firstOrNull { isXmlDat(it) }?.let { return it }
            return files.filter { it.isFile && it.extension.equals("xml", true) && !it.name.equals("gamelist.xml", true) }
                .firstOrNull { f -> head(f, 1024).contains("<datafile") }
        }

        /**
         * The DAT the scraper writes into: the folder's DAT when it reads cleanly as Logiqx XML,
         * else (a clrmamepro text DAT, a broken file) <folder>.dat when there is none yet, or
         * <folder>.mainui.dat next to it, so the user's own DAT is never overwritten.
         */
        fun forWriting(dir: File): LogiqxDat {
            locate(dir)?.let { f -> LogiqxDat(f).load(keepRaw = true).let { if (it.parseError == null) return it } }
            val own = File(dir, "${dir.name}.dat").takeUnless { it.exists() } ?: File(dir, "${dir.name}.mainui.dat")
            return LogiqxDat(own).load(keepRaw = true).also { located.remove(dir.absolutePath) }
        }
    }
}
