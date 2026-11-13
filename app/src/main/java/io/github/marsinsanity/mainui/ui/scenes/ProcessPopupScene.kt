package io.github.marsinsanity.mainui.ui.scenes

import android.graphics.Color
import android.graphics.RectF
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.RunningApps
import io.github.marsinsanity.mainui.ui.Align
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import io.github.marsinsanity.mainui.ui.UiList
import io.github.marsinsanity.mainui.ui.withAlpha

/**
 * The process manager as a popup (hold Back anywhere): a rounded panel over whatever is on screen,
 * a smaller version of Settings > Process manager. The app behind stays visible around it (the
 * overlay window is translucent), so it never covers the whole screen. Everything happens inside the
 * panel: A switches, X twice stops the app (no full-screen dialog), Y refreshes, B or a tap outside
 * closes it. By touch: a row is selected by a tap and switched to by a second, a long press is X,
 * the button hints in the panel are taps too, and the list scrolls with a drag or fling.
 */
class ProcessPopupScene(host: SceneHost) : Scene(host) {
    private val list = UiList(false)
    private var loaded = false
    /** The app X was pressed on once: a second X stops it. */
    private var armed: String? = null
    private var panel = RectF()
    private var listTop = 0f
    private var listBottom = 0f
    /** Where this frame drew each button hint, for taps. */
    private val hintRects = ArrayList<Pair<RectF, Btn>>()

    override fun onEnter() = reload()

    private fun reload() {
        armed = null
        host.io({ Processes.scan(host.hostContext) }) { apps ->
            loaded = true
            list.set(when {
                apps == null -> listOf(UiItem("Root access is required", disabled = true))
                apps.isEmpty() -> listOf(UiItem("No apps running", disabled = true))
                else -> apps.map { Processes.row(it, 72) }
            }, keepIndex = true)
            host.invalidate()
        }
    }

    override fun draw(g: Gfx) {
        g.dim(130)
        val w = g.W * 0.86f
        val h = g.H * 0.84f
        val x = (g.W - w) / 2
        val y = (g.H - h) / 2
        panel = RectF(x, y, x + w, y + h)
        val t = g.theme
        g.fillRect(x + 4f, y + 6f, w, h, Color.argb(90, 0, 0, 0), 24f)          // soft shadow
        g.fillRect(x, y, w, h, withAlpha(t.panel, 248), 24f)
        g.strokeRect(x, y, w, h, t.accent, 2f, 24f)

        // Title bar inside the panel.
        g.text("Running apps", t.title, x + 26f, y + 32f, size = t.title.size * 0.72f)
        val apps = list.items.count { it.data is RunningApps.App }
        if (loaded) g.text(if (apps == 1) "1 app" else "$apps apps", t.list, x + w - 26f, y + 32f, Align.RIGHT,
            size = t.list.size * 0.7f, alpha = 190)
        g.fillRect(x + 18f, y + 58f, w - 36f, 2f, withAlpha(t.accent, 120))

        listTop = y + 64f
        listBottom = y + h - 56f
        if (!loaded) g.text("Loading...", t.list, g.W / 2, (listTop + listBottom) / 2, Align.CENTER, alpha = 170)
        else g.list(list, listTop, listBottom, x + 16f, x + w - 16f, showPreview = false, dividers = false)

        // Button hints inside the panel.
        g.fillRect(x + 18f, y + h - 54f, w - 36f, 2f, withAlpha(t.accent, 120))
        val p = list.current?.data as? RunningApps.App
        val hints = buildList {
            if (p != null) add("A" to "SWITCH")
            if (p != null) add("X" to if (armed == p.pkg) "AGAIN TO STOP" else "KILL")
            add("Y" to "REFRESH")
            add("B" to "CLOSE")
        }
        var hx = x + 24f
        val cy = y + h - 27f
        hintRects.clear()
        for ((b, label) in hints) {
            val start = hx
            hx += g.buttonIcon(b, hx, cy, scale = 0.62f) + 6f
            hx += g.text(label, t.list, hx, cy, size = t.list.size * 0.62f,
                color = if (b == "X" && armed != null) Color.rgb(255, 120, 110) else t.list.color)
            val btn = when (b) { "A" -> Btn.A; "X" -> Btn.X; "Y" -> Btn.Y; else -> Btn.B }
            hintRects += RectF(start - 8f, y + h - 54f, hx + 9f, y + h) to btn
            hx += 18f
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val p = list.current?.data as? RunningApps.App
        if (b != Btn.X && armed != null) armed = null
        return when (b) {
            Btn.UP -> list.up(repeat)
            Btn.DOWN -> list.down(repeat)
            Btn.L1, Btn.LEFT -> list.pageUp()
            Btn.R1, Btn.RIGHT -> list.pageDown()
            Btn.A -> {
                if (!repeat && p != null) {
                    host.io({ Processes.switchTo(host.hostContext, p) }) { ok ->
                        if (ok) host.finishHost() else host.toast("Cannot switch to ${MainUI.apps.label(p.pkg)}")
                    }
                }
                true
            }
            Btn.X -> {
                if (!repeat && p != null) {
                    if (armed != p.pkg) armed = p.pkg
                    else {
                        armed = null
                        host.io({ Processes.kill(p) }) { ok -> host.toast(if (ok) "Stopped ${MainUI.apps.label(p.pkg)}" else "Failed", 1200); reload() }
                    }
                }
                true
            }
            Btn.Y -> { if (!repeat) reload(); true }
            Btn.B, Btn.MENU, Btn.SELECT -> { if (!repeat) host.finishHost(); true }
            else -> true
        }
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (!panel.contains(x, y)) { host.finishHost(); return true }
        hintRects.firstOrNull { it.first.contains(x, y) }?.let { return onKey(it.second, false) }
        val idx = rowAt(y) ?: return true
        if (idx == list.active) onKey(Btn.A, false) else { list.active = idx; armed = null }
        return true
    }

    /** A long press on an app is X: once to arm, again to stop it. */
    override fun onLongPress(x: Float, y: Float): Boolean {
        if (!panel.contains(x, y)) return true
        val idx = rowAt(y) ?: return true
        if (idx != list.active) { list.active = idx; armed = null }
        onKey(Btn.X, false)
        return true
    }

    /** The row under [y], or null (title, hints, the band below the last row). */
    private fun rowAt(y: Float): Int? {
        if (y < listTop || y > listBottom || list.size == 0) return null
        val rel = y - g0(listTop, listBottom)
        if (rel < 0f) return null
        val idx = list.scroll + (rel / ROW_H).toInt()
        return idx.takeIf { it in list.scroll until minOf(list.size, list.scroll + list.visible) }
    }

    // ---- drag / fling scrolling, a row at a time like the other lists ----
    private var dragAcc = 0f

    private fun scrollBy(rows: Int): Boolean {
        if (list.size <= list.visible || rows == 0) return false
        val old = list.scroll
        list.scroll = (list.scroll + rows).coerceIn(0, list.size - list.visible)
        list.active = list.active.coerceIn(list.scroll, list.scroll + list.visible - 1)
        if (list.scroll != old) armed = null
        return list.scroll != old
    }

    override fun onDrag(dx: Float, dy: Float): Boolean {
        if (dragAcc == 0f && kotlin.math.abs(dx) > kotlin.math.abs(dy)) return false
        dragAcc += dy
        while (dragAcc <= -ROW_H) { scrollBy(1); dragAcc += ROW_H }
        while (dragAcc >= ROW_H) { scrollBy(-1); dragAcc -= ROW_H }
        return true
    }

    override fun onDragEnd() { dragAcc = 0f }

    override fun onFling(vx: Float, vy: Float): Boolean {
        dragAcc = 0f
        return scrollBy((-vy * 0.3f / ROW_H).toInt())
    }

    override fun onSwipe(direction: Btn): Boolean = when (direction) {
        Btn.UP -> scrollBy(-list.visible) || list.pageUp()
        Btn.DOWN -> scrollBy(list.visible) || list.pageDown()
        else -> onKey(direction, false)
    }

    /** Where the list's first row starts (the list centres its rows in the area). */
    private fun g0(top: Float, bottom: Float): Float {
        val rows = maxOf(1, ((bottom - top) / ROW_H).toInt())
        return top + ((bottom - top) - rows * ROW_H) / 2f
    }

    private companion object {
        const val ROW_H = 60f
    }
}
