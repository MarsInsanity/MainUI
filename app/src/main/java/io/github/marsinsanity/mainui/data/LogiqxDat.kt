package io.github.marsinsanity.mainui.data

import android.util.Xml
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

    class Rom(val name: String, val size: Long?, val crc: String?, val md5: String?, val sha1: String?)

    class Game(
        var name: String,
        val isMachine: Boolean = false,
        val fields: LinkedHashMap<String, String> = LinkedHashMap(),
        val attrs: LinkedHashMap<String, String> = LinkedHashMap(),
        val roms: MutableList<Rom> = mutableListOf(),
    )

    val header = LinkedHashMap<String, String>()
    private val games = ArrayList<Game>()
    private val byFile = HashMap<String, Game>()
    private val byName = HashMap<String, Game>()
    private val byCrc = HashMap<String, Game>()
    private var loadedMtime = -1L

    val dir: File get() = file.parentFile ?: file

    fun load(): LogiqxDat {
        games.clear(); header.clear(); byFile.clear(); byName.clear(); byCrc.clear()
        loadedMtime = file.lastModified()
        if (!file.isFile) return this
        val raw = runCatching { file.readText() }.getOrNull() ?: return this
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        try {
            // DOCTYPE declarations are fine for the pull parser; external DTDs are never fetched.
            p.setInput(StringReader(GameListXml.sanitize(raw)))
            var game: Game? = null
            var inHeader = false
            var depth = 0
            var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                when (ev) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val tag = p.name
                        when {
                            depth == 2 && tag == "header" -> inHeader = true
                            depth == 2 && (tag == "game" || tag == "machine" || tag == "software") -> {
                                game = Game(p.getAttributeValue(null, "name") ?: "", isMachine = tag == "machine")
                                for (i in 0 until p.attributeCount) game.attrs[p.getAttributeName(i)] = p.getAttributeValue(i)
                            }
                            depth == 3 && inHeader -> { header[tag] = readText(p); depth-- }
                            depth == 3 && game != null && (tag == "rom" || tag == "disk") -> {
                                game.roms += Rom(
                                    p.getAttributeValue(null, "name") ?: "",
                                    p.getAttributeValue(null, "size")?.toLongOrNull(),
                                    p.getAttributeValue(null, "crc")?.lowercase(),
                                    p.getAttributeValue(null, "md5")?.lowercase(),
                                    p.getAttributeValue(null, "sha1")?.lowercase(),
                                )
                            }
                            depth == 3 && game != null -> { game.fields[tag] = readText(p); depth-- }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (depth == 2) {
                            if (inHeader) inHeader = false
                            game?.let { add(it) }
                            game = null
                        }
                        depth--
                    }
                }
                ev = p.next()
            }
        } catch (e: Exception) {
            android.util.Log.w("MainUI.Logiqx", "Failed to parse ${file.path}: ${e.message}")
        }
        return this
    }

    private fun add(g: Game) {
        games += g
        byName[g.name.lowercase()] = g
        for (r in g.roms) {
            byFile.putIfAbsent(r.name.substringAfterLast('/').substringAfterLast('\\').lowercase(), g)
            r.crc?.let { byCrc.putIfAbsent(it, g) }
        }
    }

    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var d = 1
        while (d > 0) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> d++
                XmlPullParser.END_TAG -> d--
                XmlPullParser.END_DOCUMENT -> return sb.toString()
            }
        }
        return sb.toString()
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

    fun save() {
        val esc = GameListXml::escape
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\"?>\n")
        sb.append("<!DOCTYPE datafile PUBLIC \"-//Logiqx//DTD ROM Management Datafile//EN\" \"http://www.logiqx.com/Dats/datafile.dtd\">\n")
        sb.append("<datafile>\n\t<header>\n")
        if (header.isEmpty()) {
            header["name"] = dir.name
            header["description"] = dir.name
            header["author"] = "MainUI"
        }
        for ((k, v) in header) sb.append("\t\t<").append(k).append('>').append(esc(v)).append("</").append(k).append(">\n")
        sb.append("\t</header>\n")
        // Logiqx element order: comment, description, year, manufacturer, ..., rom
        val order = listOf("comment", "description", "year", "manufacturer")
        for (g in games) {
            val tag = if (g.isMachine) "machine" else "game"
            sb.append("\t<").append(tag)
            val attrs = LinkedHashMap(g.attrs).apply { this["name"] = g.name }
            for ((k, v) in attrs) sb.append(' ').append(k).append("=\"").append(esc(v)).append('"')
            sb.append(">\n")
            val keys = order.filter { g.fields.containsKey(it) } + g.fields.keys.filter { it !in order }
            for (k in keys) sb.append("\t\t<").append(k).append('>').append(esc(g.fields.getValue(k))).append("</").append(k).append(">\n")
            for (r in g.roms) {
                sb.append("\t\t<rom name=\"").append(esc(r.name)).append('"')
                r.size?.let { sb.append(" size=\"").append(it).append('"') }
                r.crc?.let { sb.append(" crc=\"").append(it).append('"') }
                r.md5?.let { sb.append(" md5=\"").append(it).append('"') }
                r.sha1?.let { sb.append(" sha1=\"").append(it).append('"') }
                sb.append("/>\n")
            }
            sb.append("\t</").append(tag).append(">\n")
        }
        sb.append("</datafile>\n")
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(sb.toString())
        if (file.exists()) {
            val bak = File(dir, file.name + ".bak")
            if (!bak.exists()) file.copyTo(bak, overwrite = false)
        }
        if (!tmp.renameTo(file)) { file.writeText(sb.toString()); tmp.delete() }
        loadedMtime = file.lastModified()
    }

    companion object {
        /**
         * Finds the DAT for a system folder: <folder>/<folder>.dat, any *.dat, or an
         * *.xml whose root element is <datafile> (Skraper's generic profile writes XML).
         */
        fun locate(dir: File): File? {
            val files = dir.listFiles() ?: return null
            files.firstOrNull { it.isFile && it.name.equals("${dir.name}.dat", true) }?.let { return it }
            files.filter { it.isFile && it.extension.equals("dat", true) }.minByOrNull { it.name.length }?.let { return it }
            return files.filter { it.isFile && it.extension.equals("xml", true) && !it.name.equals("gamelist.xml", true) }
                .firstOrNull { f -> runCatching { f.bufferedReader().use { r -> CharArray(1024).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }
                    .getOrDefault("").contains("<datafile") }
        }

        /** The DAT the scraper writes into: an existing one, or <folder>/<folder>.dat. */
        fun forWriting(dir: File): LogiqxDat = LogiqxDat(locate(dir) ?: File(dir, "${dir.name}.dat")).load()
    }
}
