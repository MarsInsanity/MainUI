package io.github.marsinsanity.mainui.data

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader

/**
 * EmulationStation / dArkOS / Skraper compatible gamelist.xml reader and writer.
 *
 * Real world gamelist files are often not well formed (bare '&', stray control
 * characters), so input is sanitised before parsing and unknown tags are kept
 * verbatim so rewriting never loses data written by other tools: other top-level
 * elements (Skraper's <provider>, ES-DE's <alternativeEmulator>) and game children
 * with attributes, nested tags or repeats (Batocera's <scrap name=".." date=".."/>).
 * A file that could not be parsed completely is never overwritten ([save] throws).
 */
class GameListXml(val dir: File) : MetaLookup {

    class Entry(
        var path: String,
        val isFolder: Boolean = false,
        val fields: LinkedHashMap<String, String> = LinkedHashMap(),
        val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
        /** Children kept as written (attributes, nested tags, repeats): written back unchanged. */
        val raw: MutableList<String> = mutableListOf(),
    ) {
        val name get() = fields["name"]
        val image get() = fields["image"]
        val thumbnail get() = fields["thumbnail"]
        val marquee get() = fields["marquee"]
        val desc get() = fields["desc"]
        val hidden get() = fields["hidden"].equals("true", ignoreCase = true)
        val favorite get() = fields["favorite"].equals("true", ignoreCase = true)
    }

    val file = File(dir, "gamelist.xml")
    private val entries = LinkedHashMap<String, Entry>()
    /** Top-level elements other than games / folders, as written. */
    private val others = mutableListOf<String>()
    /** Elements beside <gameList> at the top (ES-DE's <alternativeEmulator>), written back before it. */
    private val prelude = mutableListOf<String>()
    var loaded = false
        private set
    /** Why the file couldn't be read completely (null = it was): then it is never overwritten. */
    var parseError: String? = null
        private set

    fun load(): GameListXml {
        entries.clear()
        others.clear()
        prelude.clear()
        parseError = null
        loaded = true
        if (!file.isFile) return this
        // (A UTF-8 byte order mark is not text: the parser would take it as junk before the declaration.)
        var lossy = false
        val raw = runCatching { readXmlText(file) { lossy = true } }.getOrElse { parseError = "unreadable: ${it.message}"; return this }
        // An empty file (or just the XML declaration) is an empty list, not a broken one.
        if (isEmptyXml(raw)) return this
        val parser = newParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            parser.setInput(StringReader(sanitize(raw)))
            var current: Entry? = null
            var depth = 0
            var sawRoot = false
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        if (depth == 1) sawRoot = true
                        val tag = parser.name
                        if (depth == 1 && !tag.equals("gameList", true)) {
                            prelude += readElement(parser).raw
                            depth-- // consumed its END_TAG
                        } else if (depth == 2 && (tag == "game" || tag == "folder")) {
                            val attrs = LinkedHashMap<String, String>()
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                            current = Entry("", tag == "folder", attrs = attrs)
                        } else if (depth == 2) {
                            others += readElement(parser).raw
                            depth-- // consumed its END_TAG
                        } else if (depth == 3 && current != null) {
                            val el = readElement(parser)
                            depth--
                            when {
                                tag == "path" -> current.path = el.text.trim()
                                el.simple && tag !in current.fields -> current.fields[tag] = el.text
                                else -> current.raw += el.raw
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2 && current != null) {
                            if (current.path.isNotEmpty()) entries[normalize(current.path)] = current
                            current = null
                        }
                        depth--
                    }
                }
                ev = parser.next()
            }
            // Text that isn't XML at all (a clrmamepro DAT, some other file): not ours to rewrite.
            if (!sawRoot) throw org.xmlpull.v1.XmlPullParserException("no XML elements in it")
            // Cut short (a copy that didn't finish): the parser just stops, with elements still open.
            if (depth != 0) throw org.xmlpull.v1.XmlPullParserException("unexpected end of file")
            if (lossy) parseError = "not valid UTF-8 (read, but not rewritten)"
        } catch (e: Exception) {
            parseError = e.message ?: e.javaClass.simpleName
            runCatching { android.util.Log.w("MainUI.GameList", "Failed to parse ${file.path}: ${e.message} (it won't be overwritten)") }
        }
        return this
    }

    override fun get(romFile: File): Entry? = entries[normalize(relPath(romFile))]

    override fun stamp(): Long = if (file.isFile) file.lastModified() else 0L

    fun all(): Collection<Entry> = entries.values

    /** Drops [romFile]'s entry (the rom was deleted). True when there was one. */
    fun remove(romFile: File): Boolean = entries.remove(normalize(relPath(romFile))) != null

    fun put(romFile: File, update: (Entry) -> Unit) {
        val key = normalize(relPath(romFile))
        val e = entries.getOrPut(key) { Entry("./" + relPath(romFile)) }
        update(e)
    }

    /** Resolves an image style field (./images/x.png, absolute, ~/...) to a file. */
    override fun resolve(value: String?): File? {
        if (value.isNullOrBlank()) return null
        val v = value.trim()
        val f = when {
            v.startsWith("/") -> File(v)
            v.startsWith("~/") -> File(dir, v.removePrefix("~/"))
            else -> File(dir, v.removePrefix("./"))
        }
        return f
    }

    fun relPath(romFile: File): String =
        runCatching { romFile.relativeTo(dir).path }.getOrDefault(romFile.name)

    /**
     * Writes the list back. Throws (and writes nothing) when the file on disk couldn't be read
     * completely: rewriting it from the part that was read would drop everything after the error.
     */
    fun save() {
        parseError?.let { throw java.io.IOException("${file.path} could not be read ($it): not overwriting it") }
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>\n")
        for (p in prelude) sb.append(p).append('\n')
        sb.append("<gameList>\n")
        for (o in others) sb.append('\t').append(o).append('\n')
        for (e in entries.values) {
            val tag = if (e.isFolder) "folder" else "game"
            sb.append("\t<").append(tag)
            for ((k, v) in e.attrs) sb.append(' ').append(k).append("=\"").append(escape(v)).append('"')
            sb.append(">\n")
            sb.append("\t\t<path>").append(escape(e.path)).append("</path>\n")
            for ((k, v) in e.fields) sb.append("\t\t<").append(k).append('>').append(escape(v)).append("</").append(k).append(">\n")
            for (r in e.raw) sb.append("\t\t").append(r).append('\n')
            sb.append("\t</").append(tag).append(">\n")
        }
        sb.append("</gameList>\n")
        // A temp file of its own: two saves at once (a single-game scrape during a run) never share one.
        val tmp = File(dir, "gamelist.xml.${Thread.currentThread().id}.${System.nanoTime()}.tmp")
        tmp.writeText(sb.toString())
        if (file.exists()) {
            val bak = File(dir, "gamelist.xml.bak")
            if (!bak.exists()) file.copyTo(bak, overwrite = false)
        }
        if (!tmp.renameTo(file)) {
            file.writeText(sb.toString()); tmp.delete()
        }
    }

    companion object {
        /** The XML parser (Android's; unit tests put in kxml2). */
        @Volatile var newParser: () -> XmlPullParser = { Xml.newPullParser() }

        /** One element: its text, whether it is plain (no attributes or child tags), and its XML as written. */
        class Element(val text: String, val simple: Boolean, val raw: String)

        /** Reads the element whose START_TAG was just read, up to and including its END_TAG. */
        fun readElement(p: XmlPullParser): Element {
            val text = StringBuilder()
            val raw = StringBuilder()
            var simple = p.attributeCount == 0
            fun startTag() {
                raw.append('<').append(p.name)
                for (i in 0 until p.attributeCount) raw.append(' ').append(p.getAttributeName(i)).append("=\"").append(escape(p.getAttributeValue(i))).append('"')
            }
            startTag()
            val names = arrayListOf(p.name)
            var open = true // right after a start tag: an element without content is written <x/>
            while (names.isNotEmpty()) {
                when (p.next()) {
                    XmlPullParser.START_TAG -> { if (open) raw.append('>'); simple = false; startTag(); names += p.name; open = true }
                    XmlPullParser.TEXT -> {
                        if (open) { raw.append('>'); open = false }
                        raw.append(escape(p.text))
                        if (names.size == 1) text.append(p.text)
                    }
                    XmlPullParser.END_TAG -> {
                        val n = names.removeAt(names.size - 1)
                        if (open) raw.append("/>") else raw.append("</").append(n).append('>')
                        open = false
                    }
                    XmlPullParser.END_DOCUMENT -> throw org.xmlpull.v1.XmlPullParserException("unexpected end of file in <${names.first()}>")
                }
            }
            return Element(text.toString(), simple, raw.toString())
        }

        fun normalize(p: String): String {
            var s = p.trim().replace('\\', '/')
            while (s.startsWith("./")) s = s.substring(2)
            return s.lowercase()
        }


        /** Nothing but whitespace and maybe the XML declaration: an empty file, not a broken one. */
        fun isEmptyXml(raw: String): Boolean {
            // Looks only at the start (no copy of a big file): the first thing that isn't blank.
            var i = 0
            while (i < raw.length && raw[i].isWhitespace()) i++
            if (i == raw.length) return true
            if (!raw.startsWith("<?xml", i)) return false
            val end = raw.indexOf("?>", i)
            if (end < 0) return false
            i = end + 2
            while (i < raw.length && raw[i].isWhitespace()) i++
            return i == raw.length
        }
        private val BARE_AMP = Regex("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)")
        private val BAD_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

        private val CDATA = Regex("<!\\[CDATA\\[.*?]]>", RegexOption.DOT_MATCHES_ALL)

        /** Bare "&" escaped and control characters dropped, outside CDATA sections (their text is literal). */
        fun sanitize(s: String): String {
            if (!s.contains("<![CDATA[")) return BAD_CHARS.replace(BARE_AMP.replace(s, "&amp;"), "")
            val sb = StringBuilder(s.length)
            var i = 0
            for (m in CDATA.findAll(s)) {
                sb.append(BARE_AMP.replace(s.substring(i, m.range.first), "&amp;"))
                sb.append(m.value)
                i = m.range.last + 1
            }
            sb.append(BARE_AMP.replace(s.substring(i), "&amp;"))
            return BAD_CHARS.replace(sb, "")
        }

        private val DECLARED = Regex("""^\s*<\?xml[^>]*encoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")

        /**
         * The file's text: UTF-8, else the encoding its declaration names, else Windows' "ANSI"
         * (Notepad), never UTF-8 with replacement characters, which a save would then make permanent.
         */
        fun readXmlText(f: File, lossy: () -> Unit = {}): String {
            var bytes = f.readBytes()
            // (A UTF-8 byte order mark is not text: the parser would take it as junk before the declaration.)
            val bom = bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
            if (bom) bytes = bytes.copyOfRange(3, bytes.size)
            val utf8 = runCatching {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            }.getOrNull()
            if (utf8 != null) return utf8
            val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
            val declared = DECLARED.find(head)?.groupValues?.get(1)?.let { n -> runCatching { java.nio.charset.Charset.forName(n) }.getOrNull() }
            // Mostly UTF-8 with a bad byte here and there (a stray Windows character): read as UTF-8,
            // the bad bytes replaced, and never written back ([lossy]), which would make that permanent.
            if (bom || declared == Charsets.UTF_8 || (declared == null && hasUtf8Sequences(bytes))) {
                lossy()
                return String(bytes, Charsets.UTF_8)
            }
            // A single-byte file (its declared encoding, else Windows' "ANSI" from Notepad): decoded
            // properly, and its declaration, which no longer applies, taken out (it is saved as UTF-8).
            val cs = declared ?: runCatching { java.nio.charset.Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)
            return String(bytes, cs).replaceFirst(Regex("""(<\?xml[^>]*?)\s+encoding\s*=\s*["'][^"']*["']"""), "$1")
        }

        /** [b] holds at least one well-formed multi-byte UTF-8 character (so it is UTF-8, if a damaged one). */
        private fun hasUtf8Sequences(b: ByteArray): Boolean {
            var i = 0
            while (i < b.size) {
                val c = b[i].toInt() and 0xFF
                val n = when { c in 0xC2..0xDF -> 1; c in 0xE0..0xEF -> 2; c in 0xF0..0xF4 -> 3; else -> 0 }
                if (n > 0 && i + n < b.size && (1..n).all { (b[i + it].toInt() and 0xC0) == 0x80 }) return true
                i++
            }
            return false
        }

        fun escape(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
                '"' -> append("&quot;"); else -> append(c)
            }
        }
    }
}
