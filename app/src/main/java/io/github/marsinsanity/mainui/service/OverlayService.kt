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
    private var homeDownAt = 0L
    private var longFired = false
    private var hotkeys: HotkeyListener? = null
    private val shotExecutor = Executors.newSingleThreadExecutor()

    private val longPress = Runnable {
        longFired = true
        openGameSwitcher()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        updateHotkeyListener()
    }

    override fun onDestroy() {
        instance = null
        hotkeys?.stop()
        removeOverlay()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg == "com.android.systemui" || pkg.contains("inputmethod")) return
        foregroundPackage = pkg
    }

    private fun gameInFront(): Boolean {
        val s = MainUI.sessions.active ?: return false
        return foregroundPackage == s.pkg
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (overlay != null) {
            // HOME while the menu is up closes it (Continue)
            if (event.action == KeyEvent.ACTION_UP) overlay?.dispatch(io.github.marsinsanity.mainui.ui.Btn.B, false)
            return true
        }
        if (MainUI.settings.ingameTrigger != "home" || !gameInFront()) return false
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                homeDownAt = event.eventTime
                longFired = false
                if (MainUI.settings.longHomeGameSwitcher) main.postDelayed(longPress, LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                if (!longFired) showMenu()
            }
        }
        return true
    }

    /** Captures the game frame then shows the in-game menu above it. */
    fun showMenu() {
        val s = MainUI.sessions.active ?: return
        if (overlay != null) return
        capture { bmp -> main.post { addOverlay { view -> InGameMenuScene(view, s, bmp, overlayMode = true) } } }
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
        }
        runCatching {
            getSystemService(WindowManager::class.java).addView(view, lp)
            overlay = view
            view.setRoot(scene(view))
            view.requestFocus()
            MainUI.themes.pauseBgm()
        }.onFailure { Log.e(TAG, "addView failed", it) }
    }

    fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        v.destroy()
    }

    private fun openGameSwitcher() {
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
        val combo = when (MainUI.settings.ingameTrigger) {
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
        private const val LONG_PRESS_MS = 600L

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
                val p = ProcessBuilder("su", "-c", "getevent -ql").redirectErrorStream(true).start()
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
