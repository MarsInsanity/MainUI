package io.github.marsinsanity.mainui.scraper

/**
 * libretro-database DATs (No-Intro / Redump / FBNeo), which give every known dump
 * its canonical name, the name libretro's thumbnails are filed under.
 */
object LibretroDb {
    /** A DAT in libretro-database/metadat/<dir>/<name>.dat and the thumbnail set for it. */
    data class Source(val dir: String, val dat: String, val thumbs: String = dat)

    /** MainUI system id -> DAT + thumbnail set (all checked to exist upstream). */
    val SYSTEMS: Map<String, Source> = buildMap {
        fun ni(name: String, vararg ids: String) = ids.forEach { put(it, Source("no-intro", name)) }
        fun rd(name: String, vararg ids: String) = ids.forEach { put(it, Source("redump", name)) }
        ni("Nintendo - Nintendo Entertainment System", "nes")
        ni("Nintendo - Family Computer Disk System", "fds")
        ni("Nintendo - Super Nintendo Entertainment System", "snes", "snesmsu1")
        ni("Nintendo - Satellaview", "satellaview")
        ni("Nintendo - Sufami Turbo", "sufami")
        ni("Nintendo - Game Boy", "gb", "sgb")
        ni("Nintendo - Game Boy Color", "gbc")
        ni("Nintendo - Game Boy Advance", "gba")
        ni("Nintendo - Nintendo 64", "n64")
        ni("Nintendo - Nintendo 64DD", "n64dd")
        ni("Nintendo - Nintendo DS", "nds")
        ni("Nintendo - Nintendo 3DS", "3ds")
        ni("Nintendo - Virtual Boy", "virtualboy")
        ni("Nintendo - Pokemon Mini", "pokemonmini")
        rd("Nintendo - GameCube", "gc")
        rd("Nintendo - Wii", "wii")
        ni("Sega - Mega Drive - Genesis", "megadrive", "msumd")
        ni("Sega - Master System - Mark III", "mastersystem")
        ni("Sega - Game Gear", "gamegear")
        ni("Sega - SG-1000", "sg-1000")
        ni("Sega - 32X", "sega32x")
        ni("Sega - PICO", "pico")
        rd("Sega - Mega-CD - Sega CD", "segacd")
        rd("Sega - Saturn", "saturn")
        rd("Sega - Dreamcast", "dreamcast")
        rd("Sega - Naomi", "naomi")
        rd("Sony - PlayStation", "psx")
        rd("Sony - PlayStation 2", "ps2")
        rd("Sony - PlayStation Portable", "psp")
        rd("Sony - PlayStation 3", "ps3")
        ni("Sony - PlayStation Vita", "psvita")
        ni("NEC - PC Engine - TurboGrafx 16", "pcengine")
        ni("NEC - PC Engine SuperGrafx", "supergrafx")
        rd("NEC - PC Engine CD - TurboGrafx-CD", "pcenginecd")
        rd("NEC - PC-FX", "pcfx")
        ni("Atari - 2600", "atari2600")
        ni("Atari - 5200", "atari5200")
        ni("Atari - 7800", "atari7800")
        ni("Atari - Lynx", "atarilynx")
        ni("Atari - Jaguar", "atarijaguar")
        ni("Atari - ST", "atarist")
        ni("Coleco - ColecoVision", "coleco")
        ni("Mattel - Intellivision", "intellivision")
        ni("GCE - Vectrex", "vectrex")
        ni("Magnavox - Odyssey2", "odyssey2", "videopac")
        ni("Fairchild - Channel F", "channelf")
        ni("SNK - Neo Geo Pocket", "ngp")
        ni("SNK - Neo Geo Pocket Color", "ngpc")
        rd("SNK - Neo Geo CD", "neogeocd")
        ni("Bandai - WonderSwan", "wonderswan")
        ni("Bandai - WonderSwan Color", "wonderswancolor")
        ni("Microsoft - MSX", "msx")
        ni("Microsoft - MSX2", "msx2")
        rd("The 3DO Company - 3DO", "3do")
        rd("Commodore - CD32", "amigacd32")
        rd("Philips - CD-i", "cdimono1")
        ni("Watara - Supervision", "supervision")
        ni("Commodore - 64", "c64")
        ni("Sharp - X68000", "x68000")
        ni("Epoch - Super Cassette Vision", "scv")
        ni("Arduboy Inc - Arduboy", "arduboy")
        put("zxspectrum", Source("no-intro", "Sinclair - ZX Spectrum +3", "Sinclair - ZX Spectrum"))
        // Arcade: the DAT maps the zip's short name ("sf2.zip") to the title the art is filed under.
        listOf("arcade", "neogeo", "cps1", "cps2", "cps3", "mame", "mame2003").forEach { put(it, Source("fbneo-split", "FBNeo - Arcade Games")) }
    }

    fun supports(systemId: String) = SYSTEMS.containsKey(systemId)

    /** Canonical names by CRC, by serial and by dump file name. */
    class Dat(
        val byCrc: Map<String, List<String>>,
        val bySerial: Map<String, List<String>>,
        val byFile: Map<String, List<String>>,
        val names: Set<String>,
    ) {
        fun crc(crc: String) = byCrc[crc.uppercase()].orEmpty()
        fun serial(s: String) = serialKeys(s).flatMap { bySerial[it].orEmpty() }.distinct()
        fun file(name: String) = byFile[name.lowercase()].orEmpty()
    }

    private val quoted = Regex("^\\s*name\\s+\"(.*)\"\\s*$")
    private val romName = Regex("""\bname\s+(?:"([^"]*)"|(\S+))""")
    private val crcRe = Regex("""\bcrc\s+([0-9A-Fa-f]{8})\b""")
    private val serialRe = Regex("""\bserial\s+"([^"]*)"""")

    /** Parses a clrmamepro DAT ("game ( name "..." rom ( name ... crc ... serial "..." ) )"). */
    fun parse(text: String): Dat {
        val byCrc = HashMap<String, MutableList<String>>()
        val bySerial = HashMap<String, MutableList<String>>()
        val byFile = HashMap<String, MutableList<String>>()
        val names = LinkedHashSet<String>()
        var game: String? = null
        var inGame = false
        fun add(map: HashMap<String, MutableList<String>>, k: String, v: String) {
            val l = map.getOrPut(k) { ArrayList(1) }
            if (v !in l) l += v
        }
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("game (") || line.startsWith("machine (") -> { game = null; inGame = true }
                line.startsWith("clrmamepro (") -> inGame = false
                inGame && game == null && line.startsWith("name ") -> quoted.find(line)?.let { game = it.groupValues[1]; names += it.groupValues[1] }
                game != null && line.startsWith("rom (") -> {
                    val g = game!!
                    crcRe.find(line)?.let { add(byCrc, it.groupValues[1].uppercase(), g) }
                    serialRe.find(line)?.let { m -> serialKeys(m.groupValues[1]).forEach { add(bySerial, it, g) } }
                    romName.find(line.removePrefix("rom ("))?.let { m ->
                        val f = (m.groupValues[1].ifEmpty { m.groupValues[2] }).lowercase()
                        add(byFile, f, g); add(byFile, f.substringBeforeLast('.'), g)
                    }
                }
                game != null && line.startsWith("serial ") -> serialRe.find(line)?.let { m -> serialKeys(m.groupValues[1]).forEach { add(bySerial, it, game!!) } }
            }
        }
        return Dat(byCrc, bySerial, byFile, names)
    }

    private val sonyId = Regex("""^([A-Z]{4})-?(\d{5})""")
    private val nintendoDisc = Regex("""(?:DOL|RVL)-([A-Z0-9]{4})""")

    /**
     * Keys a serial is indexed and looked up by: upper-case letters and digits only
     * ("SLUS-00594" -> "SLUS00594"), plus the forms other sources give for the same game:
     * Sony ids without a suffix ("SLPM-65002-0" -> "SLPM65002"), and the 4 character disc id
     * of GameCube / Wii serials ("DL-DOL-GW7P-EUR" -> "GW7P").
     */
    fun serialKeys(serial: String): List<String> {
        val s = serial.uppercase().trim()
        val keys = LinkedHashSet<String>()
        val plain = s.filter { it.isLetterOrDigit() }
        if (plain.length >= 4) keys += plain
        sonyId.find(s)?.let { keys += it.groupValues[1] + it.groupValues[2] }
        nintendoDisc.find(s)?.let { keys += it.groupValues[1] }
        return keys.toList()
    }
}
