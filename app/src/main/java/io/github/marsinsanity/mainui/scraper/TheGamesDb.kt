package io.github.marsinsanity.mainui.scraper

import java.io.File
import java.io.Reader

/**
 * TheGamesDB (thegamesdb.net): its public dump, cdn.thegamesdb.net/json/database-latest.json
 * (about 146 MB, 36 MB compressed on the wire), holds every game with its platform, title,
 * alternate titles, region and, for disc systems, serials (SLUS-20672, T1202N...), plus the file
 * names of its box art. MainUI reads it once a month into small per-platform indexes (front boxes
 * only) and matches games by serial, else by exact title, choosing among same-title entries by
 * region and refusing real ambiguities. Images come from cdn.thegamesdb.net/images/<size>/<file>.
 */
object TheGamesDb {
    /** MainUI system id -> TheGamesDB platform ids. */
    val PLATFORMS: Map<String, List<Int>> = mapOf(
        "nes" to listOf(7), "fds" to listOf(4936), "snes" to listOf(6), "snesmsu1" to listOf(6), "snes-hacks" to listOf(6),
        "satellaview" to listOf(6), "sufami" to listOf(6),
        "gb" to listOf(4), "sgb" to listOf(4), "gbc" to listOf(41), "gba" to listOf(5),
        "n64" to listOf(3), "n64dd" to listOf(3), "nds" to listOf(8), "3ds" to listOf(4912), "virtualboy" to listOf(4918),
        "pokemonmini" to listOf(4957), "gameandwatch" to listOf(4950), "gc" to listOf(2), "wii" to listOf(9),
        "switch" to listOf(4971),
        "megadrive" to listOf(18, 36), "msumd" to listOf(18, 36), "mastersystem" to listOf(35), "gamegear" to listOf(20),
        "sg-1000" to listOf(4949), "sega32x" to listOf(33), "pico" to listOf(4958), "segacd" to listOf(21),
        "saturn" to listOf(17), "dreamcast" to listOf(16),
        "psx" to listOf(10), "ps2" to listOf(11), "ps3" to listOf(12), "psp" to listOf(13), "pspminis" to listOf(13),
        "psvita" to listOf(39),
        "pcengine" to listOf(34), "supergrafx" to listOf(34), "pcenginecd" to listOf(4955), "pcfx" to listOf(4930),
        "pc98" to listOf(4934),
        "atari2600" to listOf(22), "atari5200" to listOf(26), "atari7800" to listOf(27), "atari800" to listOf(4943),
        "atarixegs" to listOf(30), "atarilynx" to listOf(4924), "atarijaguar" to listOf(28, 29), "atarist" to listOf(4937),
        "coleco" to listOf(31), "intellivision" to listOf(32), "vectrex" to listOf(4939),
        "odyssey2" to listOf(4927), "videopac" to listOf(4927), "channelf" to listOf(4928),
        "ngp" to listOf(4922), "ngpc" to listOf(4923), "neogeo" to listOf(24), "neogeocd" to listOf(4956),
        "wonderswan" to listOf(4925), "wonderswancolor" to listOf(4926),
        "msx" to listOf(4929), "msx2" to listOf(4929), "xbox" to listOf(14), "3do" to listOf(25),
        "amigacd32" to listOf(4947), "amiga" to listOf(4911), "c64" to listOf(40), "c128" to listOf(4946),
        "vic20" to listOf(4945), "c16" to listOf(5006), "supervision" to listOf(4959), "megaduck" to listOf(4948),
        "zxspectrum" to listOf(4913), "zx81" to listOf(5010), "amstradcpc" to listOf(4914), "gx4000" to listOf(4999),
        "x68000" to listOf(4931), "x1" to listOf(4977), "scv" to listOf(4966), "cdimono1" to listOf(4917),
        "ti99" to listOf(4953), "coco3" to listOf(4941), "dragon32" to listOf(4952), "bbcmicro" to listOf(5013),
        "apple2" to listOf(4942), "advision" to listOf(4974), "astrocde" to listOf(4968), "j2me" to listOf(5018),
        "arcade" to listOf(23), "mame" to listOf(23), "mame2003" to listOf(23), "cps1" to listOf(23),
        "cps2" to listOf(23), "cps3" to listOf(23), "naomi" to listOf(23), "atomiswave" to listOf(23),
    )

    fun supports(systemId: String) = PLATFORMS.containsKey(systemId)

    /** A game of a platform index: names, serial keys, region ("USA", "Japan", "Germany"... or ""), front box file. */
    data class Game(val id: String, val names: List<String>, val serials: List<String>, val region: String, val file: String)

    // ---- regions -------------------------------------------------------------------------------

    /** TheGamesDB country ids seen on its site; others fall back to the region. */
    private val COUNTRIES = mapOf(50 to "USA", 8 to "Canada", 28 to "Japan", 18 to "UK", 4 to "Australia",
        13 to "Germany", 17 to "France", 15 to "Spain", 37 to "Poland", 40 to "Russia", 14 to "Denmark",
        11 to "China", 7 to "Brazil")

    /** Region ids: 1 NTSC, 2 NTSC-U, 3 NTSC-C, 4 NTSC-J, 5 NTSC-K, 6 PAL, 9 Other, 0 not set. */
    private val REGIONS = mapOf(1 to "USA", 2 to "USA", 3 to "China", 4 to "Japan", 5 to "Korea", 6 to "Europe")

    fun region(regionId: Int?, countryId: Int?): String =
        countryId?.let { COUNTRIES[it] } ?: regionId?.let { REGIONS[it] } ?: ""

    /** A country's wider region. */
    private val PARENT = mapOf("Canada" to "USA", "UK" to "Europe", "Australia" to "Europe", "Germany" to "Europe",
        "France" to "Europe", "Spain" to "Europe", "Poland" to "Europe", "Russia" to "Europe", "Denmark" to "Europe",
        "Italy" to "Europe", "Netherlands" to "Europe", "Sweden" to "Europe", "Brazil" to "USA")

    // ---- reading the dump ----------------------------------------------------------------------

    /**
     * Streams database-latest.json and writes `<platform id>.tsv` into [outDir] for [platforms]:
     * `id \t name|alternate|... \t serial|... \t region \t boxart/front/123-1.jpg`, games with a
     * front box only. Returns games written per platform.
     */
    fun buildIndex(reader: Reader, platforms: Set<Int>, outDir: File, progress: (Long) -> Unit = {}): Map<Int, Int> {
        class G(val platform: Int, val names: List<String>, val serials: List<String>, val region: String)
        val games = HashMap<String, G>()
        val fronts = HashMap<String, String>()
        val p = JsonPull(reader)
        var lastReport = 0L
        fun report() { if (p.charsRead - lastReport >= 1 shl 20) { lastReport = p.charsRead; progress(p.charsRead) } }

        fun readGame() {
            var id: String? = null; var title: String? = null; var platform = -1
            var regionId: Int? = null; var countryId: Int? = null
            val alternates = ArrayList<String>(); val serials = ArrayList<String>()
            while (p.next() == JsonPull.T.NAME) {
                when (p.str) {
                    "id" -> { p.next(); id = p.str }
                    "game_title" -> { if (p.next() == JsonPull.T.STRING) title = p.str }
                    "platform" -> { p.next(); platform = p.str.toIntOrNull() ?: -1 }
                    "region_id" -> { p.next(); regionId = p.str.toIntOrNull() }
                    "country_id" -> { p.next(); countryId = p.str.toIntOrNull() }
                    "alternates" -> if (p.enter(JsonPull.T.BEGIN_ARRAY)) {
                        while (true) { val t = p.next(); if (t == JsonPull.T.END_ARRAY) break; if (t == JsonPull.T.STRING) alternates += p.str else p.skipRest(t) }
                    }
                    "uids" -> if (p.enter(JsonPull.T.BEGIN_ARRAY)) {
                        while (true) {
                            val t = p.next()
                            if (t == JsonPull.T.END_ARRAY) break
                            if (t != JsonPull.T.BEGIN_OBJECT) { p.skipRest(t); continue }
                            while (p.next() == JsonPull.T.NAME) {
                                if (p.str == "uid") { if (p.next() == JsonPull.T.STRING) serials += p.str } else p.skipValue()
                            }
                        }
                    }
                    else -> p.skipValue()
                }
            }
            val gid = id; val t = title
            if (gid != null && t != null && platform in platforms) {
                val names = (listOf(t) + alternates).map { LaunchBoxDb.unescape(it).replace('|', '/').replace('\t', ' ').trim() }
                    .filter { it.isNotEmpty() }.distinct()
                val keys = serials.flatMap { LibretroDb.serialKeys(it) }.distinct()
                games[gid] = G(platform, names, keys, region(regionId, countryId))
            }
            report()
        }

        fun readBoxart() {
            // "data": { "<game id>": [ { "type": "boxart", "side": "front", "filename": "boxart/front/1-1.jpg" }, ... ] }
            while (p.next() == JsonPull.T.NAME) {
                if (p.str != "data") { p.skipValue(); continue }
                if (!p.enter(JsonPull.T.BEGIN_OBJECT)) continue
                while (p.next() == JsonPull.T.NAME) {
                    val gid = p.str
                    if (!p.enter(JsonPull.T.BEGIN_ARRAY)) continue
                    val keep = gid in games && gid !in fronts
                    while (true) {
                        val t = p.next()
                        if (t == JsonPull.T.END_ARRAY) break
                        if (t != JsonPull.T.BEGIN_OBJECT) { p.skipRest(t); continue }
                        var type = ""; var side = ""; var file = ""
                        while (p.next() == JsonPull.T.NAME) {
                            when (p.str) {
                                "type" -> { p.next(); type = p.str }
                                "side" -> { p.next(); side = p.str }
                                "filename" -> { p.next(); file = p.str }
                                else -> p.skipValue()
                            }
                        }
                        if (keep && type == "boxart" && side == "front" && file.isNotEmpty() && gid !in fronts) fronts[gid] = file
                    }
                    report()
                }
            }
        }

        if (p.next() != JsonPull.T.BEGIN_OBJECT) throw java.io.IOException("not a TheGamesDB dump")
        while (p.next() == JsonPull.T.NAME) {
            when (p.str) {
                "data" -> {
                    if (!p.enter(JsonPull.T.BEGIN_OBJECT)) continue
                    while (p.next() == JsonPull.T.NAME) {
                        if (p.str != "games") { p.skipValue(); continue }
                        if (!p.enter(JsonPull.T.BEGIN_ARRAY)) continue
                        while (true) {
                            val t = p.next()
                            if (t == JsonPull.T.END_ARRAY) break
                            if (t == JsonPull.T.BEGIN_OBJECT) readGame() else p.skipRest(t)
                        }
                    }
                }
                "include" -> {
                    if (!p.enter(JsonPull.T.BEGIN_OBJECT)) continue
                    while (p.next() == JsonPull.T.NAME) {
                        if (p.str == "boxart") { if (p.enter(JsonPull.T.BEGIN_OBJECT)) readBoxart() } else p.skipValue()
                    }
                }
                else -> p.skipValue()
            }
        }
        if (games.isEmpty()) throw java.io.IOException("no games in the TheGamesDB dump")
        outDir.mkdirs()
        val count = HashMap<Int, Int>()
        val writers = HashMap<Int, java.io.Writer>()
        try {
            for ((id, g) in games) {
                val f = fronts[id] ?: continue
                val w = writers.getOrPut(g.platform) { File(outDir, "${g.platform}.tsv").bufferedWriter() }
                w.write(id); w.write("\t"); w.write(g.names.joinToString("|")); w.write("\t")
                w.write(g.serials.joinToString("|")); w.write("\t"); w.write(g.region); w.write("\t"); w.write(f); w.write("\n")
                count[g.platform] = (count[g.platform] ?: 0) + 1
            }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
        }
        return count
    }

    fun parseIndex(lines: Sequence<String>): List<Game> = lines.mapNotNull { l ->
        val p = l.split('\t')
        if (p.size < 5 || p[4].isEmpty()) null
        else Game(p[0], p[1].split('|').filter { it.isNotEmpty() }, p[2].split('|').filter { it.isNotEmpty() }, p[3], p[4])
    }.toList()

    // ---- matching ------------------------------------------------------------------------------

    private fun keys(name: String): List<String> {
        val t = BoxArtMatch.title(name)
        return listOf(t, t.replace(" ", "")).distinct()
    }

    /** One system's games by title and by serial. */
    class Lookup(games: List<Game>) {
        private val byTitle = HashMap<String, MutableSet<Game>>()
        private val bySerial = HashMap<String, MutableSet<Game>>()
        init {
            for (g in games) {
                for (n in g.names) for (k in keys(n)) if (k.isNotEmpty()) byTitle.getOrPut(k) { LinkedHashSet() } += g
                for (s in g.serials) bySerial.getOrPut(s) { LinkedHashSet() } += g
            }
        }

        /** The one game with one of [serials] (read from the disc), or null. */
        fun bySerial(serials: List<String>): Game? =
            serials.flatMap { LibretroDb.serialKeys(it) }.flatMap { bySerial[it].orEmpty() }.distinct().singleOrNull()

        /**
         * The game called [source] (a canonical dump name or a file name). TheGamesDB often has an
         * entry per region for the same game: the dump's own region decides, then its wider region,
         * then [prefs]. Two different entries for one title in that region (NES "Tetris" by
         * Nintendo and by Tengen) can't be told apart: null.
         */
        fun find(source: String, prefs: List<String>): Game? {
            for (k in keys(source)) {
                val hits = byTitle[k] ?: continue
                return choose(hits, source, prefs)
            }
            return null
        }
    }

    fun choose(hits: Collection<Game>, source: String, prefs: List<String>): Game? {
        if (hits.size == 1) return hits.first()
        val own = BoxArtMatch.regions(source).toList()
        val wanted = (own + own.mapNotNull { PARENT[it] } + prefs + listOf("World", "")).distinct()
        for (r in wanted) {
            var c = hits.filter { it.region == r }
            if (c.isEmpty() && r.isNotEmpty()) c = hits.filter { PARENT[it.region] == r }
            if (c.size == 1) return c.first()
            if (c.size > 1) return null
        }
        return null
    }

    /** Image size for a wanted width: medium is 720 px wide, large 1080, original as scanned. */
    fun imageSize(maxWidth: Int) = if (maxWidth in 1..720) "medium" else "large"
}

/**
 * A small pull reader for JSON, enough to stream a huge file without keeping it: tokens one by
 * one, names told from string values, [skipValue] for whole objects / arrays.
 */
class JsonPull(private val r: Reader) {
    enum class T { BEGIN_OBJECT, END_OBJECT, BEGIN_ARRAY, END_ARRAY, NAME, STRING, NUMBER, BOOLEAN, NULL, END }

    /** Text of the last NAME / STRING / NUMBER / BOOLEAN / NULL. */
    var str: String = ""
        private set
    var charsRead = 0L
        private set

    private val buf = CharArray(1 shl 16)
    private var pos = 0
    private var lim = 0
    private val sb = StringBuilder()
    private val stack = ArrayList<Int>()     // OBJ_NAME, OBJ_VALUE or ARR

    private fun fill(): Boolean {
        if (pos < lim) return true
        val n = r.read(buf)
        if (n <= 0) { pos = 0; lim = 0; return false }
        pos = 0; lim = n; charsRead += n
        return true
    }

    private fun valueDone() { if (stack.isNotEmpty() && stack.last() == OBJ_VALUE) stack[stack.size - 1] = OBJ_NAME }

    fun next(): T {
        while (true) {
            if (!fill()) return T.END
            val c = buf[pos]
            when (c) {
                ' ', '\n', '\r', '\t', ',', ':' -> { pos++; continue }
                '{' -> { pos++; stack += OBJ_NAME; return T.BEGIN_OBJECT }
                '[' -> { pos++; stack += ARR; return T.BEGIN_ARRAY }
                '}' -> { pos++; if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); valueDone(); return T.END_OBJECT }
                ']' -> { pos++; if (stack.isNotEmpty()) stack.removeAt(stack.size - 1); valueDone(); return T.END_ARRAY }
                '"' -> {
                    pos++
                    str = readString()
                    if (stack.isNotEmpty() && stack.last() == OBJ_NAME) { stack[stack.size - 1] = OBJ_VALUE; return T.NAME }
                    valueDone(); return T.STRING
                }
                else -> {
                    str = readLiteral()
                    valueDone()
                    return when (str) { "null" -> T.NULL; "true", "false" -> T.BOOLEAN; else -> T.NUMBER }
                }
            }
        }
    }

    /** Reads the next token: true when it is [expected], else skips that value and returns false. */
    fun enter(expected: T): Boolean {
        val t = next()
        if (t == expected) return true
        skipRest(t)
        return false
    }

    /** Skips the value that comes next (after a NAME). */
    fun skipValue() = skipRest(next())

    /** Skips the rest of a value whose first token [t] was just read. */
    fun skipRest(t: T) {
        if (t != T.BEGIN_OBJECT && t != T.BEGIN_ARRAY) return
        var depth = 1
        while (depth > 0) {
            when (next()) {
                T.BEGIN_OBJECT, T.BEGIN_ARRAY -> depth++
                T.END_OBJECT, T.END_ARRAY -> depth--
                T.END -> return
                else -> {}
            }
        }
    }

    private fun readString(): String {
        sb.setLength(0)
        while (true) {
            if (!fill()) return sb.toString()
            val c = buf[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (!fill()) return sb.toString()
                    when (val e = buf[pos++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                        'b' -> sb.append('\b'); 'f' -> sb.append('\u000C')
                        'u' -> {
                            var v = 0
                            repeat(4) { if (fill()) v = v * 16 + Character.digit(buf[pos++], 16).coerceAtLeast(0) }
                            sb.append(v.toChar())
                        }
                        else -> sb.append(e)   // " \ /
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun readLiteral(): String {
        sb.setLength(0)
        while (fill()) {
            val c = buf[pos]
            if (c == ',' || c == '}' || c == ']' || c == ':' || c == ' ' || c == '\n' || c == '\r' || c == '\t') break
            sb.append(c); pos++
        }
        return sb.toString()
    }

    private companion object {
        const val OBJ_NAME = 1
        const val OBJ_VALUE = 2
        const val ARR = 3
    }
}
