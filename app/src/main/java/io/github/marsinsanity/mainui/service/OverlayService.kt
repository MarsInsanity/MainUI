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
import android.os.SystemClock
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
 * - While a game MainUI launched is in front, consumes the HOME key: a press
 *   opens the Miyoo style menu as an overlay above the game (on the press
 *   itself), holding it opens the Game Switcher. Everywhere else HOME behaves
 *   normally.
 * - Watches Back (never consumes it): holding it opens the process manager
 *   as a popup over whatever is in front.
 * - Optionally listens for Select+Start / L3+R3 through root `getevent`
 *   (no input latency since it only observes).
 */
class OverlayService : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var overlay: MainUIView? = null
    @Volatile var foregroundPackage: String? = null
        private set
    /** When the app in front last changed its screen (a new activity, a dialog, its game window), uptime ms. */
    @Volatile var lastWindowChangeAt = 0L
        private set
    private var longFired = false
    @Volatile private var opening = false
    /** Counts menu opens: a completion for an older open (given up on) must not show its menu. */
    private var openId = 0
    @Volatile private var hotkeys: HotkeyListener? = null
    /** Bumped by every [updateHotkeyListener] and by [onDestroy]: an older start is discarded. */
    private val hotkeyGen = java.util.concurrent.atomic.AtomicInteger()
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()

    /** What the HOME press in progress does; decided on its first DOWN so its UP matches it. */
    private enum class HomePress { PASS, MAINUI, CLOSE, IGNORE, OPENED, WAIT, DONE }
    private var homeState: HomePress? = null
    /** HOME was held while the menu its press opened was still opening: run this instead. */
    private var pendingHold: String? = null
    private var openingSince = 0L
    /** The session the root shell and game controller were warmed up for. */
    private var warmed: Any? = null

    /** When a hold last ran (from Android's HOME key or the raw button), so it runs once per hold. */
    private var holdHandledAt = -10_000L
    /** A hold ran before Android delivered that press's HOME: ignore it when it comes (until then). */
    private var lateHomeUntil = 0L
    /** [lateHomeUntil] waits for the raw button's release, which sets the real window. */
    private var lateHomeOnRelease = false
    /** Leaving for the Game Switcher (its save runs first): HOME waits until then. */
    @Volatile private var leavingUntil = 0L

    private fun holdRan(now: Long) {
        holdHandledAt = now
        longFired = true
        // Android only gets HOME when the button is released on some handhelds, however long it
        // was held: ignored until shortly after the release, not for a fixed time after the hold.
        lateHomeOnRelease = homeState == null
        lateHomeUntil = if (homeState == null) now + 60_000 else 0L
    }
    private val longPress = Runnable { homeHeld(raw = false) }
    private val rawLongPress = Runnable { homeHeld(raw = true) }

    /**
     * HOME held for the hold time, seen through Android's HOME key or through the raw button
     * (root getevent, [HotkeyListener]): on some handhelds Android only gets HOME when the button
     * is released, too late to tell a hold. Whichever notices first runs the hold action.
     */
    private fun homeHeld(raw: Boolean) {
        val now = SystemClock.uptimeMillis()
        if (now - holdHandledAt < 1500 || now < leavingUntil) return
        if (foregroundPackage == packageName && overlay == null && !opening) {
            // MainUI in front: homeInMainUI handles it; the raw button helps where HOME comes late.
            if (raw && MainUI.settings.homeHoldInMainUI == "gs") {
                holdRan(now); main.removeCallbacks(mainUiHold)
                MainActivity.instance?.showGameSwitcher()
            }
            return
        }
        val press = MainUI.settings.homePress
        val hold = MainUI.settings.homeHold
        if (hold == "none" || hold == press) return
        if (homeState == HomePress.OPENED) {
            // This press already opened the menu: swap it for the hold action.
            holdRan(now)
            Log.d(TAG, "HOME held (${if (raw) "button" else "key"}) -> $hold, instead of the menu")
            if (opening) { pendingHold = hold; return }
            val menu = overlay?.scenes()?.firstOrNull() as? InGameMenuScene
            if (menu != null) { menu.leave(); performHold(hold) } else perform(hold)
            return
        }
        if (overlay != null || opening || locked() || !gameOrEmulatorInFront()) return
        holdRan(now)
        Log.d(TAG, "HOME held (${if (raw) "button" else "key"}) -> $hold")
        perform(hold)
    }

    /** Raw HOME button (root getevent): starts / cancels the hold timer. */
    private fun rawHome(down: Boolean) {
        main.removeCallbacks(rawLongPress)
        if (down) main.postDelayed(rawLongPress, MainUI.settings.homeHoldMs.toLong().coerceIn(200, 3000))
        else if (lateHomeOnRelease) {
            lateHomeOnRelease = false
            if (lateHomeUntil != 0L) lateHomeUntil = SystemClock.uptimeMillis() + 1500
        }
    }

    private val backHold = Runnable {
        Log.d(TAG, "Back held -> process manager")
        showProcesses()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Freezer.thawAll()
        updateHotkeyListener()
        runCatching {
            registerReceiver(wakeReceiver, android.content.IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        // Restarted (MainUI's process came back) with a game in front: it sends no window event
        // until it changes windows, so ask which app is in front instead of waiting for one.
        if (MainUI.sessions.active != null && Root.knownAvailable()) Thread {
            val fg = runCatching { Root.foregroundPackage() }.getOrNull()
            main.post { if (foregroundPackage == null && fg != null) { foregroundPackage = fg; if (gameInFront()) prewarm() } }
        }.start()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(wakeReceiver) }
        instance = null
        hotkeyGen.incrementAndGet()
        synchronized(hotkeyGen) { hotkeys?.stop(); hotkeys = null }
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
            if (event.className?.toString() == MainActivity::class.java.name) { foregroundPackage = pkg; leavingUntil = 0L }
            return
        }
        // SystemUI (notification shade, volume, power menu) over the game: remembered, see [dismissShade].
        if (pkg == "com.android.systemui") { systemUiAt = SystemClock.uptimeMillis(); return }
        if (pkg.contains("inputmethod")) return
        // Any keyboard (SwiftKey, ...): it comes and goes over the game, which sends no event when
        // it is back, so the keyboard must never count as the app in front.
        if (pkg != foregroundPackage && (event.className?.toString() == "android.inputmethodservice.SoftInputWindow" || isKeyboard(pkg))) return
        if (pkg != foregroundPackage) Log.d(TAG, "foreground: $pkg")
        foregroundPackage = pkg
        lastWindowChangeAt = SystemClock.uptimeMillis()
        if (gameInFront()) prewarm()
    }

    /**
     * Woken / unlocked: the lock screen (and the launcher behind it) sent window events, but the
     * game coming back in front may send none, so MainUI could think it isn't in a game any more
     * and HOME wouldn't open the menu. The app in front is read again (root) once things settle.
     */
    private val wakeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            for (delay in longArrayOf(700, 2500)) main.postDelayed({ recheckForeground("after ${i.action?.substringAfterLast('.')}") }, delay)
        }
    }

    private fun recheckForeground(why: String) {
        val s = MainUI.sessions.active ?: return
        if (!Root.knownAvailable()) { trustSession(why); return }
        Thread {
            val fg = runCatching { Root.foregroundPackage() }.getOrNull()
            main.post {
                if (fg == null || fg == "com.android.systemui") { trustSession(why); return@post }
                if (fg != foregroundPackage) io.github.marsinsanity.mainui.core.SessionLog.log("foreground $why: $fg (was $foregroundPackage)")
                foregroundPackage = fg
                if (fg == s.pkg) prewarm()
            }
        }.start()
    }

    /** MainUI noted as in front while its screen isn't showing, with a game running: it's the game. */
    private fun trustSession(why: String) {
        val s = MainUI.sessions.active ?: return
        if (foregroundPackage == packageName && MainActivity.instance?.isShowing != true) {
            io.github.marsinsanity.mainui.core.SessionLog.log("foreground $why: ${s.pkg} (MainUI isn't showing)")
            foregroundPackage = s.pkg
        }
    }

    /** When SystemUI last showed a window (the shade pulled down...), 0 once dealt with. */
    @Volatile private var systemUiAt = 0L

    /**
     * The notification shade (or another SystemUI window) may be over the game, since SystemUI
     * showed one after the game's last window: closed, and the time its closing takes (ms) to
     * wait before a capture, so it isn't the menu's background and the game's screenshot.
     */
    private fun dismissShade(): Long {
        if (systemUiAt <= lastWindowChangeAt || Build.VERSION.SDK_INT < 31) return 0L
        systemUiAt = 0L
        runCatching { performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE) }
        return 450L
    }

    /** The lock screen is up (over a game, whose window still counts as in front). */
    private fun locked(): Boolean = runCatching { getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)

    private fun isKeyboard(pkg: String): Boolean = runCatching {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == pkg }
    }.getOrDefault(false)

    /** MainUI's in-game menu (or popup) is showing or opening over the app in front. */
    val overlayShowing: Boolean get() = overlay != null || opening

    private fun gameInFront(): Boolean {
        val s = MainUI.sessions.active ?: return false
        return foregroundPackage == s.pkg
    }

    /**
     * [gameInFront], or an emulator opened by itself is in front (no game of MainUI's running):
     * it is adopted as the session (see SessionManager.adoptEmulator), so HOME works in it too.
     */
    private fun gameOrEmulatorInFront(): Boolean {
        if (gameInFront()) return true
        val fg = foregroundPackage ?: return false
        if (fg == packageName || fg == "com.android.systemui") return false
        val (added, removed) = MainUI.library.emulatorChoices()
        val isEmu = fg in added || (fg !in removed && runCatching { MainUI.emulators.isEmulatorPackage(fg) }.getOrDefault(false))
        if (!isEmu) return false
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(fg, 0)).toString() }.getOrDefault(fg)
        return MainUI.sessions.adoptEmulator(fg, label) != null && gameInFront()
    }

    /**
     * Gets the root shell and the game's controller ready as soon as a game is in front,
     * so the first HOME press opens the menu as fast as the later ones.
     */
    private fun prewarm() {
        val s = MainUI.sessions.active ?: return
        if (warmed === s) return
        warmed = s
        Thread({
            runCatching {
                if (Root.available()) {
                    Root.Shell.run("true", 5000)
                    // Root granted after the service started, or getevent gone: the raw button
                    // listener (menu combo, HOME hold) starts now.
                    if (hotkeys?.alive != true) main.post { if (instance === this) updateHotkeyListener() }
                }
                MainUI.sessions.controllerFor(s).warmUp()
            }
        }, "menu-prewarm").apply { isDaemon = true }.start()
    }

    /**
     * HOME and Back. HOME inside a game launched from MainUI runs the actions chosen in
     * Settings > Controls (default: press = in-game menu, hold = Game Switcher); everywhere
     * else it behaves normally. Back is only watched, never taken: holding it opens the
     * process manager popup.
     */
    override fun onKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_HOME -> onHome(event)
        KeyEvent.KEYCODE_BACK -> { watchBack(event); false }
        else -> false
    }

    private fun onHome(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) homeState = homeDown()
            return when (homeState) {
                HomePress.PASS -> false
                HomePress.MAINUI -> homeInMainUI(event)
                else -> true
            }
        }
        // The release ends the press the way its DOWN started it.
        val st = homeState
        homeState = null
        main.removeCallbacks(longPress)
        return when (st) {
            null -> overlay != null || opening
            HomePress.PASS -> false
            HomePress.MAINUI -> homeInMainUI(event)
            // HOME while the menu is up closes it (Continue)
            HomePress.CLOSE -> { overlay?.dispatch(io.github.marsinsanity.mainui.ui.Btn.B, false); true }
            HomePress.WAIT -> { if (!longFired) { Log.d(TAG, "HOME pressed -> ${MainUI.settings.homePress}"); perform(MainUI.settings.homePress) }; true }
            HomePress.OPENED, HomePress.IGNORE, HomePress.DONE -> true
        }
    }

    private fun homeDown(): HomePress {
        main.removeCallbacks(longPress)
        // Android's HOME for a button that was just held (and handled through the raw button).
        if (SystemClock.uptimeMillis() < lateHomeUntil) { lateHomeUntil = 0L; lateHomeOnRelease = false; return HomePress.IGNORE }
        // The save before the Game Switcher is running: another press must not open the menu
        // behind it (the menu would then show up over the Game Switcher).
        if (SystemClock.uptimeMillis() < leavingUntil) return HomePress.IGNORE
        if (opening && SystemClock.uptimeMillis() - openingSince > 10_000) {
            Log.w(TAG, "menu still opening after 10 s, giving up on it")
            opening = false
            openId++ // its late arrival is dropped (and its pause undone)
        }
        if (overlay != null) return HomePress.CLOSE
        // Its own press opens the menu; a second press while it opens must not close it again.
        if (opening) return HomePress.IGNORE
        // (A stale "MainUI is in front", from the lock screen after waking: the game is.)
        trustSession("on HOME")
        if (foregroundPackage == packageName) return HomePress.MAINUI
        val press = MainUI.settings.homePress
        val hold = MainUI.settings.homeHold
        // On the lock screen (over a game): the menu would show and save the lock screen, and
        // offer Exit / Save / Load without unlocking.
        if (locked() || !gameOrEmulatorInFront() || (press == "home" && hold == "home")) {
            Log.d(TAG, "HOME passed through (fg=$foregroundPackage, session=${MainUI.sessions.active?.pkg})")
            return HomePress.PASS
        }
        longFired = false
        val holdMs = MainUI.settings.homeHoldMs.toLong().coerceIn(200, 3000)
        // No separate hold action: act on the press itself.
        if (hold == "none" || hold == press) {
            longFired = true
            Log.d(TAG, "HOME pressed -> $press")
            perform(press)
            return HomePress.DONE
        }
        if (press == "menu") {
            // One press opens the menu right away, no waiting for the release. Still held
            // after the hold time, the menu gives way to the hold action (Game Switcher).
            Log.d(TAG, "HOME pressed -> menu")
            pendingHold = null
            showMenu()
            main.postDelayed(longPress, holdMs)
            return HomePress.OPENED
        }
        main.postDelayed(longPress, holdMs)
        return HomePress.WAIT
    }

    /** Back held anywhere (unless a MainUI popup is already up) opens the process manager popup. */
    private fun watchBack(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                main.removeCallbacks(backHold)
                if (MainUI.settings.backHold == "processes" && overlay == null && !opening)
                    main.postDelayed(backHold, MainUI.settings.homeHoldMs.toLong().coerceIn(200, 3000))
            }
            else -> main.removeCallbacks(backHold)
        }
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

    /** A hold action replacing the menu its press opened (the menu already saved the frame). */
    private fun performHold(action: String) {
        if (action == "gs") leaveForSwitcher(500) else perform(action)
    }

    /**
     * Opens the Game Switcher from the game in front. With auto save on, the game is saved
     * first, like OnionOS (RetroArch: into its auto slot, which it loads by itself when the game
     * starts again). [settleMs]: time for the game to run again after a menu closed.
     */
    private fun leaveForSwitcher(settleMs: Long) {
        val s = MainUI.sessions.active
        if (s == null || !MainUI.settings.autoSaveOnExit) { startGameSwitcher(); return }
        markLeaving()
        worker.execute {
            runCatching {
                val c = MainUI.sessions.controllerFor(s)
                c.prepare()
                if (MainUI.sessions.autoLoadPending(s)) {
                    // Its last save isn't loaded yet: saving the fresh start would overwrite it.
                    io.github.marsinsanity.mainui.core.SessionLog.log("Game Switcher: ${s.name} not saved, its last save wasn't loaded yet")
                    MainUI.sessions.toast("${s.name} wasn't saved: its last save wasn't loaded yet (Load it from the menu first)")
                } else if (c.savesToResume) {
                    if (settleMs > 0) Thread.sleep(settleMs)
                    val ok = c.saveToResume()
                    io.github.marsinsanity.mainui.core.SessionLog.log("Game Switcher: save for ${s.name}: ${if (ok) "done" else "FAILED ${c.saveProblem ?: ""}"}")
                    // RetroArch's save is checked (written and copied by now); others get time to write.
                    if (ok && c !is io.github.marsinsanity.mainui.emu.RetroArchController && c.states != io.github.marsinsanity.mainui.emu.StateSupport.DIRECT) Thread.sleep(800)
                    else if (!ok) MainUI.sessions.toast("${s.name} wasn't saved: ${c.saveProblem ?: "the save key failed"}")
                    // Saved, but not all the way (RetroArch: not into the auto slot): said too.
                    if (ok) c.saveProblem?.let { MainUI.sessions.toast("${s.name}: $it") }
                } else io.github.marsinsanity.mainui.core.SessionLog.log("Game Switcher: no save for ${s.name} (states ${c.states})")
            }.onFailure { Log.w(TAG, "save before Game Switcher failed", it); io.github.marsinsanity.mainui.core.SessionLog.log("Game Switcher: save failed: $it") }
            main.post { startGameSwitcher() }
        }
    }

    /** The in-game menu leaves for the Game Switcher (saving first): HOME waits until MainUI is in front. */
    fun markLeaving() { leavingUntil = SystemClock.uptimeMillis() + 30_000 }
    /** Not leaving after all (the save failed, the game stays): HOME works again. */
    fun clearLeaving() { leavingUntil = 0L }

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
        openingSince = SystemClock.uptimeMillis()
        val id = ++openId
        Log.d(TAG, "showing in-game menu for ${s.name}")
        // The notification shade over the game would be the menu's background and the game's
        // screenshot: closed first, and the capture waits for it to be gone.
        val shadeMs = dismissShade()
        worker.execute {
            val controller = MainUI.sessions.controllerFor(s)
            runCatching { controller.prepare() }
            val paused = MainUI.settings.pauseOnMenu && runCatching { controller.pause() }.getOrDefault(false)
            Log.d(TAG, "paused=$paused (${controller.javaClass.simpleName})")
            io.github.marsinsanity.mainui.core.SessionLog.log("menu opened for ${s.name} (${s.pkg}): ${controller.javaClass.simpleName}, states ${controller.states}, frozen $paused")
            if (shadeMs > 0) Thread.sleep(shadeMs)
            capture { bmp ->
                main.post {
                    if (id != openId) {
                        // Given up on (too slow) and opened again since: this open shows nothing,
                        // and its pause is undone (a stale thaw is ignored if the newer one froze).
                        Log.w(TAG, "late menu open $id dropped")
                        worker.execute { runCatching { controller.unpause() } }
                        return@post
                    }
                    if (MainUI.sessions.active !== s || foregroundPackage != s.pkg) {
                        // The game was left meanwhile (Game Switcher, exit): no menu over what is in front now.
                        Log.w(TAG, "menu open dropped, ${s.pkg} is no longer in front (${foregroundPackage})")
                        opening = false
                        pendingHold = null
                        worker.execute { runCatching { controller.unpause() } }
                        return@post
                    }
                    opening = false
                    val hold = pendingHold
                    pendingHold = null
                    if (hold != null) {
                        // HOME was held while the menu opened: the hold action instead of the menu.
                        Log.d(TAG, "menu replaced by $hold")
                        worker.execute {
                            if (bmp != null) runCatching { MainUI.sessions.saveScreenshot(s.gameKey, bmp) }
                            runCatching { controller.unpause() }
                            main.post { performHold(hold) }
                        }
                        return@post
                    }
                    val v = addOverlay { view -> InGameMenuScene(view, s, bmp, overlayMode = true, controller = controller, paused = paused) }
                    if (v == null) worker.execute { controller.unpause() } // could not show: never leave it paused
                    else if (paused) {
                        // The game lost focus to the overlay while frozen: let it acknowledge that
                        // (see GameController.settle). Fallback timer in case no focus callback comes.
                        var settled = false
                        val settle = { if (!settled && overlay === v) { settled = true; worker.execute { controller.settle() } } }
                        v.onWindowFocus = { has -> if (has) settle() }
                        main.postDelayed({ settle() }, 1500)
                    }
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

    /**
     * [translucent]: the app behind stays visible around the scene's own drawing (popups).
     * Returns the overlay this call added, null when it added none (one is already showing).
     */
    private fun addOverlay(translucent: Boolean = false, scene: (MainUIView) -> io.github.marsinsanity.mainui.ui.Scene): MainUIView? {
        if (overlay != null) return null
        var self: MainUIView? = null
        // Emptied (its last scene finished): remove this overlay, never a newer one.
        val view = MainUIView(this) { main.post { if (self != null && overlay === self) removeOverlay() } }
        self = view
        if (translucent) view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            if (translucent) PixelFormat.TRANSLUCENT else PixelFormat.OPAQUE,
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
        }.onFailure {
            Log.e(TAG, "addView failed", it)
            if (overlay === view) { overlay = null; runCatching { getSystemService(WindowManager::class.java).removeView(view) } }
            view.destroy()
            return null
        }
        return view
    }

    fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        val moved = v.takeMovedControllers()
        io.github.marsinsanity.mainui.core.SessionLog.log("overlay closed" + if (moved.isEmpty()) "" else ", controller axes moved on $moved")
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
        v.destroy()
        handBackControllers(moved)
        // A popup over MainUI itself paused its music; MainUI stayed resumed, so nothing else restarts it.
        // (Only while MainUI's screen really is in front: not when the popup switched to another
        // app, whose window event hasn't come yet, or over the lock screen.)
        if (foregroundPackage == packageName && MainActivity.instance?.isShowing == true) MainUI.themes.resumeBgm()
    }


    fun openGameSwitcher() {
        // Remember the current frame for the switcher, then bring MainUI forward.
        val s = MainUI.sessions.active
        markLeaving()
        main.postDelayed({
            capture { bmp ->
                if (bmp != null && s != null) MainUI.sessions.saveScreenshot(s.gameKey, bmp)
                main.post { leaveForSwitcher(0) }
            }
        }, dismissShade())
    }

    private fun startGameSwitcher() {
        // Until MainUI is in front (its window event), which takes a moment.
        leavingUntil = SystemClock.uptimeMillis() + 1000
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(MainActivity.EXTRA_GAME_SWITCHER, true))
    }

    /** The process manager as a popup over whatever is in front (Back held). */
    fun showProcesses() {
        if (overlay != null || opening) return
        addOverlay(translucent = true) { view -> io.github.marsinsanity.mainui.ui.scenes.ProcessPopupScene(view) }
    }

    fun globalBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    /**
     * Starts / restarts the root raw button listener: the optional menu combo, and the HOME
     * button's hold (see [homeHeld]). Without root there is nothing to listen with.
     */
    fun updateHotkeyListener() {
        val gen = hotkeyGen.incrementAndGet()
        synchronized(hotkeyGen) { hotkeys?.stop(); hotkeys = null }
        val combo = when (MainUI.settings.comboTrigger) {
            "select_start" -> setOf("BTN_SELECT", "BTN_START")
            "l3_r3" -> setOf("BTN_THUMBL", "BTN_THUMBR")
            else -> emptySet()
        }
        Thread {
            if (!Root.available()) return@Thread
            synchronized(hotkeyGen) {
                // A newer update (or the service ending) came while root was being checked.
                if (gen != hotkeyGen.get()) return@Thread
                hotkeys?.stop()
                hotkeys = HotkeyListener(combo, { main.post { if (instance === this && !locked() && gameOrEmulatorInFront() && SystemClock.uptimeMillis() >= leavingUntil) showMenu() } }) { key, down ->
                    if (key in HOME_BUTTONS) main.post { if (instance === this) rawHome(down) }
                }.also { it.start() }
            }
        }.start()
    }

    companion object {
        /**
         * After MainUI's menu or screen had focus: has every controller whose sticks / triggers
         * moved meanwhile report them again, now to the game ([moved], device ids). Otherwise
         * the game still sees them where they were, and a trigger let go in the menu (R2:
         * RetroArch's fast-forward, sound muted) stays held in the game. Root only.
         *
         * Through the controller's own /dev/input node (VirtualKeys.nudge), never as an injected
         * event: since Android 12 those come from device -1, which RetroArch took for a new
         * controller and gave a port (port 1 when it came first).
         */
        fun handBackControllers(moved: Set<Int>, afterMs: Long = 250) {
            if (moved.isEmpty() || !Root.knownAvailable()) return
            Thread({
                Thread.sleep(afterMs)
                for (id in moved) {
                    val r = runCatching { io.github.marsinsanity.mainui.core.VirtualKeys.nudge(id) }.getOrElse { "error ${it.message}" }
                    io.github.marsinsanity.mainui.core.SessionLog.log("controller $id re-reported its axes to ${instance?.foregroundPackage}: $r")
                }
            }, "axes-handback").apply { isDaemon = true }.start()
        }

        private const val TAG = "MainUI.Overlay"
        /** Raw names of a handheld's HOME button (Linux KEY_HOMEPAGE, a gamepad's BTN_MODE). */
        val HOME_BUTTONS = setOf("KEY_HOMEPAGE", "BTN_MODE")

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
 * Watches raw input events through root `getevent -ql`: fires when every key of [combo]
 * is held, and reports every button to [onKey]. Passive: the game still receives every key.
 */
class HotkeyListener(
    private val combo: Set<String>,
    private val onCombo: () -> Unit,
    /** Every button press / release: (Linux key name, down). */
    private val onKey: (String, Boolean) -> Unit = { _, _ -> },
) {
    @Volatile private var running = true
    @Volatile private var process: Process? = null
    /** Still listening (false once getevent is gone for good: started again by the service). */
    @Volatile var alive = true
        private set

    fun start() {
        Thread {
            try {
                // getevent ending by itself (killed, a device change) is restarted a few times.
                var restarts = 0
                while (running) {
                    val t0 = android.os.SystemClock.uptimeMillis()
                    try {
                        val p = ProcessBuilder(*Root.rootArgv("getevent -ql")).redirectErrorStream(true).start()
                        process = p
                        if (!running) { p.destroy(); break }
                        val down = HashSet<String>()
                        p.inputStream.bufferedReader().useLines { lines ->
                            for (line in lines) {
                                if (!running) break
                                val parts = line.substringAfter(':').trim().split(Regex("\\s+"))
                                if (parts.size < 3 || parts[0] != "EV_KEY") continue
                                val key = parts[1]
                                when (parts[2]) {
                                    "DOWN" -> { down += key; onKey(key, true); if (combo.isNotEmpty() && down.containsAll(combo)) { down.clear(); onCombo() } }
                                    "UP" -> { down -= key; onKey(key, false) }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("MainUI.Hotkeys", "listener stopped", e)
                    }
                    if (!running) break
                    // A run that lasted a while starts the count again; one that fails at once gives up soon.
                    if (android.os.SystemClock.uptimeMillis() - t0 > 60_000) restarts = 0
                    if (++restarts > 5) break
                    Thread.sleep(2000)
                }
            } finally {
                alive = false
            }
        }.apply { isDaemon = true; name = "mainui-hotkeys"; start() }
    }

    fun stop() {
        running = false
        process?.destroy()
    }
}
