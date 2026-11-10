package io.github.marsinsanity.mainui.scraper

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.allGroups
import io.github.marsinsanity.mainui.store.Http
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Box art from sources that need no account:
 *  - GameTDB (GameCube, Wii, DS, 3DS, PS3): the cover for the game id read from the disc /
 *    cartridge header (or PS3 title id). GameCube / Wii use the front of the full cover
 *    (3x the small cover's resolution), the URLs Dolphin uses.
 *  - libretro thumbnails: the dump is identified exactly through libretro-database
 *    (CRC, serial or dump name -> canonical No-Intro / Redump / FBNeo name), and only
 *    when nothing identifies it, matched by title with [BoxArtMatch]'s strict rules.
 *  - LaunchBox Games Database: matched by title (the canonical name when the dump was
 *    identified) against names and alternate names; ambiguous titles are skipped.
 *  - TheGamesDB: matched by disc serial, else the same way by title, the region deciding
 *    between its per-region entries.
 */
object FreeArt {
    const val GAMETDB = "gametdb"
    const val LIBRETRO = "libretro"
    const val LAUNCHBOX = "launchbox"
    const val THEGAMESDB = "thegamesdb"
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
    private val gametdbBase get() = testBase?.let { "$it/gametdb" } ?: "https://art.gametdb.com"
    private fun launchBoxImage(file: String) = testBase?.let { "$it/launchbox-images/$file" } ?: "https://images.launchbox-app.com/$file"
    private val launchBoxMetadata get() = testBase?.let { "$it/launchbox/Metadata.zip" } ?: "https://gamesdb.launchbox-app.com/Metadata.zip"
    private fun tgdbImage(size: String, file: String) = testBase?.let { "$it/thegamesdb-images/$size/$file" } ?: "https://cdn.thegamesdb.net/images/$size/$file"
    private val tgdbDump get() = testBase?.let { "$it/thegamesdb/database-latest.json" } ?: "https://cdn.thegamesdb.net/json/database-latest.json"

    fun supports(sources: List<String>, systemId: String) =
        (GAMETDB in sources && GameTdb.supports(systemId)) || (LIBRETRO in sources && LibretroDb.supports(systemId)) ||
            (LAUNCHBOX in sources && LaunchBoxDb.supports(systemId)) || (THEGAMESDB in sources && TheGamesDb.supports(systemId))

    /** Per scraping run: DATs, thumbnail lists and LaunchBox indexes are loaded once per system. */
    class Session(val sources: List<String>, val prefs: List<String>) {
        internal val dats = ConcurrentHashMap<String, Any>()
        internal val indexes = ConcurrentHashMap<String, Any>()
        internal val lookups = ConcurrentHashMap<String, Any>()
        internal val identified = ConcurrentHashMap<String, List<String>>()
        /** Per thumbnail set: title -> names (BoxArtMatch.titleIndex). */
        internal val titles = ConcurrentHashMap<String, Map<String, List<String>>>()
    }

    private object None

    /**
     * Finds box art for [file] and saves it to [dest] as PNG, at most [maxWidth] wide (0 = as is).
     * Returns the source's name, or null when no source has this game.
     */
    fun find(s: Session, systemId: String, file: File, dest: File, maxWidth: Int, crc: String? = null): String? {
        // Canonical names, once per file and DAT (libretro, LaunchBox and TheGamesDB use them).
        fun identified(src: LibretroDb.Source): List<String> = s.identified.computeIfAbsent("${src.dat}\u0000${file.path}") {
            identify(s, src, systemId, file, crc)
        }
        fun identified(): List<String> = LibretroDb.sources(systemId).flatMap { identified(it) }.distinct()
        for (src in s.sources) {
            when (src) {
                GAMETDB -> if (GameTdb.supports(systemId)) {
                    val id = runCatching { GameTdb.id(file, systemId) }.getOrNull() ?: continue
                    for ((url, wrap) in GameTdb.urls(gametdbBase, systemId, id, maxWidth, s.prefs))
                        if (saveImage(url, dest, maxWidth, frontOfWrap = wrap)) return "GameTDB"
                }
                LIBRETRO -> for (lsrc in LibretroDb.sources(systemId)) {
                    // Arcade: the FBNeo set, then the MAME set (or the other way round in MAME folders).
                    // Its own DAT's names first; only then the other DATs' (a MAME name may be filed in
                    // another set too), so a second DAT is only read for games the first doesn't cover.
                    val available = index(s, lsrc.thumbs)
                    val titles = available?.let { a -> s.titles.computeIfAbsent(lsrc.thumbs) { BoxArtMatch.titleIndex(a) } }
                    val name = BoxArtMatch.pick(identified(lsrc), file.nameWithoutExtension, available, s.prefs, titles)
                        ?: BoxArtMatch.pick(identified(), file.nameWithoutExtension, available, s.prefs, titles)
                        ?: continue
                    if (saveImage(thumbDir(lsrc.thumbs) + enc(name) + ".png", dest, maxWidth) ||
                        (testBase == null && saveImage(thumbGithub(lsrc.thumbs, name), dest, maxWidth))) return "libretro"
                }
                LAUNCHBOX -> {
                    val lookup = launchBoxLookup(s, systemId) ?: continue
                    // The identified (canonical) names say exactly which game (arcade sets have a few:
                    // "Marvel vs. Capcom 2" / "Marvel Vs. Capcom 2 New Age of Heroes"); else the file name.
                    var source = file.nameWithoutExtension
                    val game = titlesToTry(identified(), file).firstNotNullOfOrNull { n -> lookup.find(n)?.also { source = n } } ?: continue
                    val box = LaunchBoxDb.pickBox(game, source, s.prefs) ?: continue
                    if (saveImage(launchBoxImage(box), dest, maxWidth)) return "LaunchBox"
                }
                THEGAMESDB -> {
                    val lookup = tgdbLookup(s, systemId) ?: continue
                    // The disc's serial says exactly which game; else the canonical name, else the file name.
                    val game = lookup.bySerial(runCatching { RomIdent.serials(file, systemId) }.getOrDefault(emptyList()))
                        ?: titlesToTry(identified(), file).firstNotNullOfOrNull { lookup.find(it, s.prefs) }
                        ?: continue
                    if (saveImage(tgdbImage(TheGamesDb.imageSize(maxWidth), game.file), dest, maxWidth)) return "TheGamesDB"
                }
            }
        }
        return null
    }

    /** Names to look a game up by title with: the identified ones (a few at most), then the file name. */
    private fun titlesToTry(identified: List<String>, file: File): List<String> =
        (identified.take(4) + file.nameWithoutExtension).distinct()

    // ---- TheGamesDB ----------------------------------------------------------------------------

    private val tgdbDir get() = MainUI.storage.dataDir("cache/thegamesdb")

    private fun tgdbLookup(s: Session, systemId: String): TheGamesDb.Lookup? = s.lookups.computeIfAbsent("tgdb:$systemId") {
        val dir = File(tgdbDir, "index")
        val games = TheGamesDb.PLATFORMS[systemId].orEmpty().flatMap { p ->
            val f = File(dir, "$p.tsv")
            if (f.isFile) f.bufferedReader().useLines { TheGamesDb.parseIndex(it) } else emptyList()
        }
        if (games.isEmpty()) None else TheGamesDb.Lookup(games)
    } as? TheGamesDb.Lookup

    /**
     * Makes sure the TheGamesDB indexes exist and are at most a month old: streams the dump
     * (compressed on the wire, about 36 MB; skipped when unchanged) straight into the indexes,
     * nothing of it is kept. Returns false when there is no index to use.
     */
    fun ensureTheGamesDb(progress: (String) -> Unit, cancelled: () -> Boolean): Boolean {
        val dir = tgdbDir
        val index = File(dir, "index")
        val stamp = File(dir, "dump.lastmodified")
        val have = index.isDirectory && (index.list()?.isNotEmpty() == true)
        if (have && System.currentTimeMillis() - stamp.lastModified() < LAUNCHBOX_MAX_AGE) return true
        val tmp = File(dir, "index.new").apply { deleteRecursively() }
        return try {
            progress("Downloading TheGamesDB...")
            val c = (java.net.URL(tgdbDump).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 20000; readTimeout = 60000
                setRequestProperty("User-Agent", "MainUI-Android/${BuildConfig.VERSION_NAME}")
                if (have) stamp.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null }?.let { setRequestProperty("If-Modified-Since", it) }
            }
            try {
                if (c.responseCode == 304) { stamp.setLastModified(System.currentTimeMillis()); return true }
                if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                // Android's HttpURLConnection asks for gzip and unpacks it by itself.
                java.io.InputStreamReader(c.inputStream, Charsets.UTF_8).use { r ->
                    val counts = TheGamesDb.buildIndex(r, TheGamesDb.PLATFORMS.values.flatten().toSet(), tmp) { read ->
                        if (cancelled()) throw Http.Cancelled()
                        progress("Reading TheGamesDB: ${read shr 20} MB")
                    }
                    Log.i(TAG, "TheGamesDB index: ${counts.values.sum()} games on ${counts.size} platforms")
                }
                index.deleteRecursively()
                if (!tmp.renameTo(index)) { tmp.copyRecursively(index, true); tmp.deleteRecursively() }
                stamp.writeText(c.getHeaderField("Last-Modified") ?: "")
                true
            } finally {
                c.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "TheGamesDB: ${e.message}")
            tmp.deleteRecursively()
            have
        }
    }

    // ---- LaunchBox -----------------------------------------------------------------------------

    private const val LAUNCHBOX_MAX_AGE = 30L * 24 * 3600 * 1000
    private val launchBoxDir get() = MainUI.storage.dataDir("cache/launchbox")

    /** The platform indexes for [systemId] as one lookup; null when there is no index (yet). */
    private fun launchBoxLookup(s: Session, systemId: String): LaunchBoxDb.Lookup? = s.lookups.computeIfAbsent(systemId) {
        val dir = File(launchBoxDir, "index")
        val platforms = LaunchBoxDb.PLATFORMS[systemId].orEmpty().map { p ->
            val f = File(dir, LaunchBoxDb.fileName(p))
            if (f.isFile) f.bufferedReader().useLines { LaunchBoxDb.parseIndex(it) } else emptyList()
        }.filter { it.isNotEmpty() }
        if (platforms.isEmpty()) None else LaunchBoxDb.Lookup(platforms)
    } as? LaunchBoxDb.Lookup

    /**
     * Makes sure the LaunchBox indexes exist and are at most a month old: downloads
     * Metadata.zip (about 110 MB; skipped when unchanged) and reads it into per-platform
     * indexes, then deletes it. [progress] gets messages for the UI. False when it failed
     * (or was [cancelled]) and there is no older index to use.
     */
    fun ensureLaunchBox(progress: (String) -> Unit, cancelled: () -> Boolean): Boolean {
        val dir = launchBoxDir
        val index = File(dir, "index")
        val stamp = File(dir, "metadata.lastmodified")
        val have = index.isDirectory && (index.list()?.isNotEmpty() == true)
        if (have && System.currentTimeMillis() - stamp.lastModified() < LAUNCHBOX_MAX_AGE) return true
        val zip = File(dir, "Metadata.zip")
        return try {
            progress("Downloading the LaunchBox database...")
            val info = Http.downloadFast(launchBoxMetadata, zip,
                ifModifiedSince = if (have) stamp.takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } else null) { done, total ->
                if (cancelled()) throw Http.Cancelled()
                progress("Downloading the LaunchBox database: ${done shr 20} / ${if (total > 0) total shr 20 else "?"} MB")
            }
            if (info.notModified) { stamp.setLastModified(System.currentTimeMillis()); return true }
            if (cancelled()) return have
            val tmp = File(dir, "index.new").apply { deleteRecursively() }
            java.util.zip.ZipFile(zip).use { z ->
                val e = z.getEntry("Metadata.xml") ?: throw java.io.IOException("Metadata.xml missing")
                val size = e.size.coerceAtLeast(1)
                java.io.BufferedReader(java.io.InputStreamReader(z.getInputStream(e), Charsets.UTF_8), 1 shl 16).use { r ->
                    val counts = LaunchBoxDb.buildIndex(r, LaunchBoxDb.PLATFORMS.values.flatten().toSet(), tmp) { read ->
                        if (cancelled()) throw Http.Cancelled()
                        progress("Reading the LaunchBox database: ${(read * 100 / size).coerceAtMost(99)}%")
                    }
                    Log.i(TAG, "LaunchBox index: ${counts.values.sum()} games on ${counts.size} platforms")
                }
            }
            index.deleteRecursively()
            if (!tmp.renameTo(index)) { tmp.copyRecursively(index, true); tmp.deleteRecursively() }
            stamp.writeText(info.lastModified ?: "")
            true
        } catch (e: Exception) {
            Log.w(TAG, "LaunchBox database: ${e.message}")
            File(dir, "index.new").deleteRecursively()
            have
        } finally {
            zip.delete()
            File(dir, "Metadata.zip.part").delete()
        }
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
        if (file.extension.equals("zip", true) || src.dir in LibretroDb.ARCADE_DIRS) names += dat.file(file.nameWithoutExtension)
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
        Regex("""href="([^"?#]+)\.png"""", RegexOption.IGNORE_CASE).allGroups(html)
            .map { URLDecoder.decode(it[1].replace("+", "%2B"), "UTF-8").substringAfterLast('/') }
            .toHashSet()

    /** The same list from the set's GitHub repository (libretro-thumbnails/<set>). */
    private fun githubTree(set: String): Set<String> {
        val json = JSONObject(Http.text("https://api.github.com/repos/libretro-thumbnails/${set.replace(' ', '_')}/git/trees/HEAD?recursive=1"))
        val tree = json.getJSONArray("tree")
        return (0 until tree.length()).map { tree.getJSONObject(it).optString("path") }
            .filter { it.startsWith("Named_Boxarts/") && it.endsWith(".png") }
            .map { it.removePrefix("Named_Boxarts/").removeSuffix(".png") }.toHashSet()
    }

    /**
     * Downloads an image and saves it as PNG, scaled down to [maxWidth]. False when it isn't there.
     * [frontOfWrap]: the image is a full cover (back, spine, front): keep the front, the right
     * part as wide as a case front is for that height (135 x 190 mm).
     */
    private fun saveImage(url: String, dest: File, maxWidth: Int, depth: Int = 0, frontOfWrap: Boolean = false): Boolean {
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
            if (frontOfWrap && bounds.outWidth < bounds.outHeight * 1.2) return false   // not a full cover
            if (frontOfWrap || maxWidth in 1 until bounds.outWidth || !url.endsWith(".png", true)) {
                var bmp = BitmapFactory.decodeFile(tmp.path) ?: return false
                if (frontOfWrap) {
                    val fw = (bmp.height * 135 / 190).coerceAtMost(bmp.width)
                    bmp = Bitmap.createBitmap(bmp, bmp.width - fw, 0, fw, bmp.height)
                }
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

    /** GameTDB covers by game id: GameCube / Wii discs, DS / 3DS cartridges, PS3 title ids. */
    object GameTdb {
        fun supports(systemId: String) = systemId in setOf("gc", "wii", "nds", "3ds", "ps3")

        /** GameTDB's id for the game: 6 character disc id, 4 character game code, or PS3 title id. */
        fun id(file: File, systemId: String): String? = when (systemId) {
            "gc", "wii" -> RomIdent.discId6(file)
            "nds" -> RomIdent.serials(file, "nds").firstOrNull()
            "3ds" -> RomIdent.serials(file, "3ds").firstOrNull()?.takeLast(4)
            "ps3" -> io.github.marsinsanity.mainui.emu.Ps3Games.titleId(file)
            else -> null
        }

        /**
         * Image URLs to try, best first, with whether each is a full cover to cut the front from.
         * GameCube / Wii: the full HQ cover (1024x680; its front is 483x680), then the 160x224 front.
         * DS / 3DS / PS3: coverHQ (about 800 px) when a large image is wanted, coverM (about 400), cover (160).
         */
        fun urls(base: String, systemId: String, id: String, maxWidth: Int, prefs: List<String>): List<Pair<String, Boolean>> {
            val regions = regions(id, prefs, ps3 = systemId == "ps3")
            return when (systemId) {
                "gc", "wii" -> regions.map { "$base/wii/coverfullHQ/$it/$id.png" to true } + regions.map { "$base/wii/cover/$it/$id.png" to false }
                else -> {
                    val platform = when (systemId) { "nds" -> "ds"; else -> systemId }
                    val types = if (maxWidth in 1..400) listOf("coverM", "coverHQ", "cover") else listOf("coverHQ", "coverM", "cover")
                    types.flatMap { t -> regions.map { r -> "$base/$platform/$t/$r/$id.jpg" to false } }
                }
            }
        }

        /**
         * Cover regions to try for an id: the one it means (as Dolphin picks it for discs:
         * E -> US, J -> JA, W -> ZH, K -> KO, PAL letters -> their language; for PS3 the third
         * letter: BLUS -> US, BLES -> EN, BLJM -> JA...), then English, US and Japanese covers.
         */
        fun regions(id: String, prefs: List<String>, ps3: Boolean = false): List<String> {
            val own = if (ps3) when (id.getOrNull(2)) {
                'U' -> "US"; 'E' -> "EN"; 'J', 'P' -> "JA"; 'K' -> "KO"; 'A', 'H', 'C' -> "ZH"
                else -> null
            } else when (id.getOrNull(3)) {
                'E', 'N' -> "US"
                'J' -> "JA"
                'W', 'C' -> "ZH"
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
