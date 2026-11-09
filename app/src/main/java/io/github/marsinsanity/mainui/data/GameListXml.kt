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
 * verbatim so rewriting never loses data written by other tools.
 */
class GameListXml(val dir: File) {

    class Entry(
        var path: String,
        val isFolder: Boolean = false,
        val fields: LinkedHashMap<String, String> = LinkedHashMap(),
        val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
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
    var loaded = false
        private set

    fun load(): GameListXml {
        entries.clear()
        loaded = true
        if (!file.isFile) return this
        val raw = runCatching { file.readText() }.getOrNull() ?: return this
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            parser.setInput(StringReader(sanitize(raw)))
            var current: Entry? = null
            var depth = 0
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val tag = parser.name
                        if (depth == 2 && (tag == "game" || tag == "folder")) {
                            val attrs = LinkedHashMap<String, String>()
                            for (i in 0 until parser.attributeCount) attrs[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                            current = Entry("", tag == "folder", attrs = attrs)
                        } else if (depth == 3 && current != null) {
                            val text = readText(parser)
                            depth-- // readText consumed the END_TAG
                            if (tag == "path") current.path = text.trim() else current.fields[tag] = text
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
        } catch (e: Exception) {
            android.util.Log.w("MainUI.GameList", "Failed to parse ${file.path}: ${e.message}")
        }
        return this
    }

    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
    }

    fun get(romFile: File): Entry? = entries[normalize(relPath(romFile))]

    fun all(): Collection<Entry> = entries.values

    fun put(romFile: File, update: (Entry) -> Unit) {
        val key = normalize(relPath(romFile))
        val e = entries.getOrPut(key) { Entry("./" + relPath(romFile)) }
        update(e)
    }

    /** Resolves an image style field (./images/x.png, absolute, ~/...) to a file. */
    fun resolve(value: String?): File? {
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

    fun save() {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>\n<gameList>\n")
        for (e in entries.values) {
            val tag = if (e.isFolder) "folder" else "game"
            sb.append("\t<").append(tag)
            for ((k, v) in e.attrs) sb.append(' ').append(k).append("=\"").append(escape(v)).append('"')
            sb.append(">\n")
            sb.append("\t\t<path>").append(escape(e.path)).append("</path>\n")
            for ((k, v) in e.fields) sb.append("\t\t<").append(k).append('>').append(escape(v)).append("</").append(k).append(">\n")
            sb.append("\t</").append(tag).append(">\n")
        }
        sb.append("</gameList>\n")
        val tmp = File(dir, "gamelist.xml.tmp")
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
        fun normalize(p: String): String {
            var s = p.trim().replace('\\', '/')
            while (s.startsWith("./")) s = s.substring(2)
            return s.lowercase()
        }

        private val BARE_AMP = Regex("&(?!(?:amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);)")
        private val BAD_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

        fun sanitize(s: String): String = BAD_CHARS.replace(BARE_AMP.replace(s, "&amp;"), "")

        fun escape(s: String): String = buildString(s.length) {
            for (c in s) when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;")
                '"' -> append("&quot;"); else -> append(c)
            }
        }
    }
}
