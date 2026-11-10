package io.github.marsinsanity.mainui.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.Collections
import java.util.concurrent.Executors

/**
 * Asynchronous, size limited bitmap cache for box art, screenshots and theme
 * previews so scrolling never decodes images on the UI thread.
 */
object Images {
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** Keys that had no image -> until when that holds (network misses are retried after a while). */
    private val missing = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val loading = Collections.synchronizedSet(HashSet<String>())
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "mainui-img").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    /** Downloads (store icons, theme previews) wait on the network: kept off the local decoding threads. */
    private val netPool = Executors.newFixedThreadPool(3) { r -> Thread(r, "mainui-img-net").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private const val NET_RETRY_MS = 60_000L

    private fun isMissing(key: String): Boolean {
        val until = missing[key] ?: return false
        if (android.os.SystemClock.elapsedRealtime() < until) return true
        missing.remove(key)
        return false
    }

    /**
     * Returns the cached bitmap or null while it loads in the background.
     * [resolve] runs on a worker thread and returns the file to decode. [network]: it downloads
     * (a failure may be the connection: tried again a minute later, not never).
     */
    fun get(key: String, host: SceneHost?, maxSize: Int = 360, network: Boolean = false, resolve: () -> File?): Bitmap? {
        cache.get(key)?.let { return it }
        if (isMissing(key) || !loading.add(key)) return null
        (if (network) netPool else pool).execute {
            try {
                val f = runCatching(resolve).getOrNull()
                val b = f?.takeIf { it.isFile }?.let { decode(it, maxSize) }
                if (b != null) cache.put(key, b)
                else missing[key] = if (network) android.os.SystemClock.elapsedRealtime() + NET_RETRY_MS else Long.MAX_VALUE
                if (b != null && host != null) main.post { host.invalidate() }
            } finally {
                loading.remove(key)
            }
        }
        return null
    }

    /** Same as [get] but for bitmaps produced by arbitrary code (app banners...). */
    fun getBitmap(key: String, host: SceneHost?, produce: () -> Bitmap?): Bitmap? {
        cache.get(key)?.let { return it }
        if (isMissing(key) || !loading.add(key)) return null
        pool.execute {
            try {
                val b = runCatching(produce).getOrNull()
                if (b != null) cache.put(key, b) else missing[key] = Long.MAX_VALUE
                if (b != null && host != null) main.post { host.invalidate() }
            } finally {
                loading.remove(key)
            }
        }
        return null
    }

    fun invalidate(key: String) { cache.remove(key); missing.remove(key) }
    fun clear() { cache.evictAll(); missing.clear() }

    fun decode(f: File, maxSize: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (o.outWidth / (sample * 2) >= maxSize && o.outHeight / (sample * 2) >= maxSize) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Generated glyph icons for MainUI's built-in apps (used when a theme has no icons/app art). */
object Glyphs {
    private val gfx = Gfx()
    private val cache = HashMap<String, Bitmap>()

    @Synchronized
    fun get(kind: String, size: Int = 64): Bitmap = cache.getOrPut("$kind@$size") { gfx.glyphBitmap(kind, size) }

    @Synchronized
    fun clear() = cache.clear()
}
