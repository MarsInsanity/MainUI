package io.github.marsinsanity.mainui.store

import io.github.marsinsanity.mainui.MainUI
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Downloads themes from the OnionOS theme repository
 * (https://github.com/OnionUI/Themes):
 *  - the catalog: .github/data/custom.txt and remixed.txt (one theme per line)
 *  - previews: themes/<name>/preview.png
 *  - packages: release/<name>.zip (installed by ThemeManager like any .zip in Themes/)
 */
object ThemeStore {
    private const val DEFAULT_RAW = "https://raw.githubusercontent.com/OnionUI/Themes/main"

    /** Debug builds can point at a local mirror with `setprop debug.mainui.themebase <url>`. */
    private val RAW: String by lazy {
        if (!io.github.marsinsanity.mainui.BuildConfig.DEBUG) DEFAULT_RAW
        else runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.mainui.themebase")).inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: DEFAULT_RAW
    }

    data class Entry(val name: String, val remixed: Boolean, val featured: Boolean = false) {
        /** "Blueprint by Aemiii91" -> author "Aemiii91" */
        val title get() = name.substringBefore(" by ").trim()
        val author get() = name.substringAfter(" by ", "").trim()
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun previewUrl(e: Entry) = "$RAW/themes/${enc(e.name)}/preview.png"
    fun zipUrl(e: Entry) = "$RAW/release/${enc(e.name)}.zip"

    private val cacheDir get() = File(MainUI.storage.dataDir("cache"), "themestore").apply { mkdirs() }

    /** The catalog, cached for a day. */
    fun catalog(refresh: Boolean = false): List<Entry> {
        val cache = File(cacheDir, "catalog.txt")
        val fresh = cache.isFile && System.currentTimeMillis() - cache.lastModified() < 24 * 3600 * 1000L
        val text = if (!refresh && fresh) cache.readText() else {
            val custom = Http.text("$RAW/.github/data/custom.txt")
            val remixed = runCatching { Http.text("$RAW/.github/data/remixed.txt") }.getOrDefault("")
            val featured = runCatching { Http.text("$RAW/.github/data/featured.txt") }.getOrDefault("")
            val t = custom.lines().map { "C|$it" } + remixed.lines().map { "R|$it" } + featured.lines().map { "F|$it" }
            cache.writeText(t.joinToString("\n"))
            t.joinToString("\n")
        }
        val featured = text.lines().filter { it.startsWith("F|") }.map { it.substringAfter('|').trim().lowercase() }.toSet()
        // The repository's featured themes first (as on its README), then A-Z.
        return text.lines().filter { !it.startsWith("F|") }.mapNotNull { l ->
            val name = l.substringAfter('|').trim()
            if (name.isEmpty() || name.startsWith("#")) null else Entry(name, l.startsWith("R|"), name.lowercase() in featured)
        }.distinctBy { it.name.lowercase() }.sortedWith(compareBy({ !it.featured }, { it.name.lowercase() }))
    }

    /** Preview image, downloaded once into MainUI/cache/themestore. */
    fun preview(e: Entry): File? {
        val f = File(cacheDir, "${e.name.replace(Regex("[^A-Za-z0-9 ._-]"), "_")}.png")
        if (f.isFile && f.length() > 0) return f
        return runCatching { Http.download(previewUrl(e), f); f }.getOrNull()
    }

    /** True when a theme folder with this name exists in any Themes folder. */
    fun isInstalled(e: Entry): Boolean =
        MainUI.storage.themeDirs().any { File(it, e.name).isDirectory }

    /**
     * Downloads and installs a theme into the Themes folder on [onSd] storage
     * (created if needed). Returns the installed theme folder.
     */
    fun install(e: Entry, onSd: Boolean, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        val vol = (if (onSd) MainUI.storage.sdCard else null) ?: MainUI.storage.internal
        val themes = vol.dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("Themes", true) }
            ?: File(vol.dir, "Themes").apply { mkdirs() }
        val zip = File(themes, "${e.name}.zip")
        Http.download(zipUrl(e), zip, progress)
        return MainUI.themes.install(zip) ?: throw IOException("Could not extract ${zip.name}")
    }
}

/** "740 KB", "12.3 MB" */
fun formatBytes(b: Long): String = if (b >= 1024 * 1024) "%.1f MB".format(b / 1048576.0) else "${(b + 1023) / 1024} KB"

/** Tiny HTTP helper shared by the stores. */
object Http {
    private const val UA = "MainUI-Android"

    private fun open(url: String): HttpURLConnection {
        var u = url
        repeat(5) {
            val c = (URL(u).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000; readTimeout = 60000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
            }
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat
            }
            if (code !in 200..299) { c.disconnect(); throw IOException("HTTP $code for $u") }
            return c
        }
        throw IOException("Too many redirects for $url")
    }

    fun text(url: String): String {
        val c = open(url)
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** Downloads to [dest] atomically. [progress] gets (bytes, total or -1). */
    fun download(url: String, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        val c = open(url)
        try {
            val total = c.contentLengthLong
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            c.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - last > 256 * 1024) { progress(done, total); last = done }
                    }
                    progress(done, total)
                }
            }
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        } finally {
            c.disconnect()
        }
    }
}
