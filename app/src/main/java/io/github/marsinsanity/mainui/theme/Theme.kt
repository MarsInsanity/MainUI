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
    /** MainUI's header clock (Onion has none): the colour read as stock MainUI reads it. */
    val clockColor: Int = color,
)

/**
 * An OnionOS theme: Themes/<name>/{config.json, skin/, icons/, sound/, preview.png}.
 *
 * Mirrors Onion's theme_loadFromPath(): same defaults, same fallback chain
 * (hint -> title, list -> title, currentpage/total -> hint, battery -> hint)
 * and the same `hideIconTitle` backwards compatibility. A missing skin/extra
 * picture falls back to OnionOS's own (assets onion_res/), as on a Miyoo; other
 * missing images return null and the renderer draws a built-in replacement.
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
    /** No "mainui" block: a theme made for OnionOS, drawn with its exact layout where MainUI's differs. */
    var onionLayout = true; private set
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
    private val typefaces = HashMap<String, Typeface?>() // null: not found
    /** Pictures that are not the theme's own (see [artSource]); identity keys. */
    private val artSources = ConcurrentHashMap<Bitmap, ArtSource>()

    /** What a Miyoo falls back to for fonts it can't find: Exo 2 (hint, battery)... */
    private val exoFont: Typeface by lazy {
        runCatching { Typeface.createFromAsset(ctx.assets, "fonts/Exo-2-Bold-Italic.ttf") }.getOrNull() ?: Typeface.DEFAULT_BOLD
    }
    /** ...and stock MainUI's HENB.TTF (title, list, grid): from MainUI/Fonts, else the system bold. */
    private val henbFont: Typeface by lazy {
        fontDirs.map { File(it, "HENB.TTF") }.firstOrNull { it.isFile }
            ?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.DEFAULT_BOLD
    }

    val isBuiltIn get() = dir == null

    private fun load() {
        val white = Color.WHITE
        val henb = henbFont; val exo = exoFont
        var t = FontStyle(henb, 36f, white)
        var h = FontStyle(exo, 40f, white)
        var cp = FontStyle(exo, 40f, white)
        var tot = FontStyle(exo, 40f, white)
        var l = FontStyle(henb, 24f, white)
        var g = GridStyle(henb, 24f, 18f, Color.rgb(104, 104, 104), white)
        var b = BatteryStyle(false, exo, 24f, white, 0, 0, "left", false)

        val configs = mutableListOf<JSONObject>()
        val main = dir?.let { File(it, "config.json") }?.takeIf { it.isFile }
        fun asset(path: String) = ctx.assets.open(path).bufferedReader().use { it.readText() }
        when {
            main != null -> runCatching { configs += JSONObject(main.readText()) }
            // A theme folder without config.json gets OnionOS's default theme settings, as on a Miyoo.
            dir != null -> { name = dir.name; author = ""; runCatching { configs += JSONObject(asset("onion_res/config.json")) } }
            else -> runCatching { configs += JSONObject(asset("default_theme/config.json")) }
        }
        val ownConfig = main != null || dir == null
        val overrides = overrideDir?.let { File(it, "config.json") }?.takeIf { it.isFile }
        if (overrides != null) runCatching { configs += JSONObject(overrides.readText()) }
        // (known before any font is looked up: MainUI's own themes name its bundled fonts directly)
        onionLayout = configs.none { it.has("mainui") }

        var ownPalette = 0 // MainUI colours the configs set: 1 accent, 2 panel, 4 background
        configs.forEachIndexed { idx, c ->
            val useFallbacks = idx == 0
            if (idx > 0 || ownConfig) {
                c.optString("name").takeIf { it.isNotEmpty() }?.let { name = it }
                c.optString("author").takeIf { it.isNotEmpty() }?.let { author = it }
                c.optString("description").takeIf { it.isNotEmpty() }?.let { description = it }
            }
            val hide = c.optJSONObject("hideLabels")
            if (hide != null) {
                hideIconLabels = hide.optBoolean("icons", hideIconLabels)
                hideHintLabels = hide.optBoolean("hints", hideHintLabels)
            } else if (c.has("hideIconTitle")) {
                val v = c.optBoolean("hideIconTitle"); hideIconLabels = v; hideHintLabels = v
            }
            // (a font a Miyoo can't find: HENB for title, list and grid, Exo 2 for the rest)
            t = fontStyle(c.optJSONObject("title"), t, null, henb)
            h = fontStyle(c.optJSONObject("hint"), h, if (useFallbacks) t else null, exo)
            cp = fontStyle(c.optJSONObject("currentpage"), cp, if (useFallbacks) h else null, exo)
            tot = fontStyle(c.optJSONObject("total"), tot, if (useFallbacks) h else null, exo)
            l = fontStyle(c.optJSONObject("list"), l, if (useFallbacks) t else null, henb)
            c.optJSONObject("grid")?.let { gj ->
                g = GridStyle(
                    gj.optString("font").takeIf { it.isNotEmpty() }?.let { font(it, henb) } ?: g.typeface,
                    gj.optDouble("grid1x4", g.size1x4.toDouble()).toFloat(),
                    gj.optDouble("grid3x4", g.size3x4.toDouble()).toFloat(),
                    stockColor(gj, "color") ?: g.color,
                    stockColor(gj, "selectedcolor") ?: stockColor(gj, "selectedColor") ?: g.selectedColor,
                )
            }
            val bj = c.optJSONObject("batteryPercentage")
            if (bj != null) {
                val align = bj.optString("textAlign").takeIf { it.isNotEmpty() }
                    ?: if (bj.has("onleft")) (if (bj.optBoolean("onleft")) "right" else "left") else b.align
                b = BatteryStyle(
                    bj.optBoolean("visible", b.visible),
                    bj.optString("font").takeIf { it.isNotEmpty() }?.let { font(it, exo) } ?: if (useFallbacks) h.typeface else b.typeface,
                    bj.optDouble("size", b.size.toDouble()).toFloat(),
                    // (drawn by OnionOS's own battery code, which reads colours with hex2sdl)
                    if (bj.has("color")) OnionColor.hex2sdl(bj.optString("color")) else if (useFallbacks) h.color else b.color,
                    bj.optInt("offsetX", b.offsetX), bj.optInt("offsetY", b.offsetY), align,
                    bj.optBoolean("fixed", b.fixed),
                    // ("white" or "#000" would make a black clock: the hint colour instead)
                    stockColor(bj, "color") ?: if (useFallbacks) h.color else b.clockColor,
                )
            } else if (useFallbacks) {
                b = b.copy(typeface = h.typeface, color = h.color, clockColor = h.color)
            }
            c.optJSONObject("frame")?.let { f ->
                frameLeft = f.optInt("border-left", frameLeft); frameRight = f.optInt("border-right", frameRight)
            }
            // MainUI-for-Android extension: colours for procedurally drawn elements.
            c.optJSONObject("mainui")?.let { m ->
                color(m, "accent")?.let { accent = it; ownPalette = ownPalette or 1 }
                color(m, "panel")?.let { panel = it; ownPalette = ownPalette or 2 }
                color(m, "background")?.let { bgColor = it; ownPalette = ownPalette or 4 }
            }
        }
        // (OnionOS always draws its list font bold)
        l = l.copy(typeface = Typeface.create(l.typeface, Typeface.BOLD))
        title = t; hint = h; currentPage = cp; total = tot; list = l; grid = g; battery = b
        if (dir != null && ownPalette != 7) runCatching { paletteFromArt(ownPalette) }
    }

    /**
     * An OnionOS theme has no colours for what MainUI draws itself (toggles, panels, its own
     * tiles): they are taken from its pictures, so they match it instead of a fixed red.
     */
    private fun paletteFromArt(own: Int) {
        fun px(name: String) = skinOwn(name)?.let { pixels(it) }
        if (own and 4 == 0) px("background")?.let { ThemeArt.dominant(it) }?.let { bgColor = it }
        if (own and 1 == 0) {
            val found = sequenceOf("bg-list-s", "dot-a", "ic-game-f", "icon-A-54", "bg-pop-menu-1", "pop-bg", "background")
                .mapNotNull { n -> px(n)?.let { ThemeArt.dominant(it, colourful = true) } }.firstOrNull()
            accent = when {
                found == null -> if (ThemeArt.luma(bgColor) < 128) Color.rgb(110, 110, 110) else Color.rgb(80, 80, 80)
                // (a selection the colour of the background would not show)
                ThemeArt.dist(found, bgColor) < 90 -> ThemeArt.usableAccent(lighten(found, 0.45f))
                else -> ThemeArt.usableAccent(found)
            }
        }
        if (own and 2 == 0) (px("bg-pop-menu-1") ?: px("pop-bg"))?.let { ThemeArt.dominant(it) }?.let {
            panel = Color.argb(240, Color.red(it), Color.green(it), Color.blue(it))
        }
    }

    private fun lighten(c: Int, f: Float) = Color.rgb((Color.red(c) + (255 - Color.red(c)) * f).toInt(),
        (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())

    /** [b]'s pixels, from a copy at most [limit] wide when it is bigger. */
    private fun pixels(b: Bitmap, limit: Int = Int.MAX_VALUE): IntArray {
        val s = if (b.width > limit) Bitmap.createScaledBitmap(b, limit, maxOf(1, b.height * limit / b.width), true) else b
        val px = IntArray(s.width * s.height)
        s.getPixels(px, 0, s.width, 0, 0, s.width, s.height)
        if (s !== b) s.recycle()
        return px
    }

    /** A main menu tile with the picture taken out, and where the picture was: see [tileArt]. */
    class TileArt(val card: Bitmap?, val left: Float, val top: Float, val right: Float, val bottom: Float, val ink: Int)

    private val tileArts = arrayOfNulls<Any>(2)

    /**
     * The theme's own Games tile (or another main menu tile) as a blank card in the same style,
     * for tiles OnionOS has no picture for (Emulators, systems in the row view). Null: the theme
     * has no main menu tiles.
     */
    fun tileArt(selected: Boolean): TileArt? {
        val i = if (selected) 1 else 0
        tileArts[i]?.let { return it as? TileArt }
        val st = if (selected) "f" else "n"
        val src = listOf("game", "app", "setting", "favorite", "recent", "retroarch").firstNotNullOfOrNull { skinOwn("ic-$it-$st") }
        val art = src?.let { b ->
            val small = if (b.width > 640) Bitmap.createScaledBitmap(b, 640, maxOf(1, b.height * 640 / b.width), true) else b
            val t = ThemeArt.tile(pixels(small), small.width, small.height)
            if (small !== b) small.recycle()
            t?.let {
                // (a picture without a card stands on the background: it has to show there)
                val ink = if (it.card == null && ThemeArt.dist(it.ink, bgColor) < 90)
                    (if (ThemeArt.luma(bgColor) < 128) Color.WHITE else Color.rgb(32, 32, 32)) else it.ink
                TileArt(it.card?.let { c -> Bitmap.createBitmap(c, it.w, it.h, Bitmap.Config.ARGB_8888) },
                    it.left, it.top, it.right, it.bottom, ink)
            }
        }
        tileArts[i] = art ?: MISSING
        return art
    }

    /** The A button picture with its letter taken out, for the buttons OnionOS has no picture for. */
    class ButtonArt(val blank: Bitmap, val fill: Int, val ink: Int, val cx: Float, val cy: Float, val inkH: Float, val ring: Boolean)

    private var buttonArtCache: Any? = null

    fun buttonArt(): ButtonArt? {
        buttonArtCache?.let { return it as? ButtonArt }
        val a = skinOwn("icon-A-54")
        val art = a?.takeIf { it.width <= 256 && it.height <= 256 }?.let { b ->
            ThemeArt.button(pixels(b), b.width, b.height)?.let {
                ButtonArt(Bitmap.createBitmap(it.blank, it.w, it.h, Bitmap.Config.ARGB_8888), it.fill, it.ink, it.cx, it.cy, it.inkH, it.ring)
            }
        }
        buttonArtCache = art ?: MISSING
        return art
    }

    /**
     * The theme's hints are part of its footer picture (OnionOS hideLabels.hints, or a see-through
     * A button that pushes the labels off screen): only its own A / B buttons are drawn there.
     */
    val hintsInArt: Boolean by lazy {
        hideHintLabels || skinOwn("icon-A-54")?.let { b ->
            val px = pixels(b, 160)
            px.none { (it ushr 24) >= 32 }
        } == true
    }

    private fun fontStyle(o: JSONObject?, cur: FontStyle, fallback: FontStyle?, missingFont: Typeface): FontStyle {
        if (o == null) return fallback ?: cur
        val tf = o.optString("font").takeIf { it.isNotEmpty() }?.let { font(it, missingFont) } ?: fallback?.typeface ?: cur.typeface
        val size = if (o.has("size")) o.optDouble("size").toFloat() else fallback?.size ?: cur.size
        // (a colour stock MainUI can't read keeps the default, not the fallback's)
        val col = if (o.has("color")) stockColor(o, "color") ?: cur.color else fallback?.color ?: cur.color
        return FontStyle(tf, size, col)
    }

    private fun stockColor(o: JSONObject, key: String): Int? = if (o.has(key)) OnionColor.stock(o.optString(key)) else null

    /** The "mainui" block's colours: #RRGGBB or #AARRGGBB. */
    private fun color(o: JSONObject, key: String): Int? {
        val s = o.optString(key).takeIf { it.isNotEmpty() } ?: return null
        return runCatching { Color.parseColor(if (s.startsWith("#")) s else "#$s") }.getOrNull()
    }

    /**
     * Resolves a theme's font reference, [missing] when it can't be found. Themes often point at
     * fonts that ship with Onion (/mnt/SDCARD/miyoo/app/...): those are matched by name to the
     * ones bundled with the app. A font is also searched by name in the theme and MainUI/Fonts.
     */
    fun font(ref: String, missing: Typeface = exoFont): Typeface {
        if (typefaces.containsKey(ref)) return typefaces[ref] ?: missing
        val tf = resolveFont(ref)
        typefaces[ref] = tf
        return tf ?: missing
    }

    private fun resolveFont(ref: String): Typeface? {
        val base = ref.substringAfterLast('/')
        val candidates = mutableListOf<File>()
        if (ref.startsWith("/")) candidates += File(ref)
        dir?.let { candidates += File(it, ref); candidates += File(it, base) }
        fontDirs.forEach { candidates += File(it, base) }
        val file = candidates.firstOrNull { it.isFile }
            // (a Miyoo's FAT card ignores case: "HEN.TTF" finds HEN.ttf)
            ?: (listOfNotNull(dir?.let { File(it, ref).parentFile }, dir) + fontDirs).distinct().firstNotNullOfOrNull { d ->
                d.listFiles()?.firstOrNull { it.isFile && it.name.equals(base, ignoreCase = true) }
            }
        file?.let { f -> runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { return it } }
        // A Miyoo has Onion's fonts only there; MainUI's own themes name its bundled fonts directly.
        val onDevice = ref.startsWith("/mnt/SDCARD/miyoo/app/", ignoreCase = true) || ref.startsWith("/customer/app/", ignoreCase = true)
        if (onionLayout && !onDevice) return null
        return when (val asset = BUNDLED_ALIASES[base.lowercase()]) {
            null -> null
            SYSTEM_BOLD -> Typeface.DEFAULT_BOLD
            SYSTEM_REGULAR -> Typeface.DEFAULT
            else -> runCatching { Typeface.createFromAsset(ctx.assets, "fonts/$asset") }.getOrNull()
        }
    }

    // ---- images ----------------------------------------------------------------------

    /**
     * skin/<name>.png with theme override support (MainUI/theme_overrides/skin). An OnionOS theme
     * without an extra/ picture OnionOS has its own of gets that one, as on a Miyoo.
     */
    fun skin(name: String): Bitmap? = image("skin/$name.png")
        ?: if (dir != null && onionLayout && name.startsWith("extra/") && name.substring(6) in ONION_RES) onionAsset("onion_res/${name.substring(6)}.png") else null

    /** skin/<name>.png from the theme (or its overrides) only, never OnionOS's fallback. */
    fun skinOwn(name: String): Bitmap? = image("skin/$name.png")

    /** Where [b], a picture from [skin], [icon] or [appIcon], came from. */
    fun artSource(b: Bitmap): ArtSource = artSources[b] ?: ArtSource.THEME

    /** An app asset picture (OnionOS's own), cached. */
    private fun onionAsset(path: String): Bitmap? {
        val key = "asset:$path"
        bitmaps[key]?.let { return it as? Bitmap }
        val bmp = runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } }.getOrNull()
        bitmaps[key] = bmp ?: MISSING
        if (bmp != null) artSources[bmp] = ArtSource.ONION
        return bmp
    }

    /** icons/<name>.png or icons/sel/<name>.png. Tries every alias in order. */
    fun icon(names: List<String>, selected: Boolean = false): Bitmap? {
        for (n in names) {
            if (selected) image("icons/sel/$n.png")?.let { return it }
            image("icons/$n.png")?.let { return it }
        }
        // Not in this theme: OnionOS's own default icons first (what a Miyoo shows for a theme
        // without icons), then the bundled MainUI theme's, which has one for every system.
        val fb = iconFallbackDir
        val mainUiTheme = dir == null || (fb != null && fb.parentFile?.absolutePath == dir.absolutePath)
        if (!mainUiTheme && onionIcons) onionDefaultIcon(names)?.let { return it }
        if (fb == null || (dir != null && fb.parentFile?.absolutePath == dir.absolutePath)) return null
        for (n in names) {
            for (rel in if (selected) listOf("sel/$n.png", "$n.png") else listOf("$n.png")) {
                val key = "fallback:$rel"
                val cached = bitmaps[key]
                if (cached != null) { if (cached is Bitmap) return cached; continue }
                val f = File(fb, rel)
                val bmp = if (f.isFile) runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull() else null
                bitmaps[key] = bmp ?: MISSING
                if (bmp != null) { artSources[bmp] = ArtSource.MAINUI; return bmp }
            }
        }
        return null
    }

    /** OnionOS's default icon for the first of [names] it has (app assets onion_icons/). */
    private fun onionDefaultIcon(names: List<String>): Bitmap? = names.firstNotNullOfOrNull { onionAsset("onion_icons/$it.png") }

    /** icons/app/<name>.png; an OnionOS theme without it gets OnionOS's default app icon, as on a Miyoo. */
    fun appIcon(name: String): Bitmap? = image("icons/app/$name.png")
        ?: if (dir != null && onionLayout && onionIcons) onionAsset("onion_icons/app/$name.png") else null

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
    fun release() { bitmaps.clear(); artSources.clear(); tileArts.fill(null); buttonArtCache = null }

    companion object {
        private val MISSING = Any()

        /** icons/ of the bundled MainUI theme, used for systems a theme has no icon for. */
        @Volatile var iconFallbackDir: File? = null

        /** Settings > User interface: OnionOS's default icons before MainUI's for systems a theme lacks. */
        @Volatile var onionIcons = true

        private const val SYSTEM_BOLD = ""
        private const val SYSTEM_REGULAR = "regular"

        /** Onion bundled font names -> fonts shipped with this app, or the system font of the same weight. */
        private val BUNDLED_ALIASES = mapOf(
            "bpreplaybold.otf" to "BPreplayBold.otf",
            "exo-2-bold-italic.ttf" to "Exo-2-Bold-Italic.ttf",
            "exo-2-bold-italic_universal.ttf" to "Exo-2-Bold-Italic.ttf",
            "adobeblank.ttf" to "AdobeBlank.ttf",
            "adobeblank.ttc" to "AdobeBlank.ttf",
            "helvetica-neue-2.ttf" to SYSTEM_REGULAR,
            "henb.ttf" to SYSTEM_BOLD,
            "microsoftyaheigb.ttf" to SYSTEM_REGULAR,
            "wqy-microhei.ttc" to SYSTEM_REGULAR,
        )

        /** skin/extra/ pictures OnionOS has its own of (.tmp_update/res), bundled in assets onion_res/. */
        private val ONION_RES = setOf("toggle-on", "toggle-off", "arrowLeft", "arrowRight", "gs-legend") + (0..10).map { "lum$it" }

        fun load(ctx: Context, dir: File?, overrideDir: File?, fontDirs: List<File>): Theme =
            Theme(ctx, dir, overrideDir, fontDirs).apply { load() }
    }
}

/** Where a theme picture came from: the theme itself, OnionOS's defaults, or MainUI's bundled theme. */
enum class ArtSource { THEME, ONION, MAINUI }

/** Theme colour strings read the way a Miyoo reads them. Results are opaque ARGB. */
object OnionColor {
    private fun hexDigit(c: Char) = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * Stock MainUI (title, hint, list, grid, currentpage, total): a '#' and at least 6 more
     * characters, each of the pairs at 1, 3 and 5 read on its own up to its first non-hex
     * character ("#DDDDDD;" works). Null for anything else: the default is kept.
     */
    fun stock(s: String): Int? {
        if (s.length <= 6 || s[0] != '#') return null
        fun pair(i: Int): Int {
            val hi = hexDigit(s[i]); if (hi < 0) return 0
            val lo = hexDigit(s[i + 1]); return if (lo < 0) hi else hi * 16 + lo
        }
        return (0xFF shl 24) or (pair(1) shl 16) or (pair(3) shl 8) or pair(5)
    }

    /**
     * OnionOS's hex2sdl: one '#' dropped, then strtoul(..., 16) on a 32-bit unsigned long and its
     * low 24 bits ("#000" black, "#14646" 0x014646, "white" black).
     */
    fun hex2sdl(input: String): Int {
        var s = input.removePrefix("#").trimStart()
        var neg = false
        if (s.startsWith("+") || s.startsWith("-")) { neg = s[0] == '-'; s = s.substring(1) }
        if (s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X') && hexDigit(s[2]) >= 0) s = s.substring(2)
        var v = 0L
        for (c in s) {
            val d = hexDigit(c); if (d < 0) break
            v = v * 16 + d
            if (v > 0xFFFFFFFFL) { v = 0xFFFFFFFFL; neg = false; break } // ULONG_MAX on overflow
        }
        if (neg) v = (-v) and 0xFFFFFFFFL
        return (0xFF shl 24) or (v and 0xFFFFFF).toInt()
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
