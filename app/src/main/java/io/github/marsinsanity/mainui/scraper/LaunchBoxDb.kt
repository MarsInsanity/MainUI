package io.github.marsinsanity.mainui.scraper

import java.io.BufferedReader
import java.io.File

/**
 * LaunchBox Games Database (gamesdb.launchbox-app.com): its public Metadata.zip holds every
 * game, alternate name and image. MainUI reads it once a month into small per-platform
 * indexes (only front boxes are kept) and matches games by title, refusing ambiguous ones.
 * Images come from images.launchbox-app.com/<FileName>.
 */
object LaunchBoxDb {
    /** MainUI system id -> LaunchBox platform names (as in Metadata.xml). */
    val PLATFORMS: Map<String, List<String>> = buildMap {
        fun p(name: String, vararg ids: String) = ids.forEach { put(it, (get(it) ?: emptyList()) + name) }
        p("Nintendo Entertainment System", "nes")
        p("Nintendo Famicom Disk System", "fds")
        p("Super Nintendo Entertainment System", "snes", "snesmsu1")
        p("Nintendo Satellaview", "satellaview")
        p("Nintendo Game Boy", "gb", "sgb")
        p("Nintendo Game Boy Color", "gbc")
        p("Nintendo Game Boy Advance", "gba")
        p("Nintendo 64", "n64")
        p("Nintendo 64DD", "n64dd")
        p("Nintendo DS", "nds")
        p("Nintendo 3DS", "3ds")
        p("Nintendo Virtual Boy", "virtualboy")
        p("Nintendo Pokemon Mini", "pokemonmini")
        p("Nintendo Game & Watch", "gameandwatch")
        p("Nintendo GameCube", "gc")
        p("Nintendo Wii", "wii")
        p("Nintendo Switch", "switch")
        p("Sega Genesis", "megadrive", "msumd")
        p("Sega Master System", "mastersystem")
        p("Sega Game Gear", "gamegear")
        p("Sega SG-1000", "sg-1000")
        p("Sega 32X", "sega32x")
        p("Sega Pico", "pico")
        p("Sega CD", "segacd")
        p("Sega Saturn", "saturn")
        p("Sega Dreamcast", "dreamcast")
        p("Sega Naomi", "naomi")
        p("Sega Dreamcast VMU", "vmu")
        p("Sammy Atomiswave", "atomiswave")
        p("Sony Playstation", "psx")
        p("Sony Playstation 2", "ps2")
        p("Sony Playstation 3", "ps3")
        p("Sony PSP", "psp")
        p("Sony PSP Minis", "pspminis")
        p("Sony Playstation Vita", "psvita")
        p("NEC TurboGrafx-16", "pcengine")
        p("PC Engine SuperGrafx", "supergrafx")
        p("NEC TurboGrafx-CD", "pcenginecd")
        p("NEC PC-FX", "pcfx")
        p("NEC PC-9801", "pc98")
        p("Atari 2600", "atari2600")
        p("Atari 5200", "atari5200")
        p("Atari 7800", "atari7800")
        p("Atari 800", "atari800")
        p("Atari XEGS", "atarixegs")
        p("Atari Lynx", "atarilynx")
        p("Atari Jaguar", "atarijaguar")
        p("Atari ST", "atarist")
        p("ColecoVision", "coleco")
        p("Mattel Intellivision", "intellivision")
        p("GCE Vectrex", "vectrex")
        p("Magnavox Odyssey 2", "odyssey2", "videopac")
        p("Philips Videopac+", "videopac")
        p("Fairchild Channel F", "channelf")
        p("SNK Neo Geo Pocket", "ngp")
        p("SNK Neo Geo Pocket Color", "ngpc")
        p("SNK Neo Geo AES", "neogeo")
        p("SNK Neo Geo MVS", "neogeo")
        p("SNK Neo Geo CD", "neogeocd")
        p("WonderSwan", "wonderswan")
        p("WonderSwan Color", "wonderswancolor")
        p("Microsoft MSX", "msx")
        p("Microsoft MSX2", "msx2")
        p("Microsoft Xbox", "xbox")
        p("3DO Interactive Multiplayer", "3do")
        p("Commodore Amiga CD32", "amigacd32")
        p("Commodore Amiga", "amiga")
        p("Commodore 64", "c64")
        p("Commodore 128", "c128")
        p("Commodore VIC-20", "vic20")
        p("Commodore Plus 4", "c16")
        p("Watara Supervision", "supervision")
        p("Mega Duck", "megaduck")
        p("Sinclair ZX Spectrum", "zxspectrum")
        p("Sinclair ZX-81", "zx81")
        p("Amstrad CPC", "amstradcpc")
        p("Amstrad GX4000", "gx4000")
        p("Sharp X68000", "x68000")
        p("Sharp X1", "x1")
        p("Epoch Super Cassette Vision", "scv")
        p("Arduboy", "arduboy")
        p("MS-DOS", "dos")
        p("ScummVM", "scummvm")
        p("OpenBOR", "openbor")
        p("PICO-8", "pico-8")
        p("Philips CD-i", "cdimono1")
        p("WASM-4", "wasm4")
        p("Uzebox", "uzebox")
        p("Texas Instruments TI 99/4A", "ti99")
        p("TRS-80 Color Computer", "coco3")
        p("Dragon 32/64", "dragon32")
        p("BBC Microcomputer System", "bbcmicro")
        p("Apple II", "apple2")
        p("Entex Adventure Vision", "advision")
        p("Bally Astrocade", "astrocde")
        p("Arcade", "arcade", "mame", "mame2003", "cps1", "cps2", "cps3")
    }

    fun supports(systemId: String) = PLATFORMS.containsKey(systemId)

    /** A game in a platform index: its names and front boxes by region. */
    data class Game(val id: String, val names: List<String>, val boxes: List<Pair<String, String>>)

    /** Front box regions in LaunchBox for a No-Intro / preference region word. */
    private val REGION_NAMES = mapOf(
        "USA" to listOf("North America", "United States", "Canada"),
        "Europe" to listOf("Europe", "United Kingdom", "Germany", "France", "Spain", "Italy", "Netherlands", "Sweden",
            "Scandinavia", "Australia", "Greece", "Portugal", "Finland", "Norway", "Denmark", "Russia", "Poland"),
        "Japan" to listOf("Japan"),
        "World" to listOf("World"),
        "Korea" to listOf("Korea"), "China" to listOf("China"), "Taiwan" to listOf("Taiwan"), "Asia" to listOf("Asia"),
        "Brazil" to listOf("Brazil"), "Australia" to listOf("Australia"), "UK" to listOf("United Kingdom"),
        "France" to listOf("France"), "Germany" to listOf("Germany"), "Spain" to listOf("Spain"), "Italy" to listOf("Italy"),
    )

    /** Wider region to fall back to before the user's preference order. */
    private val PARENT = mapOf(
        "France" to "Europe", "Germany" to "Europe", "Spain" to "Europe", "Italy" to "Europe", "UK" to "Europe",
        "Netherlands" to "Europe", "Sweden" to "Europe", "Scandinavia" to "Europe", "Denmark" to "Europe",
        "Finland" to "Europe", "Norway" to "Europe", "Portugal" to "Europe", "Greece" to "Europe", "Poland" to "Europe",
        "Russia" to "Europe", "Australia" to "Europe", "Canada" to "USA", "Taiwan" to "Asia", "Hong Kong" to "Asia",
    )

    // ---- reading Metadata.xml ----------------------------------------------------------------

    private val field = Regex("""^\s*<([A-Za-z]+)>(.*)</\1>\s*$""")

    /**
     * Streams Metadata.xml and writes `<platform>.tsv` into [outDir] for [platforms]:
     * `id \t name|alternate|... \t region=file|region=file`. Metadata.xml is pretty printed
     * with one element per line and escapes text, so a line reader is safe (a multi-line
     * Overview can never contain a raw `<Tag>`). Returns games written per platform.
     */
    fun buildIndex(xml: BufferedReader, platforms: Set<String>, outDir: File, progress: (Long) -> Unit = {}): Map<String, Int> {
        val games = HashMap<String, Pair<String, MutableList<String>>>()       // id -> platform, names
        val boxes = HashMap<String, MutableList<Pair<String, String>>>()       // id -> (region, file)
        var section = ""
        val cur = HashMap<String, String>()
        var read = 0L
        while (true) {
            val line = xml.readLine() ?: break
            read += line.length + 1
            if ((read and 0xFFFFF) < line.length + 1) progress(read)
            val t = line.trim()
            when (t) {
                "<Game>", "<GameAlternateName>", "<GameImage>" -> { section = t; cur.clear(); continue }
                "</Game>" -> {
                    val id = cur["DatabaseID"]; val p = cur["Platform"]; val n = cur["Name"]
                    if (id != null && p != null && n != null && p in platforms) games[id] = p to mutableListOf(n)
                    section = ""; continue
                }
                "</GameAlternateName>" -> {
                    val id = cur["DatabaseID"]; val n = cur["AlternateName"]
                    if (id != null && n != null) games[id]?.second?.add(n)
                    section = ""; continue
                }
                "</GameImage>" -> {
                    val id = cur["DatabaseID"]; val f = cur["FileName"]
                    if (id != null && f != null && cur["Type"] == "Box - Front")
                        boxes.getOrPut(id) { ArrayList(2) } += (cur["Region"] ?: "") to f
                    section = ""; continue
                }
            }
            if (section.isEmpty()) continue
            val m = field.matchEntire(line) ?: continue
            val k = m.groupValues[1]
            if (k in KEEP && k !in cur) cur[k] = unescape(m.groupValues[2])
        }
        outDir.mkdirs()
        val count = HashMap<String, Int>()
        val writers = HashMap<String, java.io.Writer>()
        try {
            for ((id, pn) in games) {
                val b = boxes[id] ?: continue
                val w = writers.getOrPut(pn.first) { File(outDir, fileName(pn.first)).bufferedWriter() }
                w.write(id); w.write("\t")
                w.write(pn.second.distinct().joinToString("|") { it.replace('|', '/').replace('\t', ' ') }); w.write("\t")
                w.write(b.joinToString("|") { (r, f) -> "$r=$f" }); w.write("\n")
                count[pn.first] = (count[pn.first] ?: 0) + 1
            }
        } finally {
            writers.values.forEach { runCatching { it.close() } }
        }
        return count
    }

    private val KEEP = setOf("DatabaseID", "Platform", "Name", "AlternateName", "FileName", "Type", "Region")

    fun fileName(platform: String) = platform.replace(Regex("[^A-Za-z0-9]+"), "_") + ".tsv"

    fun unescape(s: String): String = if ('&' !in s) s else Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);").replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            e == "amp" -> "&"; e == "lt" -> "<"; e == "gt" -> ">"; e == "quot" -> "\""; else -> "'"
        }
    }

    fun parseIndex(lines: Sequence<String>): List<Game> = lines.mapNotNull { l ->
        val parts = l.split('\t')
        if (parts.size < 3) null
        else Game(parts[0], parts[1].split('|'), parts[2].split('|').mapNotNull { rf ->
            val i = rf.indexOf('='); if (i < 0) null else rf.substring(0, i) to rf.substring(i + 1)
        })
    }.toList()

    // ---- matching ------------------------------------------------------------------------------

    /** Titles compared with spaces removed too ("Pokemon FireRed" = "Pokemon - Fire Red"). */
    private fun keys(name: String): List<String> {
        val t = BoxArtMatch.title(name)
        return listOf(t, t.replace(" ", "")).distinct()
    }

    /** Title -> games, for one platform's games. */
    class Lookup(games: List<Game>) {
        private val byTitle = HashMap<String, MutableSet<Game>>()
        init {
            for (g in games) for (n in g.names) for (k in keys(n)) if (k.isNotEmpty()) byTitle.getOrPut(k) { LinkedHashSet() } += g
        }

        /**
         * The game called [source] (a canonical dump name or a file name), or null when no game
         * or more than one has that title: then it can't be told which one this is.
         */
        fun find(source: String): Game? {
            for (k in keys(source)) {
                val hits = byTitle[k] ?: continue
                return hits.singleOrNull()
            }
            return null
        }
    }

    /**
     * The front box to use: the dump's own region (from its name's tags), then [prefs]
     * ("USA", "Europe", ...), then World, then any.
     */
    fun pickBox(game: Game, source: String, prefs: List<String>): String? {
        if (game.boxes.isEmpty()) return null
        // The dump's own regions first, a European country then Europe, then the user's order.
        val own = BoxArtMatch.regions(source).toList()
        val parents = own.mapNotNull { r -> PARENT[r] }
        val wanted = (own + parents + prefs + listOf("World")).distinct()
        for (r in wanted) {
            val names = REGION_NAMES[r] ?: listOf(r)
            game.boxes.firstOrNull { it.first in names }?.let { return it.second }
        }
        return (game.boxes.firstOrNull { it.first.isEmpty() } ?: game.boxes.first()).second
    }
}
