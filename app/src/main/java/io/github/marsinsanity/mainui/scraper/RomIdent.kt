package io.github.marsinsanity.mainui.scraper

import io.github.marsinsanity.mainui.emu.Ps3Games
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * What identifies a dump exactly: CRC32s (of the file, or of the files in a zip, read
 * from the zip's directory without unpacking) and the serial / game id the cartridge
 * header or disc carries. Both are looked up in the system's DAT; disc images too big
 * to checksum are still identified by their serial.
 */
object RomIdent {
    /** Files up to this size are checksummed when nothing else identifies them. */
    const val MAX_CRC = 256L * 1024 * 1024
    /** Checksummed even when a serial was found (cheap for cartridge sized files). */
    const val SMALL = 64L * 1024 * 1024

    fun crcs(f: File, alsoLarge: Boolean): List<String> {
        if (!f.isFile) return emptyList()
        if (f.extension.equals("zip", true)) return runCatching {
            ZipFile(f).use { z -> z.entries().asSequence().filter { !it.isDirectory && it.crc >= 0 }
                .sortedByDescending { it.size }.map { "%08X".format(it.crc) }.toList() }
        }.getOrDefault(emptyList())
        if (f.length() > (if (alsoLarge) MAX_CRC else SMALL)) return emptyList()
        val crc = CRC32()
        val buf = ByteArray(1 shl 16)
        f.inputStream().use { input -> while (true) { val n = input.read(buf); if (n <= 0) break; crc.update(buf, 0, n) } }
        return listOf("%08X".format(crc.value))
    }

    /** Serials / game ids for [systemId], in the forms the DATs use (see LibretroDb.serialKeys). */
    fun serials(f: File, systemId: String): List<String> = runCatching {
        when (systemId) {
            "gba" -> listOfNotNull(ascii(head(f, 0xB0), 0xAC, 4))
            "nds" -> listOfNotNull(ascii(head(f, 0x10), 0x0C, 4))
            "n64" -> listOfNotNull(n64Code(head(f, 0x40)))
            "3ds" -> listOfNotNull(ctrCode(f))
            "gc", "wii" -> listOfNotNull(discId6(f)?.take(4))
            "psx", "ps2" -> listOfNotNull(Iso.of(f)?.use { it.readFile("SYSTEM.CNF") }?.let { bootSerial(String(it, Charsets.ISO_8859_1)) }
                ?: pbpSerial(f))
            "psp" -> listOfNotNull(Iso.of(f)?.use { iso ->
                iso.readFile("UMD_DATA.BIN")?.let { sonyId(String(it, Charsets.ISO_8859_1)) }
                    ?: iso.readFile("PSP_GAME/PARAM.SFO")?.let { Sfo.get(it, "DISC_ID") }
            })
            "ps3" -> listOfNotNull(Ps3Games.titleId(f) ?: Iso.of(f)?.use { it.readFile("PS3_DISC.SFB") }
                ?.let { sonyId(String(it, Charsets.ISO_8859_1)) })
            "saturn" -> listOfNotNull(Iso.of(f)?.use { it.sector0() }?.let { segaProduct(it, "SEGA SEGASATURN", 0x20) })
            "dreamcast" -> listOfNotNull(Iso.of(gdiDataTrack(f) ?: f)?.use { it.sector0() }?.let { segaProduct(it, "SEGA SEGAKATANA", 0x40) })
            "segacd" -> listOfNotNull(Iso.of(f)?.use { it.sector0() }?.let { megaCdSerial(it) })
            else -> emptyList()
        }
    }.getOrDefault(emptyList())

    // ---- cartridges --------------------------------------------------------------------

    /** First [n] bytes of the file, or of the biggest file in a zip. */
    fun head(f: File, n: Int): ByteArray {
        if (f.extension.equals("zip", true)) {
            ZipFile(f).use { z ->
                val e = z.entries().asSequence().filter { !it.isDirectory }.maxByOrNull { it.size } ?: return ByteArray(0)
                z.getInputStream(e).use { return it.readNBytesCompat(n) }
            }
        }
        f.inputStream().use { return it.readNBytesCompat(n) }
    }

    private fun java.io.InputStream.readNBytesCompat(n: Int): ByteArray {
        val out = ByteArray(n); var off = 0
        while (off < n) { val r = read(out, off, n - off); if (r <= 0) break; off += r }
        return if (off == n) out else out.copyOf(off)
    }

    private fun ascii(b: ByteArray, off: Int, len: Int): String? {
        if (b.size < off + len) return null
        val s = String(b, off, len, Charsets.ISO_8859_1)
        return s.takeIf { it.all { c -> c in 'A'..'Z' || c in '0'..'9' } }
    }

    /** N64 game code ("NSME"), whatever the dump's byte order (.z64 / .v64 / .n64). */
    fun n64Code(h: ByteArray): String? {
        if (h.size < 0x40) return null
        val b = h.copyOf()
        when {
            b[0] == 0x80.toByte() && b[1] == 0x37.toByte() -> {}                                   // big endian (z64)
            b[0] == 0x37.toByte() && b[1] == 0x80.toByte() -> for (i in 0 until 0x40 step 2) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t }
            b[0] == 0x40.toByte() && b[1] == 0x12.toByte() -> for (i in 0 until 0x40 step 4) {    // little endian (n64)
                val t0 = b[i]; val t1 = b[i + 1]; b[i] = b[i + 3]; b[i + 1] = b[i + 2]; b[i + 2] = t1; b[i + 3] = t0
            }
            else -> return null
        }
        return ascii(b, 0x3B, 4)
    }

    private val ctr = Regex("""(?:CTR|KTR)-[PNUM]-[A-Z0-9]{4}""")

    /** 3DS product code ("CTR-P-AREE") from the NCCH header of a .3ds / .cci / .cia. */
    private fun ctrCode(f: File): String? {
        val b = head(f, 4 shl 20)
        return ctr.find(String(b, Charsets.ISO_8859_1))?.value
    }

    // ---- GameCube / Wii ----------------------------------------------------------------

    /** The 6 character disc id ("GALE01") of a GameCube / Wii image (.iso .gcm .wbfs .rvz .wia .ciso). */
    fun discId6(f: File): String? {
        if (!f.isFile) return null
        RandomAccessFile(f, "r").use { r ->
            val magic = ByteArray(4).also { r.readFully(it) }
            val m = String(magic, Charsets.ISO_8859_1)
            val off = when {
                m == "WBFS" -> { r.seek(8); 1L shl (r.read() and 0xff) }                // disc header in the second hd sector
                m == "RVZ\u0001" || m == "WIA\u0001" -> 0x58L                          // header 2's copy of the disc header
                m == "CISO" -> 0x8000L
                else -> 0L
            }
            if (off + 6 > r.length()) return null
            r.seek(off)
            val id = ByteArray(6).also { r.readFully(it) }
            return String(id, Charsets.ISO_8859_1).takeIf { s -> s.all { it in 'A'..'Z' || it in '0'..'9' } }
        }
    }

    // ---- Sony ----------------------------------------------------------------------------

    private val bootLine = Regex("""BOOT2?\s*=\s*cdrom0?:\\*([A-Z]{4})[_-](\d{3})\.?(\d{2})""", RegexOption.IGNORE_CASE)
    private val sonyIdRe = Regex("""([A-Z]{4})-?(\d{5})""")

    /** "BOOT = cdrom:\SCUS_941.63;1" -> "SCUS94163". */
    fun bootSerial(systemCnf: String): String? = bootLine.find(systemCnf)?.let { (it.groupValues[1] + it.groupValues[2] + it.groupValues[3]).uppercase() }

    fun sonyId(text: String): String? = sonyIdRe.find(text.uppercase())?.let { it.groupValues[1] + it.groupValues[2] }

    /** PS1 games converted for PSP (EBOOT.PBP): DISC_ID in the embedded PARAM.SFO. */
    private fun pbpSerial(f: File): String? {
        if (!f.extension.equals("pbp", true)) return null
        RandomAccessFile(f, "r").use { r ->
            val h = ByteArray(0x28).also { r.readFully(it) }
            if (h[0] != 0.toByte() || h[1] != 'P'.code.toByte()) return null
            val sfoAt = le32(h, 0x08); val iconAt = le32(h, 0x0C)
            if (sfoAt <= 0 || iconAt <= sfoAt || iconAt - sfoAt > 65536) return null
            r.seek(sfoAt.toLong())
            val sfo = ByteArray(iconAt - sfoAt).also { r.readFully(it) }
            return Sfo.get(sfo, "DISC_ID")?.let { sonyId(it) }
        }
    }

    /** PARAM.SFO reader (string values only). */
    object Sfo {
        fun get(b: ByteArray, key: String): String? {
            if (b.size < 0x14 || String(b, 0, 4, Charsets.ISO_8859_1) != "\u0000PSF") return null
            val keyTable = le32(b, 0x08); val dataTable = le32(b, 0x0C); val count = le32(b, 0x10)
            for (i in 0 until count) {
                val e = 0x14 + i * 16
                if (e + 16 > b.size) break
                val kOff = keyTable + (le16(b, e))
                val k = cstr(b, kOff) ?: continue
                if (k != key) continue
                val len = le32(b, e + 4); val dOff = dataTable + le32(b, e + 12)
                if (dOff + len > b.size) return null
                return String(b, dOff, len, Charsets.UTF_8).trimEnd('\u0000')
            }
            return null
        }
        private fun cstr(b: ByteArray, off: Int): String? {
            if (off !in b.indices) return null
            var end = off; while (end < b.size && b[end] != 0.toByte()) end++
            return String(b, off, end - off, Charsets.ISO_8859_1)
        }
    }

    // ---- Sega ----------------------------------------------------------------------------

    /** Product number [at] bytes after the "SEGA SEGASATURN" / "SEGA SEGAKATANA" system id. */
    fun segaProduct(sector0: ByteArray, id: String, at: Int): String? {
        val s = String(sector0, Charsets.ISO_8859_1)
        val i = s.indexOf(id).takeIf { it >= 0 } ?: return null
        if (i + at + 10 > s.length) return null
        return s.substring(i + at, i + at + 10).trim().takeIf { it.isNotEmpty() && it.all { c -> c.isLetterOrDigit() || c == '-' } }
    }

    private val megaCd = Regex("""(?:GM|AI|OS)\s+([A-Z]{1,4}-?\s?\d{3,6}(?:-\d{2})?)""")

    /** Mega-CD: the serial in the Mega Drive style header ("GM T-93175 -00" -> "T-93175"). */
    fun megaCdSerial(sector0: ByteArray): String? {
        val s = String(sector0, Charsets.ISO_8859_1)
        if (!s.contains("SEGADISCSYSTEM") && !s.contains("SEGABOOTDISC")) return null
        // Only a version suffix ("T-93175-00") goes; "T-06204" keeps its leading zero.
        return megaCd.find(s)?.groupValues?.get(1)?.replace(" ", "")?.replace(Regex("-0\\d$"), "")
    }

    /** Dreamcast .gdi: the file of track 3 (the first data track, which holds IP.BIN). */
    private fun gdiDataTrack(f: File): File? {
        if (!f.extension.equals("gdi", true)) return null
        val line = f.readLines().drop(1).map { it.trim().split(Regex("\\s+")) }.firstOrNull { it.size >= 5 && it[0] == "3" } ?: return null
        val name = line.drop(4).dropLast(1).joinToString(" ").trim('"').ifEmpty { line[4] }
        return File(f.parentFile, name).takeIf { it.isFile }
    }

    // ---- ISO 9660 ------------------------------------------------------------------------

    /**
     * Minimal ISO 9660 reader for disc images with 2048 byte sectors (.iso) or raw 2352
     * byte sectors (.bin, mode 1 or mode 2). Reads files from the root directory and one
     * level below; enough for SYSTEM.CNF, UMD_DATA.BIN, PSP_GAME/PARAM.SFO and the like.
     */
    class Iso private constructor(private val r: RandomAccessFile, private val sectorSize: Int, private val dataOffset: Int) : AutoCloseable {
        override fun close() = r.close()

        fun sector(lba: Long, count: Int = 1): ByteArray {
            val out = ByteArray(2048 * count)
            for (i in 0 until count) {
                val pos = (lba + i) * sectorSize + dataOffset
                if (pos + 2048 > r.length()) return out.copyOf(i * 2048)
                r.seek(pos); r.readFully(out, i * 2048, 2048)
            }
            return out
        }

        fun sector0(): ByteArray = sector(0)

        fun readFile(path: String): ByteArray? {
            val pvd = sector(16)
            if (pvd.size < 2048 || String(pvd, 1, 5, Charsets.ISO_8859_1) != "CD001") return null
            var dirLba = le32(pvd, 156 + 2).toLong(); var dirLen = le32(pvd, 156 + 10)
            val parts = path.split('/')
            for ((i, part) in parts.withIndex()) {
                val entry = find(dirLba, dirLen, part) ?: return null
                if (i == parts.lastIndex) {
                    val len = entry.second.coerceAtMost(1 shl 20)
                    return sector(entry.first, (len + 2047) / 2048).copyOf(len)
                }
                dirLba = entry.first; dirLen = entry.second
            }
            return null
        }

        private fun find(lba: Long, len: Int, name: String): Pair<Long, Int>? {
            val data = sector(lba, ((len.coerceAtMost(64 * 2048)) + 2047) / 2048)
            var p = 0
            while (p < data.size) {
                val recLen = data[p].toInt() and 0xff
                if (recLen == 0) { p = (p / 2048 + 1) * 2048; continue }
                if (p + 33 > data.size) break
                val nameLen = data[p + 32].toInt() and 0xff
                if (p + 33 + nameLen > data.size) break
                val n = String(data, p + 33, nameLen, Charsets.ISO_8859_1).substringBefore(';')
                if (n.equals(name, true)) return le32(data, p + 2).toLong() to le32(data, p + 10)
                p += recLen
            }
            return null
        }

        companion object {
            /** Opens a disc image (or the first track a .cue / .m3u names); null for anything else. */
            fun of(f: File): Iso? {
                val file = when (f.extension.lowercase()) {
                    "cue" -> cueTrack(f)
                    "m3u" -> f.readLines().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                        ?.let { File(f.parentFile, it) }?.let { if (it.extension.equals("cue", true)) cueTrack(it) else it }
                    "iso", "bin", "img" -> f
                    else -> null
                } ?: return null
                if (!file.isFile) return null
                val r = RandomAccessFile(file, "r")
                fun at(pos: Long, s: String): Boolean {
                    if (pos + s.length > r.length()) return false
                    r.seek(pos); val b = ByteArray(s.length); r.readFully(b)
                    return String(b, Charsets.ISO_8859_1) == s
                }
                return when {
                    at(16L * 2048 + 1, "CD001") -> Iso(r, 2048, 0)
                    at(16L * 2352 + 16 + 1, "CD001") -> Iso(r, 2352, 16)           // mode 1
                    at(16L * 2352 + 24 + 1, "CD001") -> Iso(r, 2352, 24)           // mode 2 (PS1, PS2 CD)
                    at(0, "SEGA") -> Iso(r, 2048, 0)                                 // Sega discs: system area only
                    at(16, "SEGA") -> Iso(r, 2352, 16)
                    else -> { r.close(); null }
                }
            }

            private fun cueTrack(cue: File): File? = cue.readLines()
                .firstNotNullOfOrNull { Regex("""FILE\s+"?(.+?)"?\s+\w+\s*$""", RegexOption.IGNORE_CASE).find(it.trim())?.groupValues?.get(1) }
                ?.let { File(cue.parentFile, it) }
        }
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8)
}
