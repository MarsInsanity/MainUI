package io.github.marsinsanity.mainui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import io.github.marsinsanity.mainui.service.OverlayService
import io.github.marsinsanity.mainui.ui.MainUIView
import io.github.marsinsanity.mainui.ui.scenes.GameSwitcherScene
import io.github.marsinsanity.mainui.ui.scenes.HomeScenes
import io.github.marsinsanity.mainui.ui.scenes.InGameMenuScene
import io.github.marsinsanity.mainui.ui.scenes.SetupScene

/**
 * The launcher (HOME) activity. Everything is drawn by [MainUIView]; this
 * class only handles window flags and the HOME button semantics:
 *  - HOME while MainUI is visible: back to the main menu, then Game Switcher
 *    (OnionOS' MENU behaviour).
 *  - HOME while a game runs and the accessibility overlay is not available:
 *    open the in-game menu here as a fallback.
 */
class MainActivity : Activity() {

    lateinit var view: MainUIView
        private set
    private var resumed = false
    /** Resumed (in front, not paused by another app or the lock screen). */
    val isShowing: Boolean get() = resumed
    private var leftForSession = false
    /** The last pause was MainUI bringing the game to the front (see SessionManager.shownAt). */
    private var leftForGame = false
    private var resumedAt = 0L
    private var overlayChecked = false
    /** Set when we were brought forward on purpose while the game keeps running. */
    private var keepSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No FLAG_KEEP_SCREEN_ON: the launcher follows the screen timeout. Long jobs keep the
        // screen on while they run (Scene.keepsScreenOn, MainUIView).
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        view = MainUIView(this)
        setContentView(view)
        instance = this
        view.setRoot(HomeScenes.root(view))
        if (!MainUI.settings.setupDone || !MainUI.storage.hasAllFilesAccess()) view.push(SetupScene(view))
        // Started fresh by the overlay's Game Switcher / in-game menu (the activity didn't exist yet).
        else if (savedInstanceState == null) handleExtras(intent)
    }

    /** The Game Switcher / fallback in-game menu asked for by an intent. True when handled. */
    private fun handleExtras(intent: Intent?): Boolean {
        if (intent == null) return false
        if (intent.getBooleanExtra(EXTRA_INGAME_MENU, false)) { keepSession = true; showInGameMenuFallback(); return true }
        if (intent.getBooleanExtra(EXTRA_GAME_SWITCHER, false)) {
            keepSession = true
            if (view.top() !is GameSwitcherScene) view.push(GameSwitcherScene(view))
            return true
        }
        return false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }

    private fun immersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (handleExtras(intent)) return
        if (!isHome) return
        // Android pauses a resumed activity before it hands it a new intent, so `resumed` is always
        // false here; the window keeps its focus through that pause. MainUI on screen = HOME inside it.
        val showing = resumed || hasWindowFocus()
        val session = MainUI.sessions.active
        if (!showing && session != null && leftForSession) {
            // HOME while a game session exists: the game may still be running behind, keep it.
            keepSession = true
            // HOME pressed inside the game without the accessibility overlay: the fallback menu.
            // With the overlay running, HOME only gets here when the game wasn't in front (it
            // handles HOME in games itself), and after starting another app it isn't "in the game".
            // (Long presses can't be told apart here: Android only delivers the HOME intent.)
            if (OverlayService.instance == null && leftForGame) {
                when (MainUI.settings.homePress) {
                    "menu" -> showInGameMenuFallback()
                    "gs" -> view.push(GameSwitcherScene(view))
                    else -> {}
                }
            }
            return
        }
        if (showing) {
            // The resume after this pause is never "the game ended": a game being brought back
            // (resume / the Game Switcher's A, its session already marked shown) keeps its session.
            keepSession = true
            // Setup is finished with B / Continue, which loads the real library (HOME would skip that).
            if (view.scenes().any { it is io.github.marsinsanity.mainui.ui.scenes.SetupScene }) return
            when (MainUI.settings.homeInMainUI) {
                "gs" -> openGameSwitcher()
                "menu" -> view.popToRoot()
                else -> if (view.scenes().size > 1) view.popToRoot() else openGameSwitcher()
            }
        }
    }

    /** Opens the Game Switcher unless it is already showing (HOME held in MainUI). */
    fun showGameSwitcher() {
        if (view.top() !is GameSwitcherScene) view.push(GameSwitcherScene(view))
    }

    private fun openGameSwitcher() {
        if (view.top() is GameSwitcherScene) { view.pop(); return }
        view.push(GameSwitcherScene(view))
    }

    private fun showInGameMenuFallback() {
        val s = MainUI.sessions.active ?: return
        if (view.top() is InGameMenuScene) return
        view.push(InGameMenuScene(view, s, null, overlayMode = false))
    }

    /**
     * MainUI's decoded pictures (box art, screenshots, icons: tens of MB) are given back a little
     * after its screens are hidden: behind a game a 2 GB device's emulator needs them more. Android's
     * own onTrimMemory(UI_HIDDEN) doesn't come while the overlay service keeps the process important.
     * (Delayed: a quick screen off / on keeps them.)
     */
    private val trimCaches = Runnable {
        if (!resumed) runCatching {
            io.github.marsinsanity.mainui.ui.Images.clear()
            io.github.marsinsanity.mainui.ui.Glyphs.clear()
            MainUI.apps.trimIcons()
        }
    }

    override fun onStart() {
        super.onStart()
        window.decorView.removeCallbacks(trimCaches)
    }

    override fun onStop() {
        super.onStop()
        // A game being started has come up in front (or something else did): A starts games again.
        // (Not onPause: HOME inside MainUI and a superuser prompt pause it without anything starting.)
        io.github.marsinsanity.mainui.ui.scenes.Launch.launchDone()
        if (!isChangingConfigurations) window.decorView.postDelayed(trimCaches, 10_000)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resumedAt = android.os.SystemClock.uptimeMillis()
        view.takeMovedControllers() // from now on: what the game behind misses
        // (Not the game this process paused on purpose: HOME inside MainUI pauses and resumes it.)
        if (!MainUI.sessions.isParked) io.github.marsinsanity.mainui.emu.Freezer.thawAll()
        // Cores downloaded in RetroArch while MainUI was in the background; and a session restored
        // after MainUI's process restarted whose game was quit meanwhile is ended (once).
        MainUI.worker.execute {
            runCatching { MainUI.sessions.endRestoredIfGone() }
            runCatching { MainUI.emulators.refreshCoresIfStale(15_000) }
        }
        // The in-game menu service was on before and isn't now: Android turns an app's accessibility
        // service off when the app is force stopped, and HOME in games then does nothing much.
        // Said once per process, a few seconds in (the service connects shortly after MainUI starts).
        if (!overlayChecked) {
            overlayChecked = true
            window.decorView.postDelayed({
                if (OverlayService.instance == null && MainUI.settings.prefs.getBoolean("overlay_was_on", false))
                    view.toast("The in-game menu service is off: Settings > Permissions & setup", 5000)
            }, 4000)
        }
        immersive()
        if (MainUI.sessions.active != null && leftForSession && !keepSession) {
            // Back in MainUI without a HOME press: the emulator / app closed itself.
            MainUI.sessions.endActive()
        }
        leftForSession = false
        keepSession = false
        MainUI.themes.resumeBgm()
        view.top()?.onResume()
        view.invalidate()
        // A standalone emulator's game behind MainUI (Game Switcher, menus) is paused meanwhile.
        MainUI.sessions.park()
    }

    /** Reads the screen showing again (an app was uninstalled meanwhile). Main thread. */
    fun refreshTop() {
        if (!resumed) return
        view.top()?.onResume()
        view.invalidate()
    }

    override fun onPause() {
        super.onPause()
        resumed = false
        // Whatever comes next (the game, another app): never a frozen game. The screen going off
        // keeps it paused while the device sleeps (Sessions.screenOff), unless that is turned off.
        // Once the lifecycle step is done: HOME inside MainUI pauses it only around onNewIntent.
        window.decorView.post {
            val awake = runCatching { getSystemService(android.os.PowerManager::class.java).isInteractive }.getOrDefault(true)
            if (!resumed && (awake || !MainUI.settings.pauseWhenAsleep)) Thread({ MainUI.sessions.unpark() }, "unpark").apply { isDaemon = true }.start()
        }
        // Left for the game only when MainUI started or brought back the game during this visit;
        // the screen turning off or another app opened from MainUI must not end the session.
        leftForSession = MainUI.sessions.active != null && MainUI.sessions.shownAt >= resumedAt
        // Back to a game that was running (Game Switcher, fallback menu): hand it the triggers /
        // sticks that moved here. Not for a game starting now: it reads the controller fresh.
        val moved = view.takeMovedControllers()
        if (leftForSession && MainUI.sessions.resumedAt >= resumedAt) OverlayService.handBackControllers(moved, afterMs = 700)
        // Paused because MainUI just started / resumed the game (since it came to the front)?
        val shown = MainUI.sessions.shownAt
        leftForGame = leftForSession && shown >= resumedAt && android.os.SystemClock.uptimeMillis() - shown < 15_000
        MainUI.themes.pauseBgm()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        view.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Handled as Btn.B by MainUIView; never leave the launcher.
    }

    companion object {
        const val EXTRA_INGAME_MENU = "mainui.ingame_menu"
        const val EXTRA_GAME_SWITCHER = "mainui.game_switcher"
        var instance: MainActivity? = null
            private set
    }
}
