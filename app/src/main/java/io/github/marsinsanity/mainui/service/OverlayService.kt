package io.github.marsinsanity.mainui.service

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import io.github.marsinsanity.mainui.MainActivity
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.emu.Freezer
import io.github.marsinsanity.mainui.ui.MainUIView
import io.github.marsinsanity.mainui.ui.scenes.InGameMenuScene
import java.io.File
import java.util.concurrent.Executors

/**
 * Accessibility service behind the in-game menu.
 *
 * - Tracks which app is in front (window state events).
 * - While a game MainUI launched is in front, consumes the HOME key: a short
 *   press opens the Miyoo style menu as an overlay above the game, a long
 *   press opens the Game Switcher. Everywhere else HOME behaves normally.
 * - Optionally listens for Select+Start / L3+R3 through root `getevent`
 *   (no input latency since it only observes).
 */
class OverlayService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: MainUIView? = null
    @Volatile var foregroundPackage: String? = null
        private set
    private var longFired = false
    @Volatile private var opening = false
    private var hotkeys: HotkeyListener? = null
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()

    private val longPress = Runnable {
        longFired = true
        Log.d(TAG, "HOME held -> ${MainUI.settings.homeHold}")
        perform(MainUI.settings.homeHold)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Freezer.thawAll()
        updateHotkeyListener()
    }

    override fun onDestroy() {
        instance = null
        hotkeys?.stop()
        removeOverlay()
        // Never leave a game frozen behind a dead overlay.
        Freezer.frozen()?.let { pkg -> Thread { Freezer.thaw(pkg) }.start() }
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) {
            // MainUI's own activity counts as "in front"; its overlay windows don't.
            if (event.className?.toString() == MainActivity::class.java.name) foregroundPackage = pkg
            return
        }
        if (pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
        if (pkg != foregroundPackage) Log.d(TAG, "foreground: $pkg")
        foregroundPackage = pkg
    }

    private fun gameInFront(): Boolean {
        val s = MainUI.sessions.active ?: return false
        return foregroundPackage == s.pkg
    }

    /**
     * HOME inside a game launched from MainUI. A short press and a hold each run
     * the action chosen in Settings > Controls (default: press = in-game menu,
     * hold = Game Switcher). Everywhere else HOME behaves normally.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (overlay != null || opening) {
            // HOME while the menu is up closes it (Continue)
            if (event.action == KeyEvent.ACTION_UP && overlay != null) overlay?.dispatch(io.github.marsinsanity.mainui.ui.Btn.B, false)
            return true
        }
        if (foregroundPackage == packageName) return homeInMainUI(event)
        val press = MainUI.settings.homePress
        val hold = MainUI.settings.homeHold
        if (!gameInFront() || (press == "home" && hold == "home")) {
            if (event.action == KeyEvent.ACTION_DOWN) Log.d(TAG, "HOME passed through (fg=$foregroundPackage, session=${MainUI.sessions.active?.pkg})")
            return false
        }
        // No separate hold action: act on the press itself, no waiting for release.
        val instant = hold == "none" || hold == press
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longFired = false
                main.removeCallbacks(longPress)
                if (instant) { longFired = true; Log.d(TAG, "HOME pressed -> $press"); perform(press) }
                else main.postDelayed(longPress, MainUI.settings.homeHoldMs.toLong().coerceIn(200, 3000))
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (!longFired) { Log.d(TAG, "HOME pressed -> $press"); perform(press) }
            }
        }
        return true
    }

    private val mainUiHold = Runnable {
        longFired = true
        Log.d(TAG, "HOME held in MainUI -> Game Switcher")
        MainActivity.instance?.showGameSwitcher()
    }

    /**
     * HOME while MainUI itself is in front: holding it opens the Game Switcher,
     * a short press is passed on as a normal HOME (back to the main menu, see
     * Settings > Controls > Home inside MainUI).
     */
    private fun homeInMainUI(event: KeyEvent): Boolean {
        if (MainUI.settings.homeHoldInMainUI != "gs") return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                longFired = false
                main.removeCallbacks(mainUiHold)
                main.postDelayed(mainUiHold, MainUI.settings.homeHoldMs.toLong().coerceIn(200, 3000))
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(mainUiHold)
                if (!longFired) performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }
        return true
    }

    /** Runs one of the configurable HOME actions. */
    fun perform(action: String) {
        when (action) {
            "menu" -> showMenu()
            "gs" -> openGameSwitcher()
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            else -> {}
        }
    }

    /**
     * Pauses the game, captures the paused frame, then shows the in-game menu
     * above it. The pause happens first so the game never runs behind the menu.
     */
    fun showMenu() {
        val s = MainUI.sessions.active ?: return
        if (overlay != null || opening) return
        opening = true
        Log.d(TAG, "showing in-game menu for ${s.name}")
        worker.execute {
            val controller = MainUI.sessions.controllerFor(s)
            val paused = MainUI.settings.pauseOnMenu && runCatching { controller.pause() }.getOrDefault(false)
            Log.d(TAG, "paused=$paused (${controller.javaClass.simpleName})")
            capture { bmp ->
                main.post {
                    opening = false
                    addOverlay { view -> InGameMenuScene(view, s, bmp, overlayMode = true, controller = controller, paused = paused) }
                    if (overlay == null) worker.execute { controller.unpause() } // could not show: never leave it paused
                }
            }
        }
    }

    private fun capture(done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        val sw = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        result.hardwareBuffer.close()
                        done(sw)
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.w(TAG, "takeScreenshot failed: $errorCode")
                        shotExecutor.execute { done(rootCapture()) }
                    }
                })
                return
            } catch (e: Exception) {
                Log.w(TAG, "takeScreenshot threw", e)
            }
        }
        shotExecutor.execute { done(rootCapture()) }
    }

    private fun rootCapture(): Bitmap? {
        if (!Root.knownAvailable()) return null
        val f = File(cacheDir, "cap.png")
        return if (Root.screencap(f)) BitmapFactory.decodeFile(f.absolutePath) else null
    }

    private fun addOverlay(scene: (MainUIView) -> io.github.marsinsanity.mainui.ui.Scene) {
        if (overlay != null) return
        val view = MainUIView(this) { main.post { removeOverlay() } }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.OPAQUE,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            // Cover the status / navigation bar area too so the screenshot lines up 1:1 with the game.
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            flags = flags or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        runCatching {
            getSystemService(WindowManager::class.java).addView(view, lp)
            overlay = view
            view.setRoot(scene(view))
            view.requestFocus()
            if (Build.VERSION.SDK_INT >= 30) view.windowInsetsController?.let {
                it.hide(android.view.WindowInsets.Type.systemBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            MainUI.themes.pauseBgm()
        }.onFailure { Log.e(TAG, "addView failed", it) }
    }

    fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        v.destroy()
    }

    fun openGameSwitcher() {
        // Remember the current frame for the switcher, then bring MainUI forward.
        val s = MainUI.sessions.active
        capture { bmp ->
            if (bmp != null && s != null) MainUI.sessions.saveScreenshot(s.gameKey, bmp)
            main.post {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    .putExtra(MainActivity.EXTRA_GAME_SWITCHER, true))
            }
        }
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /** Starts / stops the root key-combo listener according to settings. */
    fun updateHotkeyListener() {
        hotkeys?.stop(); hotkeys = null
        val combo = when (MainUI.settings.comboTrigger) {
            "select_start" -> setOf("BTN_SELECT", "BTN_START")
            "l3_r3" -> setOf("BTN_THUMBL", "BTN_THUMBR")
            else -> return
        }
        Thread {
            if (!Root.available()) return@Thread
            hotkeys = HotkeyListener(combo) { main.post { if (gameInFront()) showMenu() } }.also { it.start() }
        }.start()
    }

    companion object {
        private const val TAG = "MainUI.Overlay"

        @Volatile var instance: OverlayService? = null
            private set

        fun isEnabled(ctx: Context): Boolean {
            val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, OverlayService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/**
 * Watches raw input events through root `getevent -ql` and fires when every
 * key of [combo] is held. Passive: the game still receives every key.
 */
class HotkeyListener(private val combo: Set<String>, private val onCombo: () -> Unit) {
    @Volatile private var running = true
    private var process: Process? = null

    fun start() {
        Thread {
            try {
                val p = ProcessBuilder(*Root.rootArgv("getevent -ql")).redirectErrorStream(true).start()
                process = p
                val down = HashSet<String>()
                p.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (!running) break
                        val parts = line.substringAfter(':').trim().split(Regex("\\s+"))
                        if (parts.size < 3 || parts[0] != "EV_KEY") continue
                        val key = parts[1]
                        when (parts[2]) {
                            "DOWN" -> { down += key; if (down.containsAll(combo)) { down.clear(); onCombo() } }
                            "UP" -> down -= key
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("MainUI.Hotkeys", "listener stopped", e)
            }
        }.apply { isDaemon = true; name = "mainui-hotkeys"; start() }
    }

    fun stop() {
        running = false
        process?.destroy()
    }
}
