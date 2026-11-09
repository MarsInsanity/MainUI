package io.github.marsinsanity.mainui.ui.scenes

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import io.github.marsinsanity.mainui.MainActivity
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.emu.GameController
import io.github.marsinsanity.mainui.emu.Session
import io.github.marsinsanity.mainui.ui.Align
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.Images
import io.github.marsinsanity.mainui.ui.ItemType
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import io.github.marsinsanity.mainui.ui.UiList
import java.io.File
import java.io.FileOutputStream

/**
 * The Miyoo style in-game menu shown when HOME is pressed inside an emulator:
 * a menu panel on the left over the paused game, A/B hints in the footer.
 *
 * [overlayMode] true = drawn by the accessibility service on top of the game
 * window (the game stays where it is). false = fallback inside MainUI's own
 * activity when the overlay service is not enabled.
 */
class InGameMenuScene(
    host: SceneHost,
    private val session: Session,
    private var screenshot: Bitmap?,
    private val overlayMode: Boolean,
) : Scene(host) {

    private val controller: GameController = MainUI.sessions.controllerFor(session)
    private val list = UiList(false)
    private var slot = MainUI.settings.prefs.getInt("slot:${session.gameKey}", 0)
    private var busy: String? = null
    private val main = Handler(Looper.getMainLooper())

    private enum class Action { CONTINUE, SAVE, LOAD, NATIVE, SWITCHER, EXIT }

    override fun onEnter() {
        val items = mutableListOf(UiItem("Continue", data = Action.CONTINUE))
        if (controller.canSaveLoad) {
            items += UiItem("Save", ItemType.ACTION, data = Action.SAVE)
            items += UiItem("Load", ItemType.ACTION, data = Action.LOAD)
        }
        items += UiItem("Native Menu", data = Action.NATIVE)
        items += UiItem("Game Switcher", data = Action.SWITCHER)
        items += UiItem("Exit Game", data = Action.EXIT)
        list.set(items, false)
        updateSlotLabels()

        val shot = screenshot
        if (shot == null) {
            // Fallback mode: show the last screenshot we have for this game
            val f = MainUI.sessions.screenshotFile(session.gameKey)
            if (f.isFile) screenshot = Images.decode(f, 1600)
        } else {
            host.io({ MainUI.sessions.saveScreenshot(session.gameKey, shot) })
        }
        if (MainUI.settings.pauseOnMenu) host.io({ controller.pause() })
    }

    private fun updateSlotLabels() {
        for (it in list.items) {
            when (it.data) {
                Action.SAVE -> it.label = if (controller.hasSlots) "Save  [slot $slot]" else "Save"
                Action.LOAD -> it.label = if (controller.hasSlots) "Load  [slot $slot]" else "Load"
            }
        }
    }

    override fun draw(g: Gfx) {
        val shot = screenshot
        if (shot != null) g.bmpCover(shot, 0f, 0f, g.W, g.H) else g.background()
        // Left menu panel like the Miyoo Mini's (bg-pop-menu image when the theme has one)
        val rows = list.size
        val rowH = 56f
        val panelW = 300f
        val top = 14f
        val panelH = rows * rowH + 8f
        g.popPanel(4, 0f, top, panelW + 14f, panelH)
        for (i in 0 until rows) {
            val it = list.items[i]
            val y = top + 4f + i * rowH
            val sel = i == list.active
            if (sel) g.selection(false, 0f, y, panelW + 14f, rowH)
            g.text(it.label, g.theme.list, 22f, y + rowH / 2, maxW = panelW - 40f)
            if (sel && controller.hasSlots && (it.data == Action.SAVE || it.data == Action.LOAD)) {
                g.arrow(false, panelW, y + rowH / 2)
            }
        }
        // Slot preview on the right while Save/Load is highlighted
        val cur = list.current?.data
        if (controller.hasSlots && (cur == Action.SAVE || cur == Action.LOAD)) {
            val f = MainUI.sessions.slotThumb(session.gameKey, slot)
            val thumb = if (f.isFile) Images.get("slot:${f.absolutePath}:${f.lastModified()}", host, 640) { f } else null
            val bx = panelW + 40f; val bw = g.W - bx - 30f; val bh = bw * 0.75f
            val by = top + 10f
            val frame = g.skin("preview-bg")
            if (frame != null) g.bmp(frame, bx - 6, by - 6, bw + 12, bh + 12) else g.fillRect(bx - 4, by - 4, bw + 8, bh + 8, g.theme.panel, 6f)
            if (thumb != null) g.bmpFit(thumb, bx, by, bw, bh)
            else g.text("Empty", g.theme.list, bx + bw / 2, by + bh / 2, Align.CENTER, alpha = 170)
            g.fillRect(bx, by + bh - 40f, bw, 40f, Color.argb(160, 0, 0, 0))
            g.arrow(true, bx + 8f, by + bh - 20f)
            g.arrow(false, bx + bw - 8f, by + bh - 20f)
            g.text("Slot $slot", g.theme.list, bx + bw / 2, by + bh - 20f, Align.CENTER)
        }
        g.footer("SELECT", "BACK")
        busy?.let { g.infoPanel(it) }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (busy != null) return true
        when (b) {
            Btn.UP -> list.up(repeat)
            Btn.DOWN -> list.down(repeat)
            Btn.LEFT, Btn.RIGHT -> {
                val cur = list.current?.data
                if (controller.hasSlots && (cur == Action.SAVE || cur == Action.LOAD)) {
                    slot = (slot + if (b == Btn.RIGHT) 1 else -1).coerceIn(0, 9)
                    MainUI.settings.prefs.edit().putInt("slot:${session.gameKey}", slot).apply()
                    updateSlotLabels()
                } else return false
            }
            Btn.A -> if (!repeat) run(list.current?.data as? Action ?: return true)
            Btn.B, Btn.MENU -> if (!repeat) run(Action.CONTINUE)
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val idx = ((y - 18f) / 56f).toInt()
        if (x > 320f || idx !in 0 until list.size) { run(Action.CONTINUE); return true }
        list.active = idx
        run(list.current?.data as? Action ?: return true)
        return true
    }

    private fun run(a: Action) {
        when (a) {
            Action.CONTINUE -> continueGame()
            Action.SAVE -> background("Saving...") {
                val ok = controller.save(slot)
                if (ok) screenshot?.let { saveThumb(it) }
                if (ok) "State saved" else "Save failed"
            }
            Action.LOAD -> background("Loading...") { if (controller.load(slot)) "State loaded" else "Load failed" }
            Action.NATIVE -> {
                closeMenu()
                main.postDelayed({ Thread { controller.unpause(); controller.openNativeMenu() }.start() }, 250)
            }
            Action.SWITCHER -> {
                closeMenu()
                if (overlayMode) {
                    host.hostContext.startActivity(Intent(host.hostContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(MainActivity.EXTRA_GAME_SWITCHER, true))
                } else host.push(GameSwitcherScene(host))
            }
            Action.EXIT -> exitGame()
        }
    }

    /** Runs a save/load action, then returns to the game like the Miyoo menu does. */
    private fun background(label: String, work: () -> String) {
        busy = label
        invalidate()
        host.io({ work() }) { msg ->
            busy = null
            host.toast(msg, 900)
            main.postDelayed({ continueGame() }, 700)
        }
    }

    private fun saveThumb(b: Bitmap) = runCatching {
        FileOutputStream(MainUI.sessions.slotThumb(session.gameKey, slot)).use { b.compress(Bitmap.CompressFormat.PNG, 85, it) }
    }

    private fun continueGame() {
        closeMenu()
        Thread { controller.unpause() }.start()
        if (!overlayMode) MainUI.sessions.resume()
    }

    private fun closeMenu() {
        if (overlayMode) host.finishHost() else close()
    }

    private fun exitGame() {
        busy = "Exiting..."
        invalidate()
        val ctx = host.hostContext
        host.io({
            val ok = controller.quit(MainUI.settings.autoSaveOnExit)
            MainUI.sessions.endActive()
            ok
        }) { ok ->
            busy = null
            if (overlayMode) {
                ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                host.finishHost()
            } else close()
            if (!ok) {
                // No root: once MainUI is in front the emulator is a background
                // process and may be killed with the normal permission.
                val kill = Runnable {
                    runCatching { ctx.getSystemService(ActivityManager::class.java).killBackgroundProcesses(session.pkg) }
                }
                main.postDelayed(kill, 800)
                main.postDelayed(kill, 3000)
            }
        }
    }

    companion object {
        fun saveBitmap(b: Bitmap, f: File) = runCatching { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 90, it) } }
    }
}
