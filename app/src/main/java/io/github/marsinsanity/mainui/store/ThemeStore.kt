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
    private const val PIECE = 1L shl 20          // 1 MB pieces for parallel downloads
    private const val CONNECTIONS = 6

    private fun connect(url: String, headers: Map<String, String> = emptyMap()): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000; readTimeout = 30000
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", UA)
            for ((k, v) in headers) setRequestProperty(k, v)
        }

    /** Opens [url] following redirects; returns the connection and the final URL. */
    private fun open(url: String, headers: Map<String, String> = emptyMap(), accept: (Int) -> Boolean = { it in 200..299 }): Pair<HttpURLConnection, String> {
        var u = url
        repeat(5) {
            val c = connect(u, headers)
            val code = c.responseCode
            if (code in 300..399 && code != 304) {
                u = URL(URL(u), c.getHeaderField("Location")).toString(); c.disconnect(); return@repeat
            }
            if (!accept(code)) { c.disconnect(); throw IOException("HTTP $code for $u") }
            return c to u
        }
        throw IOException("Too many redirects for $url")
    }

    fun text(url: String): String {
        val (c, _) = open(url)
        try { return c.inputStream.bufferedReader().readText() } finally { c.disconnect() }
    }

    /** What a download found out about the file (for conditional requests next time). */
    data class Info(val notModified: Boolean, val etag: String?, val lastModified: String?, val bytes: Long)

    /** Downloads to [dest] atomically. [progress] gets (bytes, total or -1). */
    fun download(url: String, dest: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
        downloadFast(url, dest, progress = progress)
    }

    /**
     * Downloads [url] to [dest]. When the server supports byte ranges (most do,
     * f-droid.org included), the file is fetched as 1 MB pieces over several
     * connections at once, which is much faster on links where a single
     * connection is slow. If-Range with the server's ETag / Last-Modified
     * guarantees that every piece comes from the same version of the file; if it
     * changes mid-way, or ranges aren't supported, it falls back to one stream.
     * [ifNoneMatch] / [ifModifiedSince] make the request conditional: when the
     * file hasn't changed, nothing is downloaded and Info.notModified is true.
     */
    fun downloadFast(
        url: String, dest: File, ifNoneMatch: String? = null, ifModifiedSince: String? = null,
        progress: (Long, Long) -> Unit = { _, _ -> },
    ): Info {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        val first = buildMap {
            put("Range", "bytes=0-${PIECE - 1}")
            ifNoneMatch?.let { put("If-None-Match", it) }
            ifModifiedSince?.let { put("If-Modified-Since", it) }
        }
        val (c, finalUrl) = open(url, first) { it in 200..299 || it == 304 }
        val etag = c.getHeaderField("ETag")
        val lastModified = c.getHeaderField("Last-Modified")
        if (c.responseCode == 304) { c.disconnect(); return Info(true, etag ?: ifNoneMatch, lastModified ?: ifModifiedSince, 0) }
        val range = c.getHeaderField("Content-Range")
        val total = range?.substringAfter('/')?.toLongOrNull()
        try {
            if (c.responseCode != 206 || total == null || total <= PIECE) {
                // No ranges (or a small file): one stream.
                val size = if (c.responseCode == 206 && total != null) total else c.contentLengthLong
                streamTo(c, tmp, 0L, size, progress)
            } else {
                // If-Range needs a strong validator: a weak ETag (W/"...") can't be used.
                val validator = etag?.takeUnless { it.startsWith("W/") } ?: lastModified
                parallel(c, finalUrl, tmp, total, validator, progress)
            }
        } catch (e: PieceMismatch) {
            // The file changed while downloading, or a server ignored the range: start over in one stream.
            val (c2, _) = open(finalUrl)
            try { streamTo(c2, tmp, 0L, c2.contentLengthLong, progress) } finally { c2.disconnect() }
        } finally {
            c.disconnect()
        }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) { tmp.copyTo(dest, true); tmp.delete() }
        return Info(false, etag, lastModified, dest.length())
    }

    private class PieceMismatch(msg: String) : IOException(msg)

    private fun streamTo(c: HttpURLConnection, tmp: File, startDone: Long, total: Long, progress: (Long, Long) -> Unit) {
        c.inputStream.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var done = startDone
                var last = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    if (done - last > 256 * 1024) { progress(done, total); last = done }
                }
                progress(done, total)
                if (total > 0 && done != total) throw IOException("Download incomplete ($done of $total bytes)")
            }
        }
    }

    private fun parallel(first: HttpURLConnection, url: String, tmp: File, total: Long, validator: String?,
                         progress: (Long, Long) -> Unit) {
        java.io.RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(total)
            val ch = raf.channel
            val done = java.util.concurrent.atomic.AtomicLong(0)
            val lastReport = java.util.concurrent.atomic.AtomicLong(0)
            fun report() {
                val d = done.get()
                if (d - lastReport.get() > 256 * 1024 || d == total) { lastReport.set(d); progress(d, total) }
            }
            /** Writes the piece at its offset; on failure its bytes are taken back out of the count. */
            fun readPiece(c: HttpURLConnection, start: Long, end: Long) {
                var written = 0L
                try {
                    c.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var pos = start
                        while (pos <= end) {
                            val n = input.read(buf, 0, minOf(buf.size.toLong(), end - pos + 1).toInt())
                            if (n < 0) throw IOException("Connection closed early")
                            ch.write(java.nio.ByteBuffer.wrap(buf, 0, n), pos)
                            pos += n; written += n
                            done.addAndGet(n.toLong()); report()
                        }
                    }
                } catch (e: IOException) {
                    done.addAndGet(-written)
                    throw e
                }
            }
            val pieces = java.util.concurrent.ConcurrentLinkedQueue<LongRange>()
            var s = PIECE
            while (s < total) { pieces += s..minOf(total - 1, s + PIECE - 1); s += PIECE }
            val error = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
            val workers = (1..minOf(CONNECTIONS, pieces.size)).map {
                Thread {
                    while (error.get() == null) {
                        val p = pieces.poll() ?: break
                        var attempt = 0
                        while (true) {
                            try {
                                val headers = buildMap {
                                    put("Range", "bytes=${p.first}-${p.last}")
                                    validator?.let { v -> put("If-Range", v) }
                                }
                                val c = connect(url, headers)
                                try {
                                    val cr = c.getHeaderField("Content-Range")
                                    if (c.responseCode != 206 || cr == null || !cr.startsWith("bytes ${p.first}-${p.last}/$total")) {
                                        throw PieceMismatch("piece ${p.first}: HTTP ${c.responseCode} $cr")
                                    }
                                    readPiece(c, p.first, p.last)
                                } finally { c.disconnect() }
                                break
                            } catch (e: PieceMismatch) {
                                error.compareAndSet(null, e); break
                            } catch (e: IOException) {
                                // Network hiccup: retry this piece a couple of times.
                                if (++attempt >= 3) { error.compareAndSet(null, e); break }
                                Thread.sleep(500L * attempt)
                            }
                        }
                    }
                }.apply { isDaemon = true; start() }
            }
            // The first piece arrives on the first connection while the others already run.
            try { readPiece(first, 0, PIECE - 1) } catch (e: IOException) { error.compareAndSet(null, e) }
            workers.forEach { it.join() }
            error.get()?.let { throw it }
            progress(total, total)
        }
    }
}
