package io.github.marsinsanity.mainui.ui

import android.graphics.Color
import io.github.marsinsanity.mainui.MainUI

/**
 * Generic Onion list screen: header title, list, footer hints and page status.
 * Most MainUI screens (game lists, apps, settings...) are built on this.
 */
open class ListScene(
    host: SceneHost,
    var title: String,
    large: Boolean = false,
) : Scene(host) {
    val list = UiList(large)
    open var aLabel: String? = "SELECT"
    open var bLabel: String? = "BACK"
    open val extraHints: List<Pair<String, String>> get() = emptyList()
    open val showStatus = true
    open val showPreview = true

    override fun draw(g: Gfx) {
        g.background()
        g.list(list, showPreview = showPreview && MainUI.settings.showGameArt)
        g.header(title)
        g.footer(if (list.current?.disabled == true) null else aLabel, bLabel, extraHints,
            reserveRight = if (showStatus) g.statusWidth(list.active + 1, list.size) else 0f)
        if (showStatus) g.footerStatus(list.active + 1, list.size)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean = when (b) {
        Btn.UP -> list.up(repeat)
        Btn.DOWN -> list.down(repeat)
        Btn.L1 -> list.pageUp()
        Btn.R1 -> list.pageDown()
        Btn.L2 -> list.letter(false)
        Btn.R2 -> list.letter(true)
        Btn.LEFT -> if (list.current?.type == ItemType.MULTI || list.current?.type == ItemType.TOGGLE) list.change(-1) else list.pageUp()
        Btn.RIGHT -> if (list.current?.type == ItemType.MULTI || list.current?.type == ItemType.TOGGLE) list.change(1) else list.pageDown()
        Btn.A -> { if (!repeat) activate(); true }
        Btn.B -> { if (!repeat) back(); true }
        else -> false
    }

    open fun back() = close()

    // ---- touch scrolling: drag moves the list a row at a time, fling keeps going ----
    private var dragAcc = 0f
    private val itemH get() = if (list.large) 90f else 60f

    /** Scrolls the visible window by [rows], keeping the selection inside it. */
    fun scrollBy(rows: Int): Boolean {
        if (list.size <= list.visible || rows == 0) return false
        val old = list.scroll
        list.scroll = (list.scroll + rows).coerceIn(0, list.size - list.visible)
        list.active = list.active.coerceIn(list.scroll, list.scroll + list.visible - 1)
        return list.scroll != old
    }

    override fun onDrag(dx: Float, dy: Float): Boolean {
        if (dragAcc == 0f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) return false
        dragAcc += dy
        var moved = false
        while (dragAcc <= -itemH) { moved = scrollBy(1) || moved; dragAcc += itemH }
        while (dragAcc >= itemH) { moved = scrollBy(-1) || moved; dragAcc -= itemH }
        return true
    }

    override fun onDragEnd() { dragAcc = 0f }

    override fun onFling(vx: Float, vy: Float): Boolean {
        // ~0.3 s of travel at the fling speed
        val rows = (-vy * 0.3f / itemH).toInt()
        dragAcc = 0f
        return scrollBy(rows)
    }

    override fun onSwipe(direction: Btn): Boolean = when (direction) {
        Btn.UP -> scrollBy(-list.visible) || list.pageUp()
        Btn.DOWN -> scrollBy(list.visible) || list.pageDown()
        else -> false
    }

    open fun activate() {
        val it = list.current ?: return
        if (it.disabled) return
        when (it.type) {
            ItemType.TOGGLE -> list.change(1)
            ItemType.MULTI -> if (it.onSelect != null) it.onSelect?.invoke(it) else list.change(1)
            else -> it.onSelect?.invoke(it)
        }
    }

    override fun onTap(x: Float, y: Float): Boolean {
        // Map the tap to a row using the same geometry as Gfx.list
        val itemH = if (list.large) 90f else 60f
        val bottom = host.logicalHeight - Gfx.FOOTER
        val rows = maxOf(1, ((bottom - Gfx.HEADER) / itemH).toInt())
        val startY = Gfx.HEADER + ((bottom - Gfx.HEADER) - rows * itemH) / 2f
        if (y < startY || y > bottom) {
            if (y > bottom) { activate(); return true }
            return false
        }
        val idx = list.scroll + ((y - startY) / itemH).toInt()
        if (idx !in 0 until list.size) return false
        if (idx == list.active) activate() else list.active = idx
        return true
    }
}

/** Yes/No (or OK only) dialog, Onion's theme_renderDialog. */
class DialogScene(
    host: SceneHost,
    private val title: String,
    private val message: String,
    private val confirmLabel: String? = "CONFIRM",
    private val cancelLabel: String? = "CANCEL",
    private val onConfirm: () -> Unit = {},
) : Scene(host) {
    override val isOverlay = true

    override fun draw(g: Gfx) {
        g.dialog(title, message)
        g.footer(confirmLabel, cancelLabel, covering = true)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (repeat) return false
        when (b) {
            Btn.A -> { close(); onConfirm() }
            Btn.B -> close()
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean { close(); onConfirm(); return true }
}

/** "Working..." panel over the current screen; swallows input until the caller pops it. */
class BusyScene(host: SceneHost, @Volatile var text: String, private val onCancel: (() -> Unit)? = null) : Scene(host) {
    override val isOverlay = true
    @Volatile private var cancelled = false
    override fun draw(g: Gfx) {
        g.dim(90)
        g.infoPanel(text)
        if (onCancel != null) g.footer(null, if (cancelled) null else "STOP", covering = true)
    }
    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.B && !repeat && onCancel != null && !cancelled) { cancelled = true; onCancel.invoke(); host.invalidate() }
        return true
    }
    override fun onTap(x: Float, y: Float) = true
}

/** Small options menu over the current screen (Onion pop menu, bg-pop-menu-N). */
class PopMenuScene(
    host: SceneHost,
    private val title: String?,
    items: List<UiItem>,
) : Scene(host) {
    override val isOverlay = true
    val list = UiList(false).apply { set(items, false) }

    override fun draw(g: Gfx) {
        g.dim(110)
        val rows = minOf(list.size, 6)
        val w = 400f
        val h = rows * 60f + 10f
        val x = (g.W - w) / 2
        val top = (g.H - h) / 2 + (if (title != null) 20f else 0f)
        if (title != null) {
            g.fillRect(x, top - 50f, w, 50f, darken(g.theme.panel, 0.2f))
            g.text(title, g.theme.title, g.W / 2, top - 25f, Align.CENTER, maxW = w - 20f, size = g.theme.title.size * 0.8f)
        }
        g.popPanel(rows, x, top, w, h)
        panel = android.graphics.RectF(x, top, x + w, top + h)
        g.list(list, top + 5f, top + h - 5f, x, x + w, showPreview = false, dividers = false, stretch = true)
        g.footer("SELECT", "BACK", covering = true)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean = when (b) {
        Btn.UP -> list.up(repeat)
        Btn.DOWN -> list.down(repeat)
        Btn.LEFT -> list.change(-1)
        Btn.RIGHT -> list.change(1)
        Btn.A -> {
            if (!repeat) list.current?.takeIf { !it.disabled }?.let { item ->
                if (item.type == ItemType.TOGGLE) list.change(1) else { close(); item.onSelect?.invoke(item) }
            }
            true
        }
        Btn.B, Btn.SELECT, Btn.MENU -> { if (!repeat) close(); true }
        else -> false
    }

    private var panel = android.graphics.RectF()

    override fun onTap(x: Float, y: Float): Boolean {
        if (!panel.contains(x, y)) { close(); return true }
        val rows = minOf(list.size, 6)
        val idx = list.scroll + ((y - panel.top - 5f) / ((panel.height() - 10f) / rows)).toInt()
        if (idx !in 0 until list.size) return false
        list.active = idx
        return onKey(Btn.A, false)
    }

    override fun onDrag(dx: Float, dy: Float) = false
}

/**
 * Gamepad on-screen keyboard used for search and scraper credentials.
 * A types, B deletes (or closes when empty), X space, Y shift, START confirms,
 * L1/R1 move the cursor.
 */
class KeyboardScene(
    host: SceneHost,
    private val title: String,
    initial: String = "",
    private val password: Boolean = false,
    private val onDone: (String) -> Unit,
) : Scene(host) {
    override val isOverlay = true
    private val sb = StringBuilder(initial)
    private var cursor = initial.length
    private var shift = false
    private var row = 1
    private var col = 0

    private val rowsLower = listOf("1234567890-", "qwertyuiop'", "asdfghjkl:/", "zxcvbnm,._@")
    private val rowsUpper = listOf("!#$%&*()=+?", "QWERTYUIOP\"", "ASDFGHJKL;\\", "ZXCVBNM<>!~")
    private val specials = listOf("SHIFT", "SPACE", "DEL", "CANCEL", "OK")
    private val specialWidths = listOf(2f, 3.5f, 1.75f, 2f, 1.75f)   // in key widths

    private fun rows() = if (shift) rowsUpper else rowsLower
    private val rowCount get() = rows().size + 1

    private fun colsIn(r: Int) = if (r < rows().size) rows()[r].length else specials.size

    override fun draw(g: Gfx) {
        // A screen of its own: the one below must not show through the keys or the hints.
        g.background()
        g.dim(60)
        g.header(title)
        // input box
        val inTop = Gfx.HEADER + 14f
        val inBg = g.skin("bg-input")
        val bx = 40f; val bw = g.W - 80f
        if (inBg != null) g.bmp(inBg, bx, inTop, bw, 60f) else g.fillRect(bx, inTop, bw, 60f, Color.argb(235, 255, 255, 255), 10f)
        val shown = if (password) "•".repeat(sb.length) else sb.toString()
        val before = shown.substring(0, cursor.coerceAtMost(shown.length))
        val textColor = if (inBg != null) g.theme.list.color else Color.BLACK
        val tw = g.textWidth(before, g.theme.list)
        val scrollX = maxOf(0f, tw - (bw - 40f))
        g.c.save(); g.c.clipRect(bx + 10, inTop, bx + bw - 10, inTop + 60f)
        g.text(shown, g.theme.list, bx + 20f - scrollX, inTop + 30f, color = textColor)
        if ((g.now / 500) % 2 == 0L) g.fillRect(bx + 20f + tw - scrollX, inTop + 14f, 3f, 32f, textColor)
        g.c.restore()
        g.redrawAfter(500 - g.now % 500) // cursor blink

        val keyW = 52f; val gap = 6f
        // Five rows fill the space between the input box and the footer.
        var y = inTop + 60f + 16f
        val keyH = ((g.H - Gfx.FOOTER - 12f - y) - gap * (rowCount - 1)) / rowCount
        keyRects.clear()
        for (r in 0 until rowCount) {
            val n = colsIn(r)
            val widths = if (r < rows().size) List(n) { keyW } else specialWidths.map { it * keyW }
            val total = widths.sum() + gap * (n - 1)
            var x = (g.W - total) / 2
            for (cI in 0 until n) {
                val sel = r == row && cI == col
                val kb = g.skin(if (sel) "btn-bg-f" else "btn-bg-n")
                if (kb != null) g.bmp(kb, x, y, widths[cI], keyH)
                else g.fillRect(x, y, widths[cI], keyH, if (sel) g.theme.accent else Color.argb(90, 255, 255, 255), 8f)
                keyRects += Triple(android.graphics.RectF(x, y, x + widths[cI], y + keyH), r, cI)
                val label = if (r < rows().size) rows()[r][cI].toString() else specials[cI]
                g.text(label, g.theme.list, x + widths[cI] / 2, y + keyH / 2, Align.CENTER,
                    size = if (label.length > 1) 19f else 26f, color = Color.WHITE)
                x += widths[cI] + gap
            }
            y += keyH + gap
        }
        g.footer("TYPE", "DEL", listOf("START" to "OK", "SELECT" to "CANCEL", "Y" to "SHIFT"), covering = true)
    }

    private val keyRects = ArrayList<Triple<android.graphics.RectF, Int, Int>>()

    override fun onTap(x: Float, y: Float): Boolean {
        val hit = keyRects.firstOrNull { it.first.contains(x, y) } ?: return false
        row = hit.second; col = hit.third
        press()
        return true
    }

    private fun type(ch: String) { sb.insert(cursor, ch); cursor += ch.length }

    private fun press() {
        if (row < rows().size) { type(rows()[row][col].toString()); return }
        when (specials[col]) {
            "SHIFT" -> shift = !shift
            "SPACE" -> type(" ")
            "DEL" -> delete()
            "CANCEL" -> close()
            "OK" -> done()
        }
    }

    private fun delete() { if (cursor > 0) { sb.deleteCharAt(cursor - 1); cursor-- } }
    private fun done() { close(); onDone(sb.toString()) }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        when (b) {
            Btn.UP -> { row = (row - 1 + rowCount) % rowCount; col = col.coerceAtMost(colsIn(row) - 1) }
            Btn.DOWN -> { row = (row + 1) % rowCount; col = col.coerceAtMost(colsIn(row) - 1) }
            Btn.LEFT -> col = (col - 1 + colsIn(row)) % colsIn(row)
            Btn.RIGHT -> col = (col + 1) % colsIn(row)
            Btn.A -> press()
            Btn.B -> if (sb.isEmpty() && !repeat) close() else delete()
            Btn.X -> type(" ")
            Btn.Y -> if (!repeat) shift = !shift
            Btn.L1 -> cursor = (cursor - 1).coerceAtLeast(0)
            Btn.R1 -> cursor = (cursor + 1).coerceAtMost(sb.length)
            Btn.START -> if (!repeat) done()
            Btn.SELECT, Btn.MENU -> if (!repeat) close()
            else -> return false
        }
        return true
    }

}
