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
        const val HEADER = 60f
        const val FOOTER = 60f

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
                val t = now % cycle
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

    /** Multi-line wrapped text, top aligned at [top]. Returns the bottom y. */
    fun paragraph(s: String, style: FontStyle, x: Float, top: Float, maxW: Float, size: Float = style.size,
                  color: Int = style.color, align: Align = Align.LEFT, maxLines: Int = 20): Float {
        setText(style, size, color)
        val lineH = size * 1.25f
        var y = top + lineH / 2
        var lines = 0
        for (para in s.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                val tryLine = if (line.isEmpty()) word else "$line $word"
                if (tp.measureText(tryLine) > maxW && line.isNotEmpty()) {
                    text(line, style, x, y, align, maxW, color, size); y += lineH; line = word
                    if (++lines >= maxLines) return y
                } else line = tryLine
            }
            text(line, style, x, y, align, maxW, color, size); y += lineH
            if (++lines >= maxLines) return y
        }
        return y - lineH / 2
    }

    // ---- skin helpers ----------------------------------------------------------------------

    fun skin(name: String): Bitmap? = theme.skin(name)

    /** Draws a 640px wide skin bar stretched to the full logical width. */
    private fun bar(b: Bitmap, y: Float, h: Float = b.height.toFloat()) = bmp(b, 0f, y, W, h)

    fun background() {
        val b = skin("background")
        if (b != null) {
            when (MainUI.settings.backgroundFit) {
                "stretch" -> bmp(b, 0f, 0f, W, H)
                "fit" -> { c.drawColor(Color.BLACK); bmpFit(b, 0f, 0f, W, H) }
                else -> bmpCover(b, 0f, 0f, W, H)
            }
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
        if (title != null) text(title, theme.title, W / 2, 29f, Align.CENTER, maxW = W - 220f)
        if (showBattery) battery(W - 44f, 30f)
        if (MainUI.settings.showClock) {
            val t = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            text(t, theme.battery.let { FontStyle(it.typeface, 22f, it.color) }, W - 84f, 30f, Align.RIGHT)
        }
    }

    /** Battery icon + percentage following Onion's batteryPercentage rules. */
    fun battery(cx: Float, cy: Float) {
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
        if (icon != null) bmp(icon, iconX, cy - ih / 2) else drawBatteryIcon(iconX, cy, lvl, charging)
        if (visible) text(label, style, textX, cy + st.offsetY)
    }

    private fun drawBatteryIcon(x: Float, cy: Float, lvl: Int, charging: Boolean) {
        val w = 40f; val h = 20f; val y = cy - h / 2
        strokeRect(x, y, w, h, Color.WHITE, 2.5f, 4f)
        fillRect(x + w, cy - 4, 3f, 8f, Color.WHITE, 1f)
        val col = when { charging -> Color.rgb(80, 200, 90); lvl < 20 -> Color.rgb(220, 60, 50); else -> Color.WHITE }
        fillRect(x + 4, y + 4, (w - 8) * lvl.coerceIn(0, 100) / 100f, h - 8, col, 2f)
    }

    /** Footer bar with "(A) LABEL  (B) LABEL" hints, Onion's theme_renderStandardHint. */
    fun footer(aLabel: String?, bLabel: String?, extra: List<Pair<String, String>> = emptyList()) {
        val bg = skin("tips-bar-bg")
        val y = H - FOOTER
        if (bg != null) bar(bg, y, FOOTER) else fillRect(0f, y, W, FOOTER, darken(theme.bgColor, 0.45f))
        val cy = H - 30f
        var x = 20f
        val hideLabels = theme.hideHintLabels
        fun hintPair(btn: String, label: String?) {
            if (label == null) return
            val startX = x
            x += buttonIcon(btn, x, cy) + 5
            if (!hideLabels) x += text(label, theme.hint, x, cy - 1) + 30 else x += 25
            btnFor(btn)?.let { pendingHints += RectF(startX - 10, y, x - 10, H) to it }
        }
        hintPair("A", aLabel)
        hintPair("B", bLabel)
        for ((b, l) in extra) hintPair(b, l)
        hintsEndX = x - 30f
    }

    /** Right edge of the footer hints drawn this frame (footerStatus avoids overlapping them). */
    private var hintsEndX = 0f

    private fun btnFor(label: String): Btn? = when (label) {
        "A" -> Btn.A; "B" -> Btn.B; "X" -> Btn.X; "Y" -> Btn.Y
        "START" -> Btn.START; "SELECT" -> Btn.SELECT; "MENU" -> Btn.MENU; else -> null
    }

    /** Draws a button glyph (icon-A-54 / icon-B-54 / icon-x / icon-y from the skin). Returns its width. */
    fun buttonIcon(btn: String, x: Float, cy: Float, size: Float = 44f): Float {
        val skinName = when (btn) {
            "A" -> "icon-A-54"; "B" -> "icon-B-54"; "X" -> "icon-x"; "Y" -> "icon-y"
            "MENU" -> "ic-MENU"; "START" -> "icon-START"; else -> null
        }
        val b = skinName?.let { skin(it) }
        if (b != null && btn in setOf("A", "B")) { bmp(b, x, cy - b.height / 2f); return b.width.toFloat() }
        if (b != null) {
            val s = size / b.height
            bmp(b, x, cy - size / 2, b.width * s, size); return b.width * s
        }
        val w = if (btn.length > 1) textWidth(btn, theme.hint, size * 0.5f) + 20f else size
        fillRect(x, cy - size / 2, w, size, theme.accent, 8f)
        text(btn, theme.hint, x + w / 2, cy, Align.CENTER, color = Color.WHITE, size = if (btn.length > 1) size * 0.5f else size * 0.8f)
        return w
    }

    /** "3/120" at the bottom right, Onion's theme_renderFooterStatus. */
    fun footerStatus(current: Int, total: Int) {
        val cur = if (total == 0) 0 else current
        val cy = H - 31f
        val width = textWidth("$total", theme.total) + textWidth("$cur/", theme.currentPage)
        if (W - 20f - width < hintsEndX + 12f) return // no room next to the hints
        val tw = text("$total", theme.total, W - 20f, cy, Align.RIGHT)
        text("$cur/", theme.currentPage, W - 20f - tw, cy, Align.RIGHT)
    }

    /** Page dots (dot-a / dot-n). */
    fun dots(count: Int, active: Int, cy: Float) {
        if (count <= 1) return
        val a = skin("dot-a"); val n = skin("dot-n")
        val dw = (a?.width ?: 14).toFloat()
        val gap = 10f
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

    /** Highlight bar for the selected list row (bg-list-s / bg-list-l). */
    fun selection(large: Boolean, x: Float, y: Float, w: Float, h: Float) {
        val b = skin(if (large) "bg-list-l" else "bg-list-s")
        if (b != null) {
            // Onion blits the 640 wide image cropped to the list width
            val src = android.graphics.Rect(0, 0, min(b.width, (w).toInt()).coerceAtLeast(1), b.height)
            rect.set(x, y, x + w, y + h)
            if (w > b.width) c.drawBitmap(b, null, rect, bmpPaint) else c.drawBitmap(b, src, RectF(x, y, x + src.width(), y + h), bmpPaint)
            return
        }
        fillRect(x, y, w, h, theme.accent)
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
            if (div != null) bmp(div, left, y, width, 4f)
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
    fun dialog(title: String, message: String) {
        dim(120)
        val b = skin("pop-bg")
        val pw = 560f; val ph = 280f
        val x = (W - pw) / 2; val y = (H - ph) / 2 - 10
        if (b != null) bmp(b, (W - b.width) / 2f, (H - b.height) / 2f - 10, b.width.toFloat(), b.height.toFloat())
        else { fillRect(x, y, pw, ph, theme.panel, 14f); strokeRect(x, y, pw, ph, theme.accent, 3f, 14f) }
        text(title, theme.title, W / 2, y + 50, Align.CENTER, maxW = pw - 40)
        paragraph(message, theme.list, W / 2, y + 100, pw - 60, align = Align.CENTER, maxLines = 5)
    }

    /** Small centred message box (Onion's info panel / toast). */
    fun infoPanel(message: String) {
        val style = theme.list
        val tw = min(W - 80f, textWidth(message, style) + 60f)
        val h = 80f
        val x = (W - tw) / 2; val y = (H - h) / 2
        fillRect(x, y, tw, h, theme.panel, 12f)
        strokeRect(x, y, tw, h, theme.accent, 2f, 12f)
        text(message, style, W / 2, y + h / 2, Align.CENTER, maxW = tw - 30f)
    }

    // ---- procedural artwork for missing theme images ----------------------------------------

    /** Main menu tile replacement for ic-<name>-n/f (156x278). */
    fun menuTile(kind: String, x: Float, y: Float, w: Float, h: Float, selected: Boolean) {
        val bgCol = if (selected) withAlpha(theme.accent, 235) else Color.argb(38, 255, 255, 255)
        fillRect(x + 6, y + 6, w - 12, h - 12, bgCol, 18f)
        val cx = x + w / 2; val cy = y + h * 0.36f
        glyph(kind, cx, cy, w * 0.26f, if (selected) Color.WHITE else Color.argb(200, 255, 255, 255))
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
fun darken(color: Int, f: Float) = Color.rgb((Color.red(color) * (1 - f)).toInt(), (Color.green(color) * (1 - f)).toInt(), (Color.blue(color) * (1 - f)).toInt())
fun lighten(color: Int, f: Float) = Color.rgb((Color.red(color) + (255 - Color.red(color)) * f).toInt(),
    (Color.green(color) + (255 - Color.green(color)) * f).toInt(), (Color.blue(color) + (255 - Color.blue(color)) * f).toInt())
