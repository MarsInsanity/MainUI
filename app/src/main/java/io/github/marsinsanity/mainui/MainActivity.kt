package io.github.marsinsanity.mainui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
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
    private var leftForSession = false
    /** The last pause was MainUI bringing the game to the front (see SessionManager.shownAt). */
    private var leftForGame = false
    private var resumedAt = 0L
    /** Set when we were brought forward on purpose while the game keeps running. */
    private var keepSession = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        window.setDecorFitsSystemWindows(false)
        window.insetsController?.let {
            it.hide(WindowInsets.Type.systemBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val isHome = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)
        if (handleExtras(intent)) return
        if (!isHome) return
        val session = MainUI.sessions.active
        if (!resumed && session != null && leftForSession) {
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
        if (resumed) {
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

    override fun onResume() {
        super.onResume()
        resumed = true
        resumedAt = android.os.SystemClock.uptimeMillis()
        view.takeMovedControllers() // from now on: what the game behind misses
        io.github.marsinsanity.mainui.emu.Freezer.thawAll()
        // Cores downloaded in RetroArch while MainUI was in the background.
        MainUI.worker.execute { runCatching { MainUI.emulators.refreshCoresIfStale(15_000) } }
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

    override fun onPause() {
        super.onPause()
        resumed = false
        // Whatever comes next (the game, another app, the screen going off): never a frozen game.
        Thread({ MainUI.sessions.unpark() }, "unpark").apply { isDaemon = true }.start()
        leftForSession = MainUI.sessions.active != null
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
