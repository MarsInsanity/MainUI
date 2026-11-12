package io.github.marsinsanity.mainui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class FontStyle(val typeface: Typeface, val size: Float, val color: Int)

data class GridStyle(val typeface: Typeface, val size1x4: Float, val size3x4: Float, val color: Int, val selectedColor: Int)

data class BatteryStyle(
    val visible: Boolean, val typeface: Typeface, val size: Float, val color: Int,
    val offsetX: Int, val offsetY: Int, val align: String, val fixed: Boolean,
)

/**
 * An OnionOS theme: Themes/<name>/{config.json, skin/, icons/, sound/, preview.png}.
 *
 * Mirrors Onion's theme_loadFromPath(): same defaults, same fallback chain
 * (hint -> title, list -> title, currentpage/total -> hint, battery -> hint)
 * and the same `hideIconTitle` backwards compatibility. Images missing from a
 * theme return null and the renderer draws a built-in replacement, so partial
 * themes still look complete.
 */
class Theme private constructor(
    private val ctx: Context,
    /** Theme folder, null for the built-in theme. */
    val dir: File?,
    private val overrideDir: File?,
    private val fontDirs: List<File>,
) {
    var name = "MainUI Default"; private set
    var author = "MainUI"; private set
    var description = ""; private set
    var hideIconLabels = false; private set
    var hideHintLabels = false; private set
    var frameLeft = 0; private set
    var frameRight = 0; private set
    lateinit var title: FontStyle; private set
    lateinit var hint: FontStyle; private set
    lateinit var currentPage: FontStyle; private set
    lateinit var total: FontStyle; private set
    lateinit var list: FontStyle; private set
    lateinit var grid: GridStyle; private set
    lateinit var battery: BatteryStyle; private set

    /** Built-in palette used when the theme has no image for an element. */
    var accent = Color.rgb(200, 49, 43); private set
    var panel = Color.argb(235, 24, 24, 26); private set
    var bgColor = Color.rgb(30, 30, 32); private set

    private val bitmaps = ConcurrentHashMap<String, Any>() // Bitmap or MISSING
    private val typefaces = HashMap<String, Typeface>()

    val isBuiltIn get() = dir == null

    private fun load() {
        val fallbackFont = font("BPreplayBold.otf")
        val white = Color.WHITE
        var t = FontStyle(fallbackFont, 36f, white)
        var h = FontStyle(fallbackFont, 40f, white)
        var cp = FontStyle(fallbackFont, 40f, white)
        var tot = FontStyle(fallbackFont, 40f, white)
        var l = FontStyle(fallbackFont, 24f, white)
        var g = GridStyle(fallbackFont, 24f, 18f, Color.rgb(104, 104, 104), white)
        var b = BatteryStyle(false, fallbackFont, 24f, white, 0, 0, "left", false)

        val configs = mutableListOf<JSONObject>()
        val main = dir?.let { File(it, "config.json") }?.takeIf { it.isFile }
        if (main != null) runCatching { configs += JSONObject(main.readText()) }
        else runCatching { configs += JSONObject(ctx.assets.open("default_theme/config.json").bufferedReader().readText()) }
        val overrides = overrideDir?.let { File(it, "config.json") }?.takeIf { it.isFile }
        if (overrides != null) runCatching { configs += JSONObject(overrides.readText()) }

        configs.forEachIndexed { idx, c ->
            val useFallbacks = idx == 0
            c.optString("name").takeIf { it.isNotEmpty() }?.let { name = it }
            c.optString("author").takeIf { it.isNotEmpty() }?.let { author = it }
            c.optString("description").takeIf { it.isNotEmpty() }?.let { description = it }
            val hide = c.optJSONObject("hideLabels")
            if (hide != null) {
                hideIconLabels = hide.optBoolean("icons", hideIconLabels)
                hideHintLabels = hide.optBoolean("hints", hideHintLabels)
            } else if (c.has("hideIconTitle")) {
                val v = c.optBoolean("hideIconTitle"); hideIconLabels = v; hideHintLabels = v
            }
            t = fontStyle(c.optJSONObject("title"), t, null)
            h = fontStyle(c.optJSONObject("hint"), h, if (useFallbacks) t else null)
            cp = fontStyle(c.optJSONObject("currentpage"), cp, if (useFallbacks) h else null)
            tot = fontStyle(c.optJSONObject("total"), tot, if (useFallbacks) h else null)
            l = fontStyle(c.optJSONObject("list"), l, if (useFallbacks) t else null)
            c.optJSONObject("grid")?.let { gj ->
                g = GridStyle(
                    gj.optString("font").takeIf { it.isNotEmpty() }?.let { font(it) } ?: g.typeface,
                    gj.optDouble("grid1x4", g.size1x4.toDouble()).toFloat(),
                    gj.optDouble("grid3x4", g.size3x4.toDouble()).toFloat(),
                    color(gj, "color") ?: g.color,
                    color(gj, "selectedcolor") ?: color(gj, "selectedColor") ?: g.selectedColor,
                )
            }
            val bj = c.optJSONObject("batteryPercentage")
            if (bj != null) {
                val align = bj.optString("textAlign").takeIf { it.isNotEmpty() }
                    ?: if (bj.has("onleft")) (if (bj.optBoolean("onleft")) "right" else "left") else b.align
                b = BatteryStyle(
                    bj.optBoolean("visible", b.visible),
                    bj.optString("font").takeIf { it.isNotEmpty() }?.let { font(it) } ?: if (useFallbacks) h.typeface else b.typeface,
                    bj.optDouble("size", b.size.toDouble()).toFloat(),
                    color(bj, "color") ?: if (useFallbacks) h.color else b.color,
                    bj.optInt("offsetX", b.offsetX), bj.optInt("offsetY", b.offsetY), align,
                    bj.optBoolean("fixed", b.fixed),
                )
            } else if (useFallbacks) {
                b = b.copy(typeface = h.typeface, color = h.color)
            }
            c.optJSONObject("frame")?.let { f ->
                frameLeft = f.optInt("border-left", frameLeft); frameRight = f.optInt("border-right", frameRight)
            }
            // MainUI-for-Android extension: colours for procedurally drawn elements.
            c.optJSONObject("mainui")?.let { m ->
                color(m, "accent")?.let { accent = it }
                color(m, "panel")?.let { panel = it }
                color(m, "background")?.let { bgColor = it }
            }
        }
        title = t; hint = h; currentPage = cp; total = tot; list = l; grid = g; battery = b
    }

    private fun fontStyle(o: JSONObject?, cur: FontStyle, fallback: FontStyle?): FontStyle {
        if (o == null) return fallback ?: cur
        val tf = o.optString("font").takeIf { it.isNotEmpty() }?.let { font(it) } ?: fallback?.typeface ?: cur.typeface
        val size = if (o.has("size")) o.optDouble("size").toFloat() else fallback?.size ?: cur.size
        val col = color(o, "color") ?: fallback?.color ?: cur.color
        return FontStyle(tf, size, col)
    }

    private fun color(o: JSONObject, key: String): Int? {
        val s = o.optString(key).takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Color.parseColor(if (s.startsWith("#")) s else "#$s") }.getOrNull()
    }

    /**
     * Resolves Onion font references. Themes often point at fonts that ship with
     * Onion (/mnt/SDCARD/miyoo/app/...), so the basename is searched in the theme,
     * MainUI/Fonts, and the fonts bundled with the app.
     */
    fun font(ref: String): Typeface {
        typefaces[ref]?.let { return it }
        val base = ref.substringAfterLast('/')
        val candidates = mutableListOf<File>()
        if (ref.startsWith("/")) candidates += File(ref)
        dir?.let { candidates += File(it, ref); candidates += File(it, base) }
        fontDirs.forEach { candidates += File(it, base) }
        var tf: Typeface? = candidates.firstOrNull { it.isFile }?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        if (tf == null) {
            val asset = BUNDLED_ALIASES[base.lowercase()]
            tf = when (asset) {
                null -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
                "" -> Typeface.DEFAULT_BOLD
                else -> runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$asset") }.getOrNull()
            } ?: Typeface.DEFAULT_BOLD
        }
        typefaces[ref] = tf!!
        return tf!!
    }

    // ---- images ----------------------------------------------------------------------

    /** skin/<name>.png with theme override support (MainUI/theme_overrides/skin). */
    fun skin(name: String): Bitmap? = image("skin/$name.png")

    /** icons/<name>.png or icons/sel/<name>.png. Tries every alias in order. */
    fun icon(names: List<String>, selected: Boolean = false): Bitmap? {
        for (n in names) {
            if (selected) image("icons/sel/$n.png")?.let { return it }
            image("icons/$n.png")?.let { return it }
        }
        // Not in this theme: the bundled MainUI theme has an icon for every system.
        val fb = iconFallbackDir ?: return null
        if (dir != null && fb.parentFile?.absolutePath == dir.absolutePath) return null
        for (n in names) {
            for (rel in if (selected) listOf("sel/$n.png", "$n.png") else listOf("$n.png")) {
                val key = "fallback:$rel"
                val cached = bitmaps[key]
                if (cached != null) { if (cached is Bitmap) return cached; continue }
                val f = File(fb, rel)
                val bmp = if (f.isFile) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
                bitmaps[key] = bmp ?: MISSING
                if (bmp != null) return bmp
            }
        }
        return null
    }

    fun appIcon(name: String): Bitmap? = image("icons/app/$name.png")

    fun preview(): Bitmap? = image("preview.png")

    fun file(rel: String): File? {
        overrideDir?.let { File(it, rel) }?.takeIf { it.isFile }?.let { return it }
        return dir?.let { File(it, rel) }?.takeIf { it.isFile }
    }

    private fun image(rel: String): Bitmap? {
        bitmaps[rel]?.let { return it as? Bitmap }
        val f = file(rel)
        val bmp = f?.let { file ->
            runCatching {
                val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.absolutePath, b)
                // (A huge picture, a phone photo as background, would crash drawing it: made smaller.)
                BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize(b.outWidth, b.outHeight) })
            }.getOrNull()
        }
        bitmaps[rel] = bmp ?: MISSING
        return bmp
    }

    val soundChange: File? get() = file("sound/change.wav")
    val soundBgm: File? get() = file("sound/bgm.mp3")

    /** Drops cached images. Bitmaps are not recycled: the UI thread may still be drawing them. */
    fun release() = bitmaps.clear()

    companion object {
        private val MISSING = Any()

        /** icons/ of the bundled MainUI theme, used for systems a theme has no icon for. */
        @Volatile var iconFallbackDir: File? = null

        /** Onion bundled font names -> fonts shipped with this app ("" = system bold). */
        private val BUNDLED_ALIASES = mapOf(
            "bpreplaybold.otf" to "BPreplayBold.otf",
            "exo-2-bold-italic.ttf" to "Exo-2-Bold-Italic.ttf",
            "exo-2-bold-italic_universal.ttf" to "Exo-2-Bold-Italic.ttf",
            "adobeblank.ttf" to "AdobeBlank.ttf",
            "adobeblank.ttc" to "AdobeBlank.ttf",
            "helvetica-neue-2.ttf" to "",
            "henb.ttf" to "",
            "microsoftyaheigb.ttf" to "",
            "wqy-microhei.ttc" to "",
        )

        fun load(ctx: Context, dir: File?, overrideDir: File?, fontDirs: List<File>): Theme =
            Theme(ctx, dir, overrideDir, fontDirs).apply { load() }
    }
}

/**
 * The power of two to decode a [w] x [h] image at: halved while it stays at least [maxW] on both
 * sides (0: never for size), as Images.decode does, and until it is at most [maxBytes] in memory.
 */
fun sampleSize(w: Int, h: Int, maxW: Int = 0, maxBytes: Long = 64L shl 20): Int {
    var s = 1
    while ((maxW > 0 && w / (s * 2) >= maxW && h / (s * 2) >= maxW) || (w.toLong() / s) * (h.toLong() / s) * 4 > maxBytes) s *= 2
    return s
}
