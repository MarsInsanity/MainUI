package io.github.marsinsanity.mainui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.core.Settings
import io.github.marsinsanity.mainui.core.Storage
import io.github.marsinsanity.mainui.data.Apps
import io.github.marsinsanity.mainui.data.GameRepository
import io.github.marsinsanity.mainui.data.Library
import io.github.marsinsanity.mainui.data.SystemRegistry
import io.github.marsinsanity.mainui.emu.EmulatorManager
import io.github.marsinsanity.mainui.emu.SessionManager
import io.github.marsinsanity.mainui.theme.ThemeManager
import java.util.concurrent.Executors

/** Process wide singletons. Initialised in [App.onCreate]. */
object MainUI {
    lateinit var app: App
    lateinit var settings: Settings
    lateinit var storage: Storage
    lateinit var systems: SystemRegistry
    lateinit var games: GameRepository
    lateinit var library: Library
    lateinit var apps: Apps
    lateinit var emulators: EmulatorManager
    lateinit var sessions: SessionManager
    lateinit var themes: ThemeManager

    /** Single background thread for library maintenance work. */
    val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mainui-worker").apply { isDaemon = true } }

    /** Re-reads everything that depends on storage (after permissions are granted, SD swap...). */
    fun reloadStorage() {
        storage.invalidate()
        library.load()
        games.invalidate()
        themes.reload()
        worker.execute {
            emulators.loadDefs(); emulators.refresh()
            // First run with the real library: sort the installed games into it now.
            if (storage.hasAllFilesAccess()) apps.reconcileNewInstalls(emulators)
        }
    }
}

class App : Application() {
    /**
     * MainUI stays in memory behind a game (it is the launcher and hosts the overlay service), so
     * its decoded pictures (box art, screenshots, icons: tens of MB) are given back once its screens
     * are hidden; a 2 GB device's emulator needs them more. They load again when shown.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) runCatching {
            io.github.marsinsanity.mainui.ui.Images.clear()
            io.github.marsinsanity.mainui.ui.Glyphs.clear()
            MainUI.apps.trimIcons()
        }
    }

    override fun onCreate() {
        super.onCreate()
        MainUI.app = this
        val s = Settings(this)
        MainUI.settings = s
        MainUI.storage = Storage(this, s)
        MainUI.systems = SystemRegistry(this, MainUI.storage)
        MainUI.games = GameRepository(MainUI.systems, MainUI.storage)
        io.github.marsinsanity.mainui.data.MainUIRef.metadataSource = { s.metadataSource }
        io.github.marsinsanity.mainui.data.MainUIRef.showHidden = { s.showHiddenGames }
        io.github.marsinsanity.mainui.data.MainUIRef.hidden = { key, bios -> MainUI.library.isGameHidden(key, bios) }
        io.github.marsinsanity.mainui.data.MainUIRef.emulatorInstalled = { ref -> MainUI.emulators.isInstalled(ref) }
        io.github.marsinsanity.mainui.data.MainUIRef.showPortsSystem = { s.showPortsSystem }
        MainUI.library = Library(MainUI.storage).apply { load() }
        MainUI.apps = Apps(this, MainUI.library, s)
        MainUI.emulators = EmulatorManager(this, MainUI.storage, s, MainUI.library)
        MainUI.sessions = SessionManager(this, s, MainUI.storage, MainUI.library, MainUI.emulators)
        MainUI.themes = ThemeManager(this, MainUI.storage, s)
        MainUI.themes.reloadSounds() // (music starts when MainActivity resumes, not behind a game)

        io.github.marsinsanity.mainui.emu.Freezer.thawAll()
        io.github.marsinsanity.mainui.emu.ApiPauses.resumeLeftover()
        // Root first (MainUI is made for rooted handhelds): the root shell and the virtual
        // keyboard are ready before the first game, so its first Save / Load doesn't wait ~1 s
        // for them, and RetroArch can see the keyboard from the start.
        Thread({
            if (Root.available()) {
                Root.Shell.run("true", 5000)
                io.github.marsinsanity.mainui.core.VirtualKeys.warmUp()
            }
        }, "root-warmup").apply { isDaemon = true }.start()
        MainUI.worker.execute {
            MainUI.emulators.refresh()
            // Only with the real library (before access is granted it's a stand-in that goes).
            if (MainUI.storage.hasAllFilesAccess()) MainUI.apps.reconcileNewInstalls(MainUI.emulators)
            // APKs handed to Android's installer (no root) stay behind, one per version: gone once
            // they are an hour old (an installation still being confirmed keeps its file).
            runCatching {
                val old = System.currentTimeMillis() - 3_600_000L
                for (d in listOf("appstore", "update")) java.io.File(cacheDir, d).listFiles()
                    ?.filter { it.isFile && (it.name.endsWith(".apk") || it.name.endsWith(".apk.part")) && it.lastModified() < old }
                    ?.forEach { it.delete() }
            }
        }

        // PACKAGE_ADDED cannot be received by manifest receivers since Android 8,
        // but as the home app our process is practically always alive.
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val pkg = i.data?.schemeSpecificPart ?: return
                val replacing = i.getBooleanExtra(Intent.EXTRA_REPLACING, false)
                MainUI.worker.execute {
                    MainUI.apps.invalidate()
                    if (i.action == Intent.ACTION_PACKAGE_ADDED && !replacing) MainUI.apps.onPackageAdded(pkg, MainUI.emulators)
                    // Uninstalled (Uninstall game, or anywhere else): its Ports entry and recents go,
                    // and the screen showing is read again.
                    if (i.action == Intent.ACTION_PACKAGE_REMOVED && !replacing) {
                        runCatching { io.github.marsinsanity.mainui.ui.scenes.Ports.forgetUninstalled(pkg) }
                        android.os.Handler(android.os.Looper.getMainLooper()).post { MainActivity.instance?.refreshTop() }
                    }
                    if (MainUI.emulators.isEmulatorPackage(pkg)) MainUI.emulators.refresh()
                }
            }
        }, filter)

        // The game paused while the device sleeps, and running again once it is woken and
        // unlocked (a moment later: MainUI's screen, if it is what comes back, takes over).
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val woken = Runnable {
            MainUI.sessions.woken(MainActivity.instance?.isShowing == true ||
                io.github.marsinsanity.mainui.service.OverlayService.instance?.menuHoldsGame == true)
        }
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                main.removeCallbacks(woken)
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> MainUI.sessions.screenOff(
                        io.github.marsinsanity.mainui.service.OverlayService.instance?.menuHoldsGame == true)
                    Intent.ACTION_SCREEN_ON -> {
                        val locked = runCatching { getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked }.getOrDefault(false)
                        if (!locked) main.postDelayed(woken, 600)
                    }
                    Intent.ACTION_USER_PRESENT -> main.postDelayed(woken, 600)
                }
            }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        })

        // An SD card mounted after MainUI started (late at boot) or swapped: MainUI's data folder
        // can move with it, so everything read from storage is read again.
        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { MainUI.reloadStorage() }
        }, IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        })
    }
}
