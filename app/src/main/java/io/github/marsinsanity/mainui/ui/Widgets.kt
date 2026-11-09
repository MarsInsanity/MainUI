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
        g.footer(if (list.current?.disabled == true) null else aLabel, bLabel, extraHints)
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
        val startY = bottom - rows * itemH
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
        g.footer(confirmLabel, cancelLabel)
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
        g.list(list, top + 5f, top + h - 5f, x, x + w, showPreview = false, dividers = false, stretch = true)
        g.footer("SELECT", "BACK")
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
    private val specials = listOf("SHIFT", "SPACE", "DEL", "OK")

    private fun rows() = if (shift) rowsUpper else rowsLower
    private val rowCount get() = rows().size + 1

    private fun colsIn(r: Int) = if (r < rows().size) rows()[r].length else specials.size

    override fun draw(g: Gfx) {
        g.dim(170)
        val panelTop = g.H - 60f - 5 * 58f - 90f
        g.text(title, g.theme.title, g.W / 2, panelTop - 30f, Align.CENTER, maxW = g.W - 40f)
        // input box
        val inBg = g.skin("bg-input")
        val bx = 40f; val bw = g.W - 80f
        if (inBg != null) g.bmp(inBg, bx, panelTop, bw, 60f) else g.fillRect(bx, panelTop, bw, 60f, Color.argb(220, 255, 255, 255), 10f)
        val shown = if (password) "•".repeat(sb.length) else sb.toString()
        val before = shown.substring(0, cursor.coerceAtMost(shown.length))
        val textColor = if (inBg != null) g.theme.list.color else Color.BLACK
        val tw = g.textWidth(before, g.theme.list)
        val scrollX = maxOf(0f, tw - (bw - 40f))
        g.c.save(); g.c.clipRect(bx + 10, panelTop, bx + bw - 10, panelTop + 60f)
        g.text(shown, g.theme.list, bx + 20f - scrollX, panelTop + 30f, color = textColor)
        if ((g.now / 500) % 2 == 0L) g.fillRect(bx + 20f + tw - scrollX, panelTop + 14f, 3f, 32f, textColor)
        g.c.restore()
        g.wantsFrame = true

        val keyW = 52f; val keyH = 52f; val gap = 6f
        var y = panelTop + 80f
        for (r in 0 until rowCount) {
            val n = colsIn(r)
            val widths = if (r < rows().size) List(n) { keyW } else listOf(keyW * 2.5f, keyW * 4f, keyW * 2f, keyW * 2f)
            val total = widths.sum() + gap * (n - 1)
            var x = (g.W - total) / 2
            for (cI in 0 until n) {
                val sel = r == row && cI == col
                val kb = g.skin(if (sel) "btn-bg-f" else "btn-bg-n")
                if (kb != null) g.bmp(kb, x, y, widths[cI], keyH)
                else g.fillRect(x, y, widths[cI], keyH, if (sel) g.theme.accent else Color.argb(90, 255, 255, 255), 8f)
                val label = if (r < rows().size) rows()[r][cI].toString() else specials[cI]
                g.text(label, g.theme.list, x + widths[cI] / 2, y + keyH / 2, Align.CENTER,
                    size = if (label.length > 1) 20f else 26f, color = Color.WHITE)
                x += widths[cI] + gap
            }
            y += keyH + gap
        }
        g.footer("TYPE", "DEL", listOf("START" to "OK", "Y" to "SHIFT"))
    }

    private fun type(ch: String) { sb.insert(cursor, ch); cursor += ch.length }

    private fun press() {
        if (row < rows().size) { type(rows()[row][col].toString()); return }
        when (specials[col]) {
            "SHIFT" -> shift = !shift
            "SPACE" -> type(" ")
            "DEL" -> delete()
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

    override fun animating(now: Long) = true
}
