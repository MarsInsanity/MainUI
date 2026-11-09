package io.github.marsinsanity.mainui.scraper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.store.Http
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Box art from sources that need no account:
 *  - GameTDB (GameCube / Wii): the cover for the disc id read from the image, the same
 *    URL Dolphin uses.
 *  - libretro thumbnails: the dump is identified exactly through libretro-database
 *    (CRC, serial or dump name -> canonical No-Intro / Redump / FBNeo name), and only
 *    when nothing identifies it, matched by title with [BoxArtMatch]'s strict rules.
 */
object FreeArt {
    const val GAMETDB = "gametdb"
    const val LIBRETRO = "libretro"
    private const val TAG = "MainUI.FreeArt"
    private const val DAT_MAX_AGE = 30L * 24 * 3600 * 1000
    private const val INDEX_MAX_AGE = 7L * 24 * 3600 * 1000

    /**
     * Debug builds: `setprop debug.mainui.artbase <url>` serves everything from a test server:
     * <url>/dats/<dir>/<name>.dat, <url>/thumbs/<set>/Named_Boxarts/, <url>/gametdb/wii/cover/...
     */
    private val testBase: String? by lazy {
        if (!BuildConfig.DEBUG) null else runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.mainui.artbase")).inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.startsWith("http") }?.trimEnd('/')
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun datUrl(src: LibretroDb.Source) = testBase?.let { "$it/dats/${src.dir}/${enc(src.dat)}.dat" }
        ?: "https://raw.githubusercontent.com/libretro/libretro-database/master/metadat/${src.dir}/${enc(src.dat)}.dat"
    private fun thumbDir(set: String) = testBase?.let { "$it/thumbs/${enc(set)}/Named_Boxarts/" }
        ?: "https://thumbnails.libretro.com/${enc(set)}/Named_Boxarts/"
    private fun thumbGithub(set: String, name: String) =
        "https://raw.githubusercontent.com/libretro-thumbnails/${set.replace(' ', '_')}/HEAD/Named_Boxarts/${enc(name)}.png"
    private fun gametdb(region: String, id: String) = (testBase?.let { "$it/gametdb" } ?: "https://art.gametdb.com") + "/wii/cover/$region/$id.png"

    fun supports(sources: List<String>, systemId: String) =
        (GAMETDB in sources && GameTdb.supports(systemId)) || (LIBRETRO in sources && LibretroDb.supports(systemId))

    /** Per scraping run: DATs and thumbnail lists are loaded once per system. */
    class Session(val sources: List<String>, val prefs: List<String>) {
        internal val dats = ConcurrentHashMap<String, Any>()
        internal val indexes = ConcurrentHashMap<String, Any>()
    }

    private object None

    /**
     * Finds box art for [file] and saves it to [dest] as PNG, at most [maxWidth] wide (0 = as is).
     * Returns the source's name, or null when no source has this game.
     */
    fun find(s: Session, systemId: String, file: File, dest: File, maxWidth: Int, crc: String? = null): String? {
        for (src in s.sources) {
            when (src) {
                GAMETDB -> if (GameTdb.supports(systemId)) {
                    val id = runCatching { RomIdent.discId6(file) }.getOrNull() ?: continue
                    for (region in GameTdb.regions(id, s.prefs)) if (saveImage(gametdb(region, id), dest, maxWidth)) return "GameTDB"
                }
                LIBRETRO -> {
                    val lsrc = LibretroDb.SYSTEMS[systemId] ?: continue
                    val identified = identify(s, lsrc, systemId, file, crc)
                    val name = BoxArtMatch.pick(identified, file.nameWithoutExtension, index(s, lsrc.thumbs), s.prefs) ?: continue
                    if (saveImage(thumbDir(lsrc.thumbs) + enc(name) + ".png", dest, maxWidth) ||
                        (testBase == null && saveImage(thumbGithub(lsrc.thumbs, name), dest, maxWidth))) return "libretro"
                }
            }
        }
        return null
    }

    /** Canonical names for the dump: by CRC, then serial, then its file name in the DAT. */
    fun identify(s: Session, src: LibretroDb.Source, systemId: String, file: File, crcHint: String?): List<String> {
        val dat = dat(s, src) ?: return emptyList()
        val names = LinkedHashSet<String>()
        val serials = RomIdent.serials(file, systemId)
        val crcs = listOfNotNull(crcHint?.uppercase()) + runCatching { RomIdent.crcs(file, alsoLarge = serials.isEmpty()) }.getOrDefault(emptyList())
        crcs.forEach { names += dat.crc(it) }
        serials.forEach { names += dat.serial(it) }
        names += dat.file(file.name)
        if (file.extension.equals("zip", true) || src.dir == "fbneo-split") names += dat.file(file.nameWithoutExtension)
        return names.toList()
    }

    fun dat(s: Session, src: LibretroDb.Source): LibretroDb.Dat? = s.dats.computeIfAbsent(src.dat) {
        val f = File(MainUI.storage.dataDir("cache/libretro/dat"), "${src.dat}.dat")
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > DAT_MAX_AGE) {
            runCatching { Http.download(datUrl(src), f) }.onFailure { Log.w(TAG, "DAT ${src.dat}: ${it.message}") }
        }
        if (f.isFile) runCatching { LibretroDb.parse(f.readText()) }.getOrNull() ?: None else None
    } as? LibretroDb.Dat

    /** Names (without .png) of the box art the thumbnail set has; null if the list can't be read. */
    @Suppress("UNCHECKED_CAST")
    fun index(s: Session, set: String): Set<String>? = s.indexes.computeIfAbsent(set) {
        val f = File(MainUI.storage.dataDir("cache/libretro/index"), "$set.txt")
        if (!f.isFile || System.currentTimeMillis() - f.lastModified() > INDEX_MAX_AGE) {
            val names = runCatching { parseListing(Http.text(thumbDir(set))) }.getOrNull()?.takeIf { it.isNotEmpty() }
                ?: if (testBase == null) runCatching { githubTree(set) }.getOrNull() else null
            if (!names.isNullOrEmpty()) runCatching { f.writeText(names.joinToString("\n")) }
            else Log.w(TAG, "no thumbnail list for $set")
        }
        if (f.isFile) f.readLines().filter { it.isNotEmpty() }.toHashSet() else None
    } as? Set<String>

    /** Thumbnail names from a web server's folder listing (links to *.png). */
    fun parseListing(html: String): Set<String> =
        Regex("""href="([^"?#]+)\.png"""", RegexOption.IGNORE_CASE).findAll(html)
            .map { URLDecoder.decode(it.groupValues[1].replace("+", "%2B"), "UTF-8").substringAfterLast('/') }
            .toHashSet()

    /** The same list from the set's GitHub repository (libretro-thumbnails/<set>). */
    private fun githubTree(set: String): Set<String> {
        val json = JSONObject(Http.text("https://api.github.com/repos/libretro-thumbnails/${set.replace(' ', '_')}/git/trees/HEAD?recursive=1"))
        val tree = json.getJSONArray("tree")
        return (0 until tree.length()).map { tree.getJSONObject(it).optString("path") }
            .filter { it.startsWith("Named_Boxarts/") && it.endsWith(".png") }
            .map { it.removePrefix("Named_Boxarts/").removeSuffix(".png") }.toHashSet()
    }

    /** Downloads an image and saves it as PNG, scaled down to [maxWidth]. False when it isn't there. */
    private fun saveImage(url: String, dest: File, maxWidth: Int, depth: Int = 0): Boolean {
        val tmp = File(dest.parentFile, dest.name + ".dl")
        return try {
            dest.parentFile?.mkdirs()
            Http.download(url, tmp)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(tmp.path, bounds)
            if (bounds.outWidth <= 0) {
                // libretro-thumbnails stores duplicates as symlinks; GitHub serves those as the target's path.
                val link = if (tmp.length() in 1..512) tmp.readText().trim() else ""
                return depth < 2 && link.endsWith(".png", true) && '\n' !in link &&
                    saveImage(java.net.URL(java.net.URL(url), link.split('/').joinToString("/") { enc(it) }).toString(), dest, maxWidth, depth + 1)
            }
            if (maxWidth in 1 until bounds.outWidth || !url.endsWith(".png", true)) {
                val bmp = BitmapFactory.decodeFile(tmp.path) ?: return false
                val out = if (maxWidth in 1 until bmp.width)
                    Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height.toLong() * maxWidth / bmp.width).toInt().coerceAtLeast(1), true)
                else bmp
                dest.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } else {
                if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true) }
            }
            true
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    /** GameTDB covers for GameCube and Wii discs, by disc id. */
    object GameTdb {
        fun supports(systemId: String) = systemId == "gc" || systemId == "wii"

        /**
         * Cover regions to try for a disc id: the one its 4th character means (as Dolphin
         * picks it: E -> US, J -> JA, W -> ZH, K -> KO, PAL letters -> their language),
         * then English, US and Japanese covers.
         */
        fun regions(id: String, prefs: List<String>): List<String> {
            val own = when (id.getOrNull(3)) {
                'E', 'N' -> "US"
                'J' -> "JA"
                'W' -> "ZH"
                'K', 'Q', 'T' -> "KO"
                'D' -> "DE"; 'F' -> "FR"; 'S' -> "ES"; 'I' -> "IT"; 'H' -> "NL"
                'P', 'X', 'Y', 'Z', 'U', 'V' -> "EN"
                else -> null
            }
            val pref = prefs.mapNotNull { when (it) { "USA" -> "US"; "Europe" -> "EN"; "Japan" -> "JA"; else -> null } }
            return (listOfNotNull(own) + pref + listOf("EN", "US", "JA")).distinct()
        }
    }
}
