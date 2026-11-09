package io.github.marsinsanity.mainui.ui.scenes

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import io.github.marsinsanity.mainui.MainActivity
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.emu.GameController
import io.github.marsinsanity.mainui.emu.Hotkeys
import io.github.marsinsanity.mainui.emu.Session
import io.github.marsinsanity.mainui.emu.StandaloneController
import io.github.marsinsanity.mainui.emu.StateSupport
import io.github.marsinsanity.mainui.ui.Align
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.Images
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import io.github.marsinsanity.mainui.ui.UiList
import java.io.FileOutputStream

/**
 * The Miyoo style in-game menu shown when HOME is pressed inside an emulator:
 * a menu panel on the left over the paused game, A/B hints in the footer.
 *
 * [overlayMode] true = drawn by the accessibility service on top of the game
 * window, which [io.github.marsinsanity.mainui.service.OverlayService] already
 * paused ([paused]) before taking the screenshot. false = fallback inside
 * MainUI's own activity when the overlay service is not enabled (the emulator
 * is in the background then, so Android has already paused it).
 *
 * Save / Load depend on what the emulator supports ([StateSupport]):
 *  - API (RetroArch): slots 0-9 through its network commands, while paused.
 *  - HOTKEY: MainUI closes the menu, unfreezes the game and injects the save /
 *    load key bound in the emulator (root). "Bind hotkeys" sends those keys
 *    while the emulator's binding screen waits, so they can be bound with
 *    only a gamepad.
 *  - MENU: one "Save / Load" entry that opens the emulator's own menu.
 *  - NONE: no entries (the Switch, Vita and PS3 emulators have no save states).
 */
class InGameMenuScene(
    host: SceneHost,
    private val session: Session,
    private var screenshot: Bitmap?,
    private val overlayMode: Boolean,
    private val controller: GameController = MainUI.sessions.controllerFor(session),
    private val paused: Boolean = false,
) : Scene(host) {

    private val list = UiList(false)
    private var slot = MainUI.settings.prefs.getInt("slot:${session.gameKey}", 0)
    private var busy: String? = null
    private var bindMode = false
    private var resumed = false
    private val main = Handler(Looper.getMainLooper())
    private val emuName = session.emuRef?.let { MainUI.emulators.defFor(it)?.name } ?: session.name

    companion object {
        const val ROW_H = 56f
    }

    private enum class Action { CONTINUE, SAVE, LOAD, STATES_MENU, NATIVE, SWITCHER, EXIT, BIND, BIND_SAVE, BIND_LOAD, BIND_BACK,
        KEY_SAVE, KEY_LOAD, KEY_RESET }

    /** Standalone emulator (not an Android game): its save / load keys can be set here. */
    private val standalone get() = (controller as? StandaloneController)?.takeIf { it.emuId != null }

    override fun onEnter() {
        buildItems()
        // Fallback mode: nothing has probed the emulator yet (network, so off the UI thread)
        if (!overlayMode) host.io({ runCatching { controller.prepare() } }) { buildItems() }
        val shot = screenshot
        if (shot == null) {
            // Fallback mode: show the last screenshot we have for this game
            val f = MainUI.sessions.screenshotFile(session.gameKey)
            if (f.isFile) screenshot = Images.decode(f, 1600)
        } else {
            host.io({ MainUI.sessions.saveScreenshot(session.gameKey, shot) })
        }
    }

    private fun buildItems() {
        val items = mutableListOf<UiItem>()
        val sa = standalone
        if (bindMode && sa != null) {
            items += UiItem("Save key: ${Hotkeys.label(sa.saveSpec)}", data = Action.KEY_SAVE)
            items += UiItem("Load key: ${Hotkeys.label(sa.loadSpec)}", data = Action.KEY_LOAD)
            if (sa.saveSpec != null) items += UiItem("Send save key", data = Action.BIND_SAVE)
            if (sa.loadSpec != null) items += UiItem("Send load key", data = Action.BIND_LOAD)
            if (Hotkeys.get(sa.emuId!!) != (null to null)) items += UiItem(
                if (sa.defaultSave != null) "Use defaults (${Hotkeys.label(sa.defaultSave)} / ${Hotkeys.label(sa.defaultLoad)})" else "Clear keys",
                data = Action.KEY_RESET)
            items += UiItem("Back", data = Action.BIND_BACK)
        } else {
            items += UiItem("Continue", data = Action.CONTINUE)
            when (controller.states) {
                StateSupport.API, StateSupport.HOTKEY -> {
                    items += UiItem(if (controller.hasSlots) "Save  [slot $slot]" else "Save", data = Action.SAVE)
                    items += UiItem(if (controller.hasSlots) "Load  [slot $slot]" else "Load", data = Action.LOAD)
                }
                StateSupport.MENU -> if (!session.isAndroidApp) items += UiItem("Save / Load (menu)", data = Action.STATES_MENU)
                StateSupport.NONE -> {}
            }
            items += UiItem("Native Menu", data = Action.NATIVE)
            items += UiItem("Game Switcher", data = Action.SWITCHER)
            items += UiItem("Exit Game", data = Action.EXIT)
            // RetroArch's hotkeys are its own defaults; standalone emulators get theirs set here (root presses them).
            if (sa != null && Root.knownAvailable()) items += UiItem("Save / load keys...", data = Action.BIND)
        }
        list.set(items, false)
    }

    override fun draw(g: Gfx) {
        val shot = screenshot
        if (shot != null) g.bmpCover(shot, 0f, 0f, g.W, g.H) else g.background()
        // Left menu panel like the Miyoo Mini's (bg-pop-menu image when the theme has one)
        val rows = list.size
        val rowH = ROW_H
        val panelW = 300f
        val top = 0f // flush with the top edge, like the Miyoo Mini's menu
        val panelH = rows * rowH
        g.popPanel(4, 0f, top, panelW + 14f, panelH)
        for (i in 0 until rows) {
            val it = list.items[i]
            val y = top + i * rowH
            val sel = i == list.active
            if (sel) g.selection(false, 0f, y, panelW + 14f, rowH)
            g.text(it.label, g.theme.list, 22f, y + rowH / 2, maxW = panelW - 40f)
            if (sel && controller.hasSlots && (it.data == Action.SAVE || it.data == Action.LOAD)) {
                g.arrow(false, panelW, y + rowH / 2)
            }
            if (sel && (it.data == Action.KEY_SAVE || it.data == Action.KEY_LOAD)) g.arrow(false, panelW, y + rowH / 2)
        }
        val cur = list.current?.data
        if (bindMode) {
            val bx = panelW + 40f
            g.fillRect(bx - 12f, top + 14f, g.W - bx - 6f, 270f, g.theme.panel, 10f)
            g.paragraph("Left / right picks the keys MainUI presses for Save and Load in $emuName. " +
                "They must be the same in $emuName: open its hotkey settings, start binding \"Save state\" " +
                "(or \"Load state\"), press HOME and choose Send save / load key.", g.theme.list, bx, top + 26f,
                g.W - bx - 24f, size = 20f, maxLines = 11)
        } else if (controller.hasSlots && (cur == Action.SAVE || cur == Action.LOAD)) {
            // Slot preview on the right while Save/Load is highlighted
            val f = MainUI.sessions.slotThumb(session.gameKey, slot)
            val thumb = if (f.isFile) Images.get("slot:${f.absolutePath}:${f.lastModified()}", host, 640) { f } else null
            val bx = panelW + 40f; val bw = g.W - bx - 30f; val bh = bw * 0.75f
            val by = top + 14f
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
        if (paused) g.text("PAUSED", g.theme.hint, g.W - 20f, g.H - 30f, Align.RIGHT, size = 24f, alpha = 200)
        busy?.let { g.infoPanel(it) }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (busy != null) return true
        when (b) {
            Btn.UP -> list.up(repeat)
            Btn.DOWN -> list.down(repeat)
            Btn.LEFT, Btn.RIGHT -> {
                val cur = list.current?.data
                if (cur == Action.KEY_SAVE || cur == Action.KEY_LOAD) changeKey(cur == Action.KEY_SAVE, b == Btn.RIGHT)
                else if (controller.hasSlots && (cur == Action.SAVE || cur == Action.LOAD)) {
                    slot = (slot + if (b == Btn.RIGHT) 1 else -1).coerceIn(0, 9)
                    MainUI.settings.prefs.edit().putInt("slot:${session.gameKey}", slot).apply()
                    buildItems(); list.active = list.items.indexOfFirst { it.data == cur }.coerceAtLeast(0)
                } else return false
            }
            Btn.A -> if (!repeat) run(list.current?.data as? Action ?: return true)
            Btn.B, Btn.MENU -> if (!repeat) run(if (bindMode) Action.BIND_BACK else Action.CONTINUE)
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val idx = (y / ROW_H).toInt()
        if (x > 320f || idx !in 0 until list.size) { run(Action.CONTINUE); return true }
        list.active = idx
        run(list.current?.data as? Action ?: return true)
        return true
    }

    private fun run(a: Action) {
        when (a) {
            Action.CONTINUE -> continueGame()
            Action.SAVE -> if (controller.states == StateSupport.API) apiAction("Saving...") {
                val ok = controller.save(slot)
                if (ok) screenshot?.let { saveThumb(it) }
                if (ok) "State saved to slot $slot" else "Save failed (run Configure RetroArch)"
            } else hotkeyAction("Save") { controller.save(0).also { ok -> if (ok) screenshot?.let { saveThumb(it) } } }
            Action.LOAD -> if (controller.states == StateSupport.API) apiAction("Loading...") {
                if (controller.load(slot)) "State loaded from slot $slot" else "Load failed"
            } else hotkeyAction("Load") { controller.load(0) }
            Action.STATES_MENU, Action.NATIVE -> leaveThen(250) { controller.openNativeMenu() }
            Action.SWITCHER -> {
                resumeOnce()
                closeMenu()
                if (overlayMode) {
                    host.hostContext.startActivity(Intent(host.hostContext, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        .putExtra(MainActivity.EXTRA_GAME_SWITCHER, true))
                } else host.push(GameSwitcherScene(host))
            }
            Action.EXIT -> exitGame()
            Action.BIND -> { bindMode = true; buildItems() }
            Action.BIND_BACK -> { bindMode = false; buildItems() }
            Action.KEY_SAVE, Action.KEY_LOAD -> changeKey(a == Action.KEY_SAVE, true)
            Action.KEY_RESET -> { standalone?.emuId?.let { Hotkeys.set(it, null, null) }; buildItems() }
            Action.BIND_SAVE -> leaveThen(700) { (controller as? StandaloneController)?.save(0) }
            Action.BIND_LOAD -> leaveThen(700) { (controller as? StandaloneController)?.load(0) }
        }
    }

    /** Steps the save or load key to the next / previous choice and keeps it for this emulator. */
    private fun changeKey(save: Boolean, forward: Boolean) {
        val sa = standalone ?: return
        val id = sa.emuId ?: return
        val (s0, l0) = Hotkeys.get(id)
        val cur = if (save) sa.saveSpec else sa.loadSpec
        val next = Hotkeys.step(cur, forward)
        // The other key keeps what is in effect now, so changing one doesn't drop the default of the other.
        if (save) Hotkeys.set(id, next, l0 ?: sa.loadSpec) else Hotkeys.set(id, s0 ?: sa.saveSpec, next)
        val keep = list.active
        buildItems()
        list.active = keep.coerceIn(0, list.size - 1)
        invalidate()
    }

    /** RetroArch: run while paused, then return to the game like the Miyoo menu does. */
    private fun apiAction(label: String, work: () -> String) {
        busy = label
        invalidate()
        host.io({ work() }) { msg ->
            busy = null
            host.toast(msg, 900)
            main.postDelayed({ continueGame() }, 700)
        }
    }

    /**
     * Hotkey emulators: the key must reach the emulator's window, so close the
     * menu, unfreeze the game, let it regain focus, then inject the key.
     */
    private fun hotkeyAction(what: String, work: () -> Boolean) {
        val ctx = host.hostContext
        leaveThen(450) {
            val ok = work()
            main.post {
                Toast.makeText(ctx, if (ok) "$what state: hotkey sent" else "$what state failed (needs root)", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Close the menu, resume the game, then run [after] on a worker thread after [delayMs]. */
    private fun leaveThen(delayMs: Long, after: () -> Unit) {
        resumeOnce()
        closeMenu()
        if (!overlayMode) MainUI.sessions.resume()
        Thread {
            Thread.sleep(delayMs)
            runCatching(after).onFailure { Log.w("MainUI.InGame", "action failed", it) }
        }.start()
    }

    private fun saveThumb(b: Bitmap) = runCatching {
        FileOutputStream(MainUI.sessions.slotThumb(session.gameKey, slot)).use { b.compress(Bitmap.CompressFormat.PNG, 85, it) }
    }

    /** Unpause exactly once, whatever path leaves the menu. */
    private fun resumeOnce() {
        if (resumed) return
        resumed = true
        Thread { runCatching { controller.unpause() } }.start()
    }

    private fun continueGame() {
        resumeOnce()
        closeMenu()
        if (!overlayMode) MainUI.sessions.resume()
    }

    private fun closeMenu() {
        if (overlayMode) host.finishHost() else close()
    }

    override fun onExit() {
        // Safety net (overlay torn down some other way): never leave the game paused.
        resumeOnce()
    }

    private fun exitGame() {
        busy = "Exiting..."
        invalidate()
        val ctx = host.hostContext
        resumed = true // quitting: force stop also kills a frozen process
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
                // No root: thaw if needed; once MainUI is in front the emulator is a
                // background process that may be killed with the normal permission.
                Thread { runCatching { controller.unpause() } }.start()
                val kill = Runnable {
                    runCatching { ctx.getSystemService(ActivityManager::class.java).killBackgroundProcesses(session.pkg) }
                }
                main.postDelayed(kill, 800)
                main.postDelayed(kill, 3000)
            }
        }
    }
}
