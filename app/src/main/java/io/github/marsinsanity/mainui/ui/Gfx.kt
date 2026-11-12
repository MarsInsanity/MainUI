package io.github.marsinsanity.mainui.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.theme.FontStyle
import io.github.marsinsanity.mainui.theme.Theme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

object BatteryState {
    @Volatile var level = 100
    @Volatile var charging = false
}

enum class Align { LEFT, CENTER, RIGHT }

/**
 * Renders MainUI elements in OnionOS' 640x480 coordinate space.
 *
 * The logical canvas is always 640 wide when the screen is narrower than 4:3
 * (Retroid Pocket Mini V2: 640x557) and 480 tall when it is wider (16:9:
 * 853x480). Header and footer stay glued to the top/bottom, horizontal bars
 * stretch, and content blocks are centred in the extra space. Every skin image
 * that a theme lacks is drawn procedurally so partial themes still work.
 */
class Gfx {
    lateinit var c: Canvas
    var W = 640f; private set
    var H = 480f; private set
    var scale = 1f; private set
    var now = 0L; private set
    /** Set during draw when something (marquee text) needs another frame. */
    var wantsFrame = false
    /** Smallest delay (ms) after which a scene wants to be redrawn, 0 = none. Cheaper than [wantsFrame]. */
    var redrawIn = 0L

    fun redrawAfter(ms: Long) { if (redrawIn == 0L || ms < redrawIn) redrawIn = ms }

    /** Footer hint areas of the last frame, so touch users can tap "(B) BACK". */
    private val hints = ArrayList<Pair<RectF, Btn>>()
    private val pendingHints = ArrayList<Pair<RectF, Btn>>()

    /** Button whose footer hint is at (x, y) in logical coordinates, if any. */
    fun hintAt(x: Float, y: Float): Btn? = synchronized(hints) {
        hints.firstOrNull { it.first.contains(x, y) }?.second
    }

    val theme: Theme get() = MainUI.themes.current
    private val smooth get() = MainUI.settings.smoothScaling

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    /** Vertical offset that centres 480px tall layouts inside a taller canvas. */
    val cy0 get() = (H - 480f) / 2f
    /** Horizontal offset that centres 640px wide layouts inside a wider canvas. */
    val cx0 get() = (W - 640f) / 2f

    companion object {
        /**
         * Width of each end slice of a selection bar image [imageW] px wide: 24 of its 640 design
         * pixels (enough for rounded corners and an edge marker), at most a quarter of it.
         */
        fun selectionCap(imageW: Int): Int = (imageW * 24 / 640).coerceIn(1, kotlin.math.max(1, imageW / 4))

        /** How many items one page dot stands for so [count] dots of [step] px fit in [room] px. */
        fun dotsPerGroup(count: Int, step: Float, room: Float): Int {
            if (count <= 0 || step <= 0f) return 1
            val fit = kotlin.math.max(1, (room / step).toInt())
            return kotlin.math.max(1, (count + fit - 1) / fit)
        }

        const val HEADER = 60f
        const val FOOTER = 60f
        /** Smallest gap between the battery indicator and the right edge of the screen. */
        const val BATTERY_MARGIN = 16f

        fun logicalSize(screenW: Int, screenH: Int): Pair<Float, Float> {
            val aspect = screenW.toFloat() / screenH
            return if (aspect >= 4f / 3f) (480f * aspect) to 480f else 640f to (640f / aspect)
        }
    }

    fun begin(canvas: Canvas, screenW: Int, screenH: Int) {
        c = canvas
        val (w, h) = logicalSize(screenW, screenH)
        W = w; H = h
        scale = screenW / W
        now = System.currentTimeMillis()
        wantsFrame = false
        redrawIn = 0L
        hintsEndX = 0f
        pendingHints.clear()
        // Scrolling labels not drawn last frame start from their beginning when they come back.
        marqueeStart.keys.retainAll(marqueeSeen)
        marqueeSeen.clear()
        bmpPaint.isFilterBitmap = smooth
        c.save()
        c.scale(scale, scale)
    }

    fun end() {
        c.restore()
        synchronized(hints) { hints.clear(); hints.addAll(pendingHints) }
    }

    // ---- primitives --------------------------------------------------------------------

    fun bmp(b: Bitmap, x: Float, y: Float, w: Float = b.width.toFloat(), h: Float = b.height.toFloat(), alpha: Int = 255) {
        bmpPaint.alpha = alpha
        rect.set(x, y, x + w, y + h)
        c.drawBitmap(b, null, rect, bmpPaint)
        bmpPaint.alpha = 255
    }

    /** Draws [b] scaled to fit inside the box, centred. Returns the drawn rect. */
    fun bmpFit(b: Bitmap, x: Float, y: Float, w: Float, h: Float, alpha: Int = 255, upscale: Boolean = true): RectF {
        var s = min(w / b.width, h / b.height)
        if (!upscale) s = min(s, 1f)
        val dw = b.width * s; val dh = b.height * s
        val dx = x + (w - dw) / 2; val dy = y + (h - dh) / 2
        bmp(b, dx, dy, dw, dh, alpha)
        return RectF(dx, dy, dx + dw, dy + dh)
    }

    /** Blurred silhouettes of icons for [bmpFitGlow], with their offset; dropped with their icon. */
    private val glows = java.util.WeakHashMap<Bitmap, Pair<Bitmap, IntArray>>()
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * [bmpFit] with a soft [color] glow behind the picture, like the selected glyph of a main menu
     * tile in MainUI's theme (a blurred copy under it).
     */
    fun bmpFitGlow(b: Bitmap, x: Float, y: Float, w: Float, h: Float, color: Int): RectF {
        val s = min(w / b.width, h / b.height)
        val dw = b.width * s; val dh = b.height * s
        val dx = x + (w - dw) / 2; val dy = y + (h - dh) / 2
        val made = synchronized(glows) {
            glows[b] ?: runCatching {
                // About 4 px of blur where the icon is drawn, in the icon's own pixels.
                val radius = (4f / s).coerceIn(2f, 24f)
                val off = IntArray(2)
                val p = Paint().apply { maskFilter = android.graphics.BlurMaskFilter(radius, android.graphics.BlurMaskFilter.Blur.NORMAL) }
                b.extractAlpha(p, off) to off
            }.getOrNull()?.also { glows[b] = it }
        } ?: return bmpFit(b, x, y, w, h) // (a picture that can't be read back: no glow)
        val (glow, off) = made
        glowPaint.color = color
        rect.set(dx + off[0] * s, dy + off[1] * s, dx + (off[0] + glow.width) * s, dy + (off[1] + glow.height) * s)
        c.drawBitmap(glow, null, rect, glowPaint)
        bmp(b, dx, dy, dw, dh)
        return RectF(dx, dy, dx + dw, dy + dh)
    }

    /** Draws [b] covering the box (cropped), like a fullscreen game screenshot. */
    fun bmpCover(b: Bitmap, x: Float, y: Float, w: Float, h: Float, alpha: Int = 255) {
        val s = max(w / b.width, h / b.height)
        val sw = w / s; val sh = h / s
        val sx = (b.width - sw) / 2; val sy = (b.height - sh) / 2
        val src = android.graphics.Rect(sx.toInt(), sy.toInt(), (sx + sw).toInt(), (sy + sh).toInt())
        rect.set(x, y, x + w, y + h)
        bmpPaint.alpha = alpha
        c.drawBitmap(b, src, rect, bmpPaint)
        bmpPaint.alpha = 255
    }

    fun fillRect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f) {
        fill.shader = null
        fill.color = color
        rect.set(x, y, x + w, y + h)
        if (radius > 0) c.drawRoundRect(rect, radius, radius, fill) else c.drawRect(rect, fill)
    }

    fun strokeRect(x: Float, y: Float, w: Float, h: Float, color: Int, width: Float, radius: Float = 0f) {
        stroke.color = color; stroke.strokeWidth = width
        rect.set(x, y, x + w, y + h)
        if (radius > 0) c.drawRoundRect(rect, radius, radius, stroke) else c.drawRect(rect, stroke)
    }

    fun circle(cx: Float, cy: Float, r: Float, color: Int) {
        fill.shader = null; fill.color = color
        c.drawCircle(cx, cy, r, fill)
    }

    /** A white check in a green disc of radius [r] (see UiItem.mark). */
    fun checkMark(cx: Float, cy: Float, r: Float, alpha: Int = 255) {
        circle(cx, cy, r, Color.argb(alpha, 0x34, 0xB2, 0x5C))
        stroke.color = Color.argb(alpha, 255, 255, 255); stroke.strokeWidth = r * 0.24f
        stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        path.reset()
        path.moveTo(cx - r * 0.45f, cy + r * 0.02f)
        path.lineTo(cx - r * 0.12f, cy + r * 0.34f)
        path.lineTo(cx + r * 0.48f, cy - r * 0.32f)
        c.drawPath(path, stroke)
    }

    private fun setText(style: FontStyle, size: Float = style.size, color: Int = style.color) {
        tp.typeface = style.typeface
        tp.textSize = size
        tp.color = color
    }

    fun textWidth(s: String, style: FontStyle, size: Float = style.size): Float {
        setText(style, size); return tp.measureText(s)
    }

    /**
     * Draws a single line of text vertically centred on [cy]. When [maxW] is set
     * and the text is too long it is either clipped with a marquee (if
     * [marquee]) or ellipsized. Returns the drawn width.
     */
    fun text(
        s: String, style: FontStyle, x: Float, cy: Float, align: Align = Align.LEFT, maxW: Float = 0f,
        color: Int = style.color, size: Float = style.size, alpha: Int = 255, marquee: Boolean = false,
    ): Float {
        if (s.isEmpty()) return 0f
        setText(style, size, color)
        tp.alpha = (Color.alpha(color) * alpha / 255)
        val fm = tp.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2
        var str = s
        var w = tp.measureText(str)
        if (maxW > 0 && w > maxW) {
            if (marquee) {
                val overflow = w - maxW
                val speed = 40f // px per second
                val pause = 1200L
                val travel = (overflow / speed * 1000).toLong()
                val cycle = pause * 2 + travel
                // From when this label started scrolling (it became selected), not the wall clock:
                // a newly selected name always starts at its beginning.
                marqueeSeen += s
                val t = (now - marqueeStart.getOrPut(s) { now }) % cycle
                val off = when {
                    t < pause -> 0f
                    t < pause + travel -> (t - pause) / 1000f * speed
                    else -> overflow
                }
                wantsFrame = true
                val left = when (align) { Align.LEFT -> x; Align.CENTER -> x - maxW / 2; Align.RIGHT -> x - maxW }
                c.save()
                c.clipRect(left, cy - size, left + maxW, cy + size)
                c.drawText(str, left - off, baseline, tp)
                c.restore()
                return maxW
            }
            str = android.text.TextUtils.ellipsize(str, tp, maxW, android.text.TextUtils.TruncateAt.END).toString()
            w = tp.measureText(str)
        }
        val dx = when (align) { Align.LEFT -> x; Align.CENTER -> x - w / 2; Align.RIGHT -> x - w }
        c.drawText(str, dx, baseline, tp)
        return w
    }

    /** When each scrolling (marquee) label started, and those drawn this frame. */
    private val marqueeStart = HashMap<String, Long>()
    private val marqueeSeen = HashSet<String>()

    /** Multi-line wrapped text, top aligned at [top]. Returns the bottom y. */
    fun paragraph(s: String, style: FontStyle, x: Float, top: Float, maxW: Float, size: Float = style.size,
                  color: Int = style.color, align: Align = Align.LEFT, maxLines: Int = 20): Float {
        setText(style, size, color)
        val lineH = size * 1.25f
        var y = top + lineH / 2
        val wrapped = ArrayList<String>()
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (tp.measureText(tryLine) > maxW && line.isNotEmpty()) { wrapped += line; line = word } else line = tryLine
            }
            wrapped += line
        }
        // More than fits: the last line holds the rest, cut with "...", so it's clear text is missing.
        val shown = if (wrapped.size > maxLines) wrapped.take(maxLines - 1) + wrapped.drop(maxLines - 1).joinToString(" ") else wrapped
        for (line in shown) { text(line, style, x, y, align, maxW, color, size); y += lineH }
        return y - lineH / 2
    }

    // ---- skin helpers ----------------------------------------------------------------------

    fun skin(name: String): Bitmap? = theme.skin(name)

    /** Draws a 640px wide skin bar stretched to the full logical width. */
    private fun bar(b: Bitmap, y: Float, h: Float = b.height.toFloat()) = bmp(b, 0f, y, W, h)

    fun background() {
        val b = skin("background")
        if (b != null) {
            // OnionOS shows background.png turned 180 degrees (themes store it upside down).
            c.save(); c.rotate(180f, W / 2, H / 2)
            when (MainUI.settings.backgroundFit) {
                "stretch" -> bmp(b, 0f, 0f, W, H)
                "fit" -> { c.drawColor(Color.BLACK); bmpFit(b, 0f, 0f, W, H) }
                else -> bmpCover(b, 0f, 0f, W, H)
            }
            c.restore()
            return
        }
        // Built-in background: dark vertical gradient
        fill.shader = LinearGradient(0f, 0f, 0f, H, lighten(theme.bgColor, 0.06f), darken(theme.bgColor, 0.35f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill)
        fill.shader = null
    }

    fun header(title: String?, logo: Boolean = false, showBattery: Boolean = true) {
        val bg = skin("bg-title")
        if (bg != null) bar(bg, 0f, HEADER) else fillRect(0f, 0f, W, HEADER, darken(theme.bgColor, 0.45f))
        if (logo) {
            val l = skin("miyoo-topbar")
            if (l != null) bmp(l, 20f, 30f - l.height / 2f)
            else text("MainUI", theme.title, 20f, 30f, color = Color.WHITE)
        }
        // Right side first: the battery (its width depends on the theme and the percentage),
        // then the clock just left of it, so neither covers the other ("6:52 PM" is wide).
        val batteryLeft = if (showBattery) battery(W - 44f, 30f) else W - BATTERY_MARGIN
        var rightEdge = batteryLeft
        if (MainUI.settings.showClock) {
            val h24 = when (MainUI.settings.clockFormat) {
                "12" -> false
                "system" -> android.text.format.DateFormat.is24HourFormat(MainUI.app)
                else -> true
            }
            val pattern = if (h24) "HH:mm" else "h:mm a"
            val fmt = clockFormat?.takeIf { it.first == pattern && it.second == Locale.getDefault() }?.third
                ?: SimpleDateFormat(pattern, Locale.getDefault()).also { clockFormat = Triple(pattern, Locale.getDefault(), it) }
            // The zone can change while MainUI runs (travel, Settings): follow it every time.
            fmt.timeZone = java.util.TimeZone.getDefault()
            val clock = fmt.format(Date(now))
            val style = theme.battery.let { FontStyle(it.typeface, 22f, it.color) }
            val clockRight = batteryLeft - if (showBattery) 14f else 0f
            text(clock, style, clockRight, 30f, Align.RIGHT)
            rightEdge = clockRight - textWidth(clock, style)
            // Draw again when the minute changes, even on a screen nobody touches.
            redrawAfter(60_000L - now % 60_000L + 50L)
        }
        // The centred title stays clear of the clock / battery on the right (and as far from the left).
        if (title != null) {
            val room = minOf(W - 220f, 2f * (rightEdge - 14f - W / 2)).coerceAtLeast(120f)
            text(title, theme.title, W / 2, 29f, Align.CENTER, maxW = room)
        }
    }

    /** The clock's formatter, made again only when its pattern or the language changes. */
    private var clockFormat: Triple<String, Locale, SimpleDateFormat>? = null

    /** Battery icon + percentage following Onion's batteryPercentage rules. Returns its left edge. */
    fun battery(cx: Float, cy: Float): Float {
        val lvl = BatteryState.level
        val charging = BatteryState.charging
        val name = when {
            charging -> "ic-power-charge-100%"
            lvl < 5 -> "power-0%-icon"
            lvl < 30 -> "power-20%-icon"
            lvl < 60 -> "power-50%-icon"
            lvl < 90 -> "power-80%-icon"
            else -> "power-full-icon"
        }
        val icon = skin(name) ?: if (name == "power-full-icon") skin("power-full-icon_back") else null
        val st = theme.battery
        val visible = st.visible && !charging
        val label = "$lvl%"
        val style = FontStyle(st.typeface, st.size, st.color)
        val iw = icon?.width?.toFloat() ?: 44f
        val ih = icon?.height?.toFloat() ?: 24f
        val tw = if (visible) textWidth(label, style) else 0f
        // Onion composes a surface centred on (cx, cy); reproduce its geometry.
        val totalW = when {
            !visible -> iw
            st.fixed || st.align == "center" -> max(iw, tw)
            else -> iw + 5 + tw
        }
        val left = cx - totalW / 2
        var iconX = left
        var textX = left + iw + 5 + st.offsetX
        if (visible) {
            if (st.fixed) {
                textX = when (st.align) {
                    "right" -> left + iw - tw + st.offsetX
                    "center" -> left + (iw - tw) / 2 + st.offsetX
                    else -> left + st.offsetX
                }
            } else when (st.align) {
                "right" -> { iconX = left + tw + 5; textX = left + st.offsetX }
                "center" -> textX = left + (iw - tw) / 2 + st.offsetX
            }
        }
        // Onion centres the group 44 px from the right edge, which pushes a wide one
        // (percentage next to the icon) off screen: keep it at least 16 px inside.
        val right = max(iconX + iw, if (visible) textX + tw else 0f)
        val overflow = right - (W - BATTERY_MARGIN)
        if (overflow > 0f) { iconX -= overflow; textX -= overflow }
        if (icon != null) bmp(icon, iconX, cy - ih / 2) else drawBatteryIcon(iconX, cy, lvl, charging)
        if (visible) text(label, style, textX, cy + st.offsetY)
        return if (visible) minOf(iconX, textX) else iconX
    }

    private fun drawBatteryIcon(x: Float, cy: Float, lvl: Int, charging: Boolean) {
        val w = 40f; val h = 20f; val y = cy - h / 2
        strokeRect(x, y, w, h, Color.WHITE, 2.5f, 4f)
        fillRect(x + w, cy - 4, 3f, 8f, Color.WHITE, 1f)
        val col = when { charging -> Color.rgb(80, 200, 90); lvl < 20 -> Color.rgb(220, 60, 50); else -> Color.WHITE }
        fillRect(x + 4, y + 4, (w - 8) * lvl.coerceIn(0, 100) / 100f, h - 8, col, 2f)
    }

    /** Footer bar with "(A) LABEL  (B) LABEL" hints, Onion's theme_renderStandardHint. */
    /**
     * The A / B / extra button hints, Onion's theme_renderStandardHint. They are
     * fitted into the bar: tighter spacing first, then slightly smaller, then
     * without the n/total counter ([reserveRight] is its width), and only as a
     * last resort are trailing hints left out (their buttons still work).
     */
    fun footer(aLabel: String?, bLabel: String?, extra: List<Pair<String, String>> = emptyList(), reserveRight: Float = 0f,
               covering: Boolean = false) {
        val bg = skin("tips-bar-bg")
        val y = H - FOOTER
        if (covering) {
            // An overlay (dialog, menu, progress) replaces the bar of the screen below it: repaint
            // the background there first, or that screen's hints show through a see-through bar.
            c.save(); c.clipRect(0f, y, W, H); background(); c.restore()
            // ...and its hints: taps on the bar are for the overlay's buttons only.
            pendingHints.clear()
        }
        if (bg != null) bar(bg, y, FOOTER) else fillRect(0f, y, W, FOOTER, darken(theme.bgColor, 0.45f))
        val cy = H - 30f
        // (a theme whose footer picture has its hints: only its own A / B, as OnionOS draws it)
        val inArt = theme.hintsInArt
        val hideLabels = inArt
        val hints = buildList {
            aLabel?.let { add("A" to it) }
            bLabel?.let { add("B" to it) }
            if (!inArt) addAll(extra)
        }
        val left = 20f
        fun widthOf(n: Int, gap: Float, scale: Float): Float = hints.take(n).sumOf { (b, l) ->
            (buttonIcon(b, 0f, 0f, scale = scale, draw = false) + 5f * scale +
                (if (hideLabels) 0f else textWidth(l, theme.hint, theme.hint.size * scale))).toDouble()
        }.toFloat() + gap * (n - 1).coerceAtLeast(0)
        // (gap, smallest scale, room on the right) tried in order.
        val withCounter = W - left - 20f - reserveRight - (if (reserveRight > 0f) 12f else 0f)
        val noCounter = W - left - 20f
        var n = hints.size
        var gap = 30f; var scale = 1f
        run fit@{
            while (n > 0) {
                for ((g, minScale, room) in listOf(Triple(30f, 1f, withCounter), Triple(16f, 1f, withCounter),
                    Triple(16f, 0.82f, withCounter), Triple(16f, 0.72f, noCounter))) {
                    val w1 = widthOf(n, g, 1f)
                    val s = if (w1 <= room) 1f else ((room - g * (n - 1)) / (w1 - g * (n - 1))).coerceAtMost(1f)
                    if (s >= minScale) { gap = g; scale = s; return@fit }
                }
                if (n <= 2) { gap = 16f; scale = 0.72f; return@fit }
                n-- // drop the last extra hint
            }
        }
        var x = left
        for ((btn, label) in hints.take(n)) {
            val startX = x
            x += buttonIcon(btn, x, cy, scale = scale) + 5f * scale
            if (!hideLabels) x += text(label, theme.hint, x, cy - 1, size = theme.hint.size * scale)
            btnFor(btn)?.let { pendingHints += RectF(startX - 10, y, x + gap / 2, H) to it }
            x += gap
        }
        hintsEndX = x - gap
    }

    /** Right edge of the footer hints drawn this frame (footerStatus avoids overlapping them). */
    private var hintsEndX = 0f

    private fun btnFor(label: String): Btn? = when (label) {
        "A" -> Btn.A; "B" -> Btn.B; "X" -> Btn.X; "Y" -> Btn.Y
        "START" -> Btn.START; "SELECT" -> Btn.SELECT; "MENU" -> Btn.MENU; else -> null
    }

    /**
     * Draws a button glyph (icon-A-54 / icon-B-54 / icon-x / icon-y from the skin)
     * and returns its width. [draw] = false only measures it.
     */
    fun buttonIcon(btn: String, x: Float, cy: Float, size: Float = 44f, scale: Float = 1f, draw: Boolean = true): Float {
        val skinName = when (btn) {
            "A" -> "icon-A-54"; "B" -> "icon-B-54"; "X" -> "icon-x"; "Y" -> "icon-y"
            "MENU" -> "ic-MENU"; "START" -> "icon-START"; "SELECT" -> "icon-SELECT"; else -> null
        }
        val b = skinName?.let { skin(it) }
        if (b != null && btn in setOf("A", "B")) {
            val w = b.width * scale; val h = b.height * scale
            if (draw) bmp(b, x, cy - h / 2f, w, h)
            return w
        }
        val sz = size * scale
        if (b != null) {
            val s = sz / b.height
            if (draw) bmp(b, x, cy - sz / 2, b.width * s, sz)
            return b.width * s
        }
        theme.buttonArt()?.let { return themedButton(it, btn, x, cy, scale, draw) }
        val w = if (btn.length > 1) textWidth(btn, theme.hint, sz * 0.5f) + 20f * scale else sz
        if (draw) {
            fillRect(x, cy - sz / 2, w, sz, theme.accent, 8f * scale)
            text(btn, theme.hint, x + w / 2, cy, Align.CENTER, color = Color.WHITE, size = if (btn.length > 1) sz * 0.5f else sz * 0.8f)
        }
        return w
    }

    private val buttonLetter = FontStyle(android.graphics.Typeface.DEFAULT_BOLD, 20f, Color.WHITE)

    /**
     * A button OnionOS has no picture for (X, Y, START...) made from the theme's A button: its
     * picture without the letter and this one's letter in, or for a word, a pill in its colours.
     */
    private fun themedButton(art: Theme.ButtonArt, btn: String, x: Float, cy: Float,
                             scale: Float, draw: Boolean): Float {
        val w = art.blank.width * scale; val h = art.blank.height * scale
        if (btn.length == 1) {
            if (draw) {
                bmp(art.blank, x, cy - h / 2, w, h)
                text(btn, buttonLetter, x + art.cx * w, cy - h / 2 + art.cy * h, Align.CENTER, color = art.ink,
                    size = (art.inkH * h / 0.72f).coerceIn(h * 0.3f, h * 0.7f))
            }
            return w
        }
        val ph = h * 0.74f
        val size = ph * 0.52f
        val pw = textWidth(btn, buttonLetter, size) + ph * 0.8f
        if (draw) {
            if (art.ring) strokeRect(x, cy - ph / 2, pw, ph, art.fill, max(1.5f, ph * 0.08f), ph / 2)
            else fillRect(x, cy - ph / 2, pw, ph, art.fill, ph / 2)
            text(btn, buttonLetter, x + pw / 2, cy, Align.CENTER, color = if (art.ring) art.fill else art.ink, size = size)
        }
        return pw
    }

    /** Width footerStatus needs for "current/total". */
    fun statusWidth(current: Int, total: Int): Float =
        textWidth("$total", theme.total) + textWidth("${if (total == 0) 0 else current}/", theme.currentPage)

    /** "3/120" at the bottom right, Onion's theme_renderFooterStatus. */
    fun footerStatus(current: Int, total: Int) {
        val cur = if (total == 0) 0 else current
        val cy = H - 31f
        val width = statusWidth(current, total)
        if (W - 20f - width < hintsEndX + 12f) return // no room next to the hints
        val tw = text("$total", theme.total, W - 20f, cy, Align.RIGHT)
        text("$cur/", theme.currentPage, W - 20f - tw, cy, Align.RIGHT)
    }

    /** Page dots (dot-a / dot-n). */
    /**
     * Page dots, one per item. When they don't fit in [maxW] (a long row of systems), one dot
     * stands for a group of items, like pages.
     */
    fun dots(itemCount: Int, activeItem: Int, cy: Float, maxW: Float = W - 40f) {
        val a = skin("dot-a"); val n = skin("dot-n")
        val dw = (a?.width ?: 14).toFloat()
        val gap = if (a != null) 5f else 10f // (OnionOS: dot-a's width + 5 apart)
        val per = dotsPerGroup(itemCount, dw + gap, maxW + gap)
        val count = (itemCount + per - 1) / per
        val active = activeItem / per
        if (count <= 1) return
        val total = count * dw + (count - 1) * gap
        var x = W / 2 - total / 2
        for (i in 0 until count) {
            val b = if (i == active) a else n
            if (b != null) bmp(b, x, cy - b.height / 2f)
            else circle(x + dw / 2, cy, if (i == active) 6f else 4.5f, if (i == active) Color.WHITE else Color.argb(140, 255, 255, 255))
            x += dw + gap
        }
    }

    fun arrow(left: Boolean, x: Float, cy: Float, withBg: Boolean = false) {
        val name = if (withBg) (if (left) "extra/arrowLeft" else "extra/arrowRight") else (if (left) "icon-left-arrow-24" else "icon-right-arrow-24")
        val b = skin(name) ?: if (withBg) skin(if (left) "icon-left-arrow-24" else "icon-right-arrow-24") else null
        if (b != null) { bmp(b, if (left) x else x - b.width, cy - b.height / 2f); return }
        path.reset()
        val s = 11f
        if (left) { path.moveTo(x + s, cy - s); path.lineTo(x, cy); path.lineTo(x + s, cy + s) }
        else { path.moveTo(x - s, cy - s); path.lineTo(x, cy); path.lineTo(x - s, cy + s) }
        stroke.color = Color.WHITE; stroke.strokeWidth = 4f; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        c.drawPath(path, stroke)
    }

    fun toggle(on: Boolean, rightX: Float, cy: Float, alpha: Int = 255): Float {
        val b = skin(if (on) "extra/toggle-on" else "extra/toggle-off")
        if (b != null) { bmp(b, rightX - b.width, cy - b.height / 2f, alpha = alpha); return b.width.toFloat() }
        val w = 56f; val h = 28f
        fillRect(rightX - w, cy - h / 2, w, h, if (on) withAlpha(theme.accent, alpha) else Color.argb(alpha * 90 / 255, 255, 255, 255), h / 2)
        circle(if (on) rightX - h / 2 else rightX - w + h / 2, cy, h / 2 - 4, Color.argb(alpha, 255, 255, 255))
        return w
    }

    private val sliceSrc = android.graphics.Rect()
    private val sliceDst = RectF()

    /**
     * Highlight bar for the selected list row (bg-list-s / bg-list-l), [w] wide. Onion blits the
     * 640 wide image cropped to the list width, which cut its right end off when a game's picture
     * takes the right side. Instead the image is drawn in three slices: its two ends (rounded
     * corners, edge, the marker on the left) as they are, and the middle squeezed or stretched to
     * fit, so a narrower list gets a shorter version of the same bar.
     */
    fun selection(large: Boolean, x: Float, y: Float, w: Float, h: Float) {
        val b = skin(if (large) "bg-list-l" else "bg-list-s")
        if (b == null) { fillRect(x, y, w, h, theme.accent); return }
        val unit = 640f / b.width // theme images are drawn for a 640 wide screen
        if (kotlin.math.abs(w - b.width * unit) < 1f) {
            rect.set(x, y, x + w, y + h); c.drawBitmap(b, null, rect, bmpPaint); return
        }
        val cap = selectionCap(b.width)
        val capW = min(cap * unit, w / 2)
        fun slice(sx0: Int, sx1: Int, dx0: Float, dx1: Float) {
            if (sx1 <= sx0 || dx1 <= dx0) return
            sliceSrc.set(sx0, 0, sx1, b.height); sliceDst.set(dx0, y, dx1, y + h)
            c.drawBitmap(b, sliceSrc, sliceDst, bmpPaint)
        }
        slice(0, cap, x, x + capW)
        slice(cap, b.width - cap, x + capW, x + w - capW)
        slice(b.width - cap, b.width, x + w - capW, x + w)
    }

    // ---- composite widgets -----------------------------------------------------------------

    class ListLayout(val top: Float, val bottom: Float, val left: Float, val right: Float, val previewW: Float)

    /**
     * Onion's theme_renderListCustom: rows anchored to the bottom of the list
     * area, divider lines, selection image, icons, toggles and multi values.
     */
    fun list(list: UiList, top: Float = HEADER, bottom: Float = H - FOOTER, left: Float = 0f, right: Float = W,
             showPreview: Boolean = true, dividers: Boolean = true, stretch: Boolean = false) {
        val large = list.large
        val itemH = if (large) 90f else 60f
        val bgH = if (large) 90f else 56f
        val padding = if (!large && dividers) 4f else 0f
        val rows = max(1, ((bottom - top) / itemH).toInt())
        list.visible = rows
        list.ensureVisible()
        val effectiveItemH = if (stretch) (bottom - top) / rows else itemH
        // Onion anchors rows to the bottom of a 360px area; on taller screens the
        // spare space is split above and below instead of leaving a gap on top.
        val startY = if (stretch) top else listStartY(list.large, top, bottom)
        val width = right - left

        // Preview image of the active item (box art etc.)
        var labelEnd = right
        val active = list.current
        val preview = if (showPreview) active?.preview?.invoke() else null
        if (preview != null) {
            val maxW = 250f
            val maxH = bottom - top - 20f
            val s = min(maxW / preview.width, maxH / preview.height)
            val pw = preview.width * s; val ph = preview.height * s
            val pbg = skin("preview-bg")
            val px = right - pw - 14f; val py = top + (bottom - top - ph) / 2
            if (pbg != null) bmp(pbg, px - 6, py - 6, pw + 12, ph + 12)
            bmp(preview, px, py, pw, ph)
            labelEnd = px - 10f
        }

        val div = if (dividers) skin("div-line-h") else null
        val last = min(list.size, list.scroll + rows)
        for (i in list.scroll until last) {
            val item = list.items[i]
            var y = startY + (i - list.scroll) * effectiveItemH
            if (stretch) y += (effectiveItemH - bgH) / 2
            // (Not across the box art beside the list.)
            if (div != null) bmp(div, left, y, if (preview != null) labelEnd - left + 4 else width, 4f)
            y += padding
            val selected = i == list.active
            if (selected) selection(large, left, y, (if (preview != null) labelEnd - left + 4 else width), bgH)
            val cy = y + bgH / 2
            val alpha = if (item.disabled) 90 else 255
            var x = left + 20f
            val icon = item.icon?.invoke()
            if (icon != null) {
                val maxIcon = if (large) 64f else 40f
                val s = min(1f, min(maxIcon / icon.width, maxIcon / icon.height)).let { if (icon.width < 24) 1f else it }
                val iw = icon.width * s; val ih = icon.height * s
                bmp(icon, x, cy - ih / 2, iw, ih, alpha)
                x += iw + 17f
            }
            var end = labelEnd
            if (item.mark && item.type != ItemType.MULTI) {
                val r = if (large) 14f else 12f
                checkMark(end - 20f - r, cy, r, alpha)
                end -= 2 * r + 16f
            }
            when (item.type) {
                ItemType.TOGGLE -> {
                    val tw = toggle(item.checked, end - 20f, cy, alpha); end -= tw + 30f
                    item.sub?.let { s ->
                        val sw = text(s, theme.list, end, cy, Align.RIGHT, maxW = 200f, size = theme.list.size * 0.75f, alpha = alpha * 200 / 255)
                        end -= sw + 20f
                    }
                }
                ItemType.MULTI -> {
                    val mw = 226f
                    val rightArrowX = end - 20f
                    arrow(false, rightArrowX, cy)
                    arrow(true, rightArrowX - mw - 24f, cy)
                    val v = item.values.getOrNull(item.value) ?: ""
                    text(v, theme.list, rightArrowX - 12f - mw / 2, cy, Align.CENTER, maxW = mw - 20f, alpha = alpha)
                    end = rightArrowX - mw - 34f
                }
                else -> item.sub?.let { s ->
                    val sw = text(s, theme.list, end - 20f, cy, Align.RIGHT, maxW = 220f, size = theme.list.size * 0.8f,
                        alpha = alpha * 200 / 255)
                    end -= sw + 34f
                }
            }
            text(item.label, theme.list, x, cy, maxW = end - x - 10f, alpha = alpha, marquee = selected)
        }
        if (list.size == 0) {
            text("Empty", theme.list, (left + right) / 2, (top + bottom) / 2, Align.CENTER, alpha = 160)
        }
    }

    fun listStartY(large: Boolean, top: Float = HEADER, bottom: Float = H - FOOTER): Float {
        val itemH = if (large) 90f else 60f
        val rows = max(1, ((bottom - top) / itemH).toInt())
        return top + ((bottom - top) - rows * itemH) / 2f
    }

    /** Panel background for pop menus (bg-pop-menu-N) and the in-game menu. */
    fun popPanel(rows: Int, x: Float, y: Float, w: Float, h: Float) {
        val b = skin("bg-pop-menu-${rows.coerceIn(1, 4)}")
        if (b != null) bmp(b, x, y, w, h) else fillRect(x, y, w, h, theme.panel)
    }

    fun dim(alpha: Int = 150) = fillRect(0f, 0f, W, H, Color.argb(alpha, 0, 0, 0))

    /** Onion's theme_renderDialog: pop-bg with title + message. */
    /** Draws a dialog box; returns the box's area (taps outside it dismiss a dialog). */
    fun dialog(title: String, message: String): RectF {
        dim(120)
        val b = skin("pop-bg")
        val pw = 560f; val ph = 280f
        val x = (W - pw) / 2; val y = (H - ph) / 2 - 10
        val box = if (b != null) {
            val bx = (W - b.width) / 2f; val by = (H - b.height) / 2f - 10
            bmp(b, bx, by, b.width.toFloat(), b.height.toFloat())
            RectF(bx, by, bx + b.width, by + b.height)
        } else {
            fillRect(x, y, pw, ph, theme.panel, 14f); strokeRect(x, y, pw, ph, theme.accent, 3f, 14f)
            RectF(x, y, x + pw, y + ph)
        }
        text(title, theme.title, W / 2, y + 50, Align.CENTER, maxW = pw - 40)
        paragraph(message, theme.list, W / 2, y + 100, pw - 60, align = Align.CENTER, maxLines = 5)
        return box
    }

    /** Small centred message box (Onion's info panel / toast). */
    /** Centered message panel; one line per '\n'. */
    fun infoPanel(message: String) {
        val style = theme.list
        // Long lines wrap (an error's reason is at its end); past 5 lines the rest is cut with "...".
        val wrapped = ArrayList<String>()
        for (para in message.split('\n').filter { it.isNotEmpty() }) {
            var line = ""
            for (word in para.split(' ')) {
                val t = if (line.isEmpty()) word else "$line $word"
                if (line.isNotEmpty() && textWidth(t, style) > W - 110f) { wrapped += line; line = word } else line = t
            }
            wrapped += line
        }
        val lines = (if (wrapped.size > 5) wrapped.take(4) + wrapped.drop(4).joinToString(" ") else wrapped).ifEmpty { listOf("") }
        val lineH = style.size * 1.45f
        val tw = min(W - 80f, (lines.maxOf { textWidth(it, style) }) + 60f)
        val h = max(80f, lines.size * lineH + 40f)
        val x = (W - tw) / 2; val y = (H - h) / 2
        fillRect(x, y, tw, h, theme.panel, 12f)
        strokeRect(x, y, tw, h, theme.accent, 2f, 12f)
        val top = y + h / 2 - (lines.size - 1) * lineH / 2
        lines.forEachIndexed { i, l -> text(l, style, W / 2, top + i * lineH, Align.CENTER, maxW = tw - 30f) }
    }

    // ---- procedural artwork for missing theme images ----------------------------------------

    /** Main menu tile replacement for ic-<name>-n/f (156x278). */
    fun menuTile(kind: String, x: Float, y: Float, w: Float, h: Float, selected: Boolean) {
        val art = blankTile(x, y, w, h, selected)
        if (art == null) {
            glyph(kind, x + w / 2, y + h * 0.36f, w * 0.26f, if (selected) Color.WHITE else Color.argb(200, 255, 255, 255))
            return
        }
        val box = tilePictureBox(art, x, y, w, h)
        glyph(kind, box.centerX(), box.centerY(), min(box.width(), box.height()) * 0.42f, art.ink)
    }

    /**
     * An empty main menu tile in the theme's style: MainUI's own theme's bg-home-tile, an OnionOS
     * theme's own Games tile with its picture taken out, else one drawn in the accent. Returns
     * where the picture goes in an OnionOS theme's tile (null: the usual place).
     */
    fun blankTile(x: Float, y: Float, w: Float, h: Float, selected: Boolean): Theme.TileArt? {
        skin(if (selected) "bg-home-tile-f" else "bg-home-tile-n")?.let { bmp(it, x, y, w, h); return null }
        val art = theme.tileArt(selected)
        if (art == null) { menuTileBox(x, y, w, h, selected); return null }
        art.card?.let { bmp(it, x, y, w, h) }
        return art
    }

    private val pictureBox = RectF()

    /** Where [art]'s picture was, kept above the label (195 of the tile's 278) and not too small or big. */
    fun tilePictureBox(art: Theme.TileArt, x: Float, y: Float, w: Float, h: Float): RectF {
        var l = x + art.left * w; var r = x + art.right * w
        var t = y + art.top * h; var b = min(y + art.bottom * h, y + h * (170f / 278f))
        if (b - t < w * 0.4f) { val c = (t + b) / 2; t = c - w * 0.2f; b = c + w * 0.2f }
        val side = min(min(r - l, b - t), w * 0.7f).coerceAtLeast(w * 0.4f)
        val cx = (l + r) / 2; val cy = (t + b) / 2
        l = cx - side / 2; r = cx + side / 2
        pictureBox.set(l, cy - side / 2, r, cy + side / 2)
        return pictureBox
    }

    private val tileFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tileEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tileGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        maskFilter = android.graphics.BlurMaskFilter(3f, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    /**
     * A top-level page tile, drawn the way MainUI's own theme draws them (tools/theme/
     * make_mainui_theme.py, home_tile) in the theme's accent: a rounded card with a vertical
     * gradient; selected, the accent fading darker with a glowing light border and an underline
     * below the label; otherwise faint, with a thin border. For themes without tile artwork.
     */
    fun menuTileBox(x: Float, y: Float, w: Float, h: Float, selected: Boolean) {
        val pad = 6f; val r = 20f
        val a = theme.accent
        val light = tileHighlight(a)
        rect.set(x + pad, y + pad, x + w - pad, y + h - pad)
        if (selected) {
            tileGlow.color = withAlpha(light, 200); tileGlow.strokeWidth = 4f
            c.drawRoundRect(rect, r, r, tileGlow)
        }
        tileFill.shader = if (selected) LinearGradient(0f, rect.top, 0f, rect.bottom,
            withAlpha(tileTop(a), 235), withAlpha(tileBottom(a), 235), Shader.TileMode.CLAMP)
        else LinearGradient(0f, rect.top, 0f, rect.bottom, Color.argb(22, 255, 255, 255), Color.argb(8, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, r, r, tileFill)
        tileFill.shader = null
        tileEdge.color = if (selected) withAlpha(light, 230) else Color.argb(40, 255, 255, 255)
        tileEdge.strokeWidth = if (selected) 2f else 1.2f
        c.drawRoundRect(rect, r, r, tileEdge)
        // The accent underline below where the label sits (236 of 278 in the theme's tiles).
        if (selected) fillRect(x + w / 2 - 18f, y + h * (236f / 278f), 36f, 4f, withAlpha(light, 255), 2f)
    }

    /** Simple vector glyphs for built-in tiles and app icons. */
    fun glyph(kind: String, cx: Float, cy: Float, r: Float, color: Int) {
        stroke.color = color; stroke.strokeWidth = r * 0.16f; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        fill.shader = null; fill.color = color
        path.reset()
        when (kind) {
            "favorite" -> {
                for (i in 0 until 10) {
                    val ang = Math.toRadians((-90 + i * 36).toDouble())
                    val rr = if (i % 2 == 0) r else r * 0.45f
                    val px = cx + (rr * Math.cos(ang)).toFloat(); val py = cy + (rr * Math.sin(ang)).toFloat()
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close(); c.drawPath(path, stroke)
            }
            "game" -> {
                rect.set(cx - r, cy - r * 0.55f, cx + r, cy + r * 0.55f)
                c.drawRoundRect(rect, r * 0.5f, r * 0.5f, stroke)
                c.drawLine(cx - r * 0.6f, cy, cx - r * 0.2f, cy, stroke)
                c.drawLine(cx - r * 0.4f, cy - r * 0.2f, cx - r * 0.4f, cy + r * 0.2f, stroke)
                c.drawCircle(cx + r * 0.35f, cy - r * 0.1f, r * 0.08f, fill)
                c.drawCircle(cx + r * 0.6f, cy + r * 0.12f, r * 0.08f, fill)
            }
            "app" -> {
                val s = r * 0.8f; val gap = r * 0.2f
                for (ix in 0..1) for (iy in 0..1) {
                    val x0 = cx - s - gap / 2 + ix * (s + gap); val y0 = cy - s - gap / 2 + iy * (s + gap)
                    rect.set(x0, y0, x0 + s, y0 + s); c.drawRoundRect(rect, s * 0.25f, s * 0.25f, stroke)
                }
            }
            "setting" -> {
                c.drawCircle(cx, cy, r * 0.32f, stroke)
                for (i in 0 until 8) {
                    val ang = Math.toRadians((i * 45).toDouble())
                    c.drawLine(cx + (r * 0.6f * Math.cos(ang)).toFloat(), cy + (r * 0.6f * Math.sin(ang)).toFloat(),
                        cx + (r * 0.95f * Math.cos(ang)).toFloat(), cy + (r * 0.95f * Math.sin(ang)).toFloat(), stroke)
                }
                c.drawCircle(cx, cy, r * 0.62f, stroke)
            }
            "recent" -> {
                c.drawCircle(cx, cy, r * 0.9f, stroke)
                c.drawLine(cx, cy, cx, cy - r * 0.55f, stroke)
                c.drawLine(cx, cy, cx + r * 0.4f, cy + r * 0.25f, stroke)
            }
            "emulators" -> {
                // a handheld
                rect.set(cx - r * 0.62f, cy - r, cx + r * 0.62f, cy + r); c.drawRoundRect(rect, r * 0.18f, r * 0.18f, stroke)
                rect.set(cx - r * 0.42f, cy - r * 0.78f, cx + r * 0.42f, cy - r * 0.08f); c.drawRoundRect(rect, r * 0.06f, r * 0.06f, stroke)
                c.drawLine(cx - r * 0.42f, cy + r * 0.42f, cx - r * 0.1f, cy + r * 0.42f, stroke)
                c.drawLine(cx - r * 0.26f, cy + r * 0.26f, cx - r * 0.26f, cy + r * 0.58f, stroke)
                c.drawCircle(cx + r * 0.4f, cy + r * 0.32f, r * 0.08f, fill); c.drawCircle(cx + r * 0.22f, cy + r * 0.52f, r * 0.08f, fill)
            }
            "expert", "retroarch" -> {
                rect.set(cx - r, cy - r * 0.7f, cx + r, cy + r * 0.7f); c.drawRoundRect(rect, r * 0.15f, r * 0.15f, stroke)
                path.moveTo(cx - r * 0.6f, cy - r * 0.3f); path.lineTo(cx - r * 0.2f, cy); path.lineTo(cx - r * 0.6f, cy + r * 0.3f)
                c.drawPath(path, stroke)
                c.drawLine(cx, cy + r * 0.3f, cx + r * 0.5f, cy + r * 0.3f, stroke)
            }
            "search" -> {
                c.drawCircle(cx - r * 0.15f, cy - r * 0.15f, r * 0.55f, stroke)
                c.drawLine(cx + r * 0.25f, cy + r * 0.25f, cx + r * 0.8f, cy + r * 0.8f, stroke)
            }
            "android" -> {
                rect.set(cx - r * 0.7f, cy - r * 0.55f, cx + r * 0.7f, cy + r * 0.85f)
                c.drawArc(RectF(cx - r * 0.7f, cy - r * 0.9f, cx + r * 0.7f, cy + r * 0.2f), 180f, 180f, false, stroke)
                c.drawRoundRect(RectF(cx - r * 0.7f, cy - r * 0.2f, cx + r * 0.7f, cy + r * 0.8f), r * 0.15f, r * 0.15f, stroke)
                c.drawCircle(cx - r * 0.3f, cy - r * 0.45f, r * 0.07f, fill); c.drawCircle(cx + r * 0.3f, cy - r * 0.45f, r * 0.07f, fill)
            }
            "process" -> {
                for (i in 0..2) { val yy = cy - r * 0.6f + i * r * 0.6f; c.drawLine(cx - r * 0.8f, yy, cx + r * 0.8f, yy, stroke) }
                c.drawCircle(cx - r * 0.3f, cy - r * 0.6f, r * 0.12f, fill); c.drawCircle(cx + r * 0.3f, cy, r * 0.12f, fill)
                c.drawCircle(cx - r * 0.1f, cy + r * 0.6f, r * 0.12f, fill)
            }
            "theme" -> {
                c.drawCircle(cx, cy, r * 0.9f, stroke)
                c.drawCircle(cx - r * 0.35f, cy - r * 0.3f, r * 0.14f, fill); c.drawCircle(cx + r * 0.3f, cy - r * 0.35f, r * 0.14f, fill)
                c.drawCircle(cx + r * 0.4f, cy + r * 0.25f, r * 0.14f, fill)
            }
            "switcher" -> {
                rect.set(cx - r * 0.9f, cy - r * 0.55f, cx + r * 0.5f, cy + r * 0.45f); c.drawRect(rect, stroke)
                c.drawLine(cx - r * 0.6f, cy + r * 0.75f, cx + r * 0.9f, cy + r * 0.75f, stroke)
                c.drawLine(cx + r * 0.9f, cy + r * 0.75f, cx + r * 0.9f, cy - r * 0.25f, stroke)
            }
            "scraper" -> {
                rect.set(cx - r * 0.8f, cy - r * 0.8f, cx + r * 0.8f, cy + r * 0.8f); c.drawRoundRect(rect, r * 0.15f, r * 0.15f, stroke)
                path.moveTo(cx - r * 0.6f, cy + r * 0.5f); path.lineTo(cx - r * 0.15f, cy - r * 0.05f)
                path.lineTo(cx + r * 0.15f, cy + r * 0.25f); path.lineTo(cx + r * 0.6f, cy - r * 0.3f); c.drawPath(path, stroke)
            }
            "folder" -> {
                path.moveTo(cx - r, cy - r * 0.6f); path.lineTo(cx - r * 0.3f, cy - r * 0.6f); path.lineTo(cx - r * 0.1f, cy - r * 0.4f)
                path.lineTo(cx + r, cy - r * 0.4f); path.lineTo(cx + r, cy + r * 0.7f); path.lineTo(cx - r, cy + r * 0.7f); path.close()
                c.drawPath(path, stroke)
            }
            "hidden" -> {
                c.drawOval(RectF(cx - r, cy - r * 0.5f, cx + r, cy + r * 0.5f), stroke)
                c.drawCircle(cx, cy, r * 0.22f, fill)
                c.drawLine(cx - r * 0.9f, cy + r * 0.8f, cx + r * 0.9f, cy - r * 0.8f, stroke)
            }
            else -> c.drawCircle(cx, cy, r * 0.8f, stroke)
        }
    }

    /** Built-in icon for a system without theme artwork: rounded tile with its short name. */
    fun systemTile(short: String, x: Float, y: Float, w: Float, h: Float, selected: Boolean, alpha: Int = 255) {
        val bg = if (selected) withAlpha(theme.accent, alpha) else Color.argb(alpha * 70 / 255, 255, 255, 255)
        fillRect(x, y, w, h, bg, min(w, h) * 0.14f)
        val size = min(h * 0.38f, w * 1.6f / max(2, short.length))
        text(short, theme.title, x + w / 2, y + h / 2, Align.CENTER, maxW = w - 10, size = size,
            color = Color.WHITE, alpha = alpha)
    }

    /** A generated square bitmap of a glyph, used as list icon for built-in apps. */
    fun glyphBitmap(kind: String, size: Int = 64): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val saved = if (::c.isInitialized) c else null
        c = Canvas(b)
        fillRect(0f, 0f, size.toFloat(), size.toFloat(), withAlpha(theme.accent, 255), size * 0.22f)
        glyph(kind, size / 2f, size / 2f, size * 0.32f, Color.WHITE)
        if (saved != null) c = saved
        return b
    }
}

fun withAlpha(color: Int, a: Int) = Color.argb(a.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

/** Opaque [color] with its red, green and blue multiplied (plain bit maths: testable off-device). */
private fun scaled(color: Int, r: Float, g: Float, b: Float): Int {
    fun ch(shift: Int, f: Float) = ((((color shr shift) and 0xFF) * f).toInt().coerceIn(0, 255)) shl shift
    return (0xFF shl 24) or ch(16, r) or ch(8, g) or ch(0, b)
}

/** A selected tile's gradient and edge from the theme's [accent] (MainUI's #3A96FF: 40,96,FF / 14,3E,96 / 56,DC,FF). */
fun tileTop(accent: Int) = scaled(accent, 1.1f, 1f, 1f)
fun tileBottom(accent: Int) = scaled(accent, 0.35f, 0.42f, 0.59f)
fun tileHighlight(accent: Int) = scaled(accent, 1.48f, 1.47f, 1f)
fun darken(color: Int, f: Float) = Color.rgb((Color.red(color) * (1 - f)).toInt(), (Color.green(color) * (1 - f)).toInt(), (Color.blue(color) * (1 - f)).toInt())
fun lighten(color: Int, f: Float) = Color.rgb((Color.red(color) + (255 - Color.red(color)) * f).toInt(),
    (Color.green(color) + (255 - Color.green(color)) * f).toInt(), (Color.blue(color) + (255 - Color.blue(color)) * f).toInt())
