package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainActivity
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.service.OverlayService
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.ItemType
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

internal fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) =
    UiItem(label, ItemType.TOGGLE, value = if (get()) 1 else 0, onChange = { set(it.value == 1) })

internal fun multi(label: String, options: List<Pair<String, String>>, get: () -> String, set: (String) -> Unit) =
    UiItem(label, ItemType.MULTI, values = options.map { it.second },
        value = options.indexOfFirst { it.first == get() }.coerceAtLeast(0),
        onChange = { set(options[it.value].first) })

internal fun action(label: String, sub: String? = null, onSelect: (UiItem) -> Unit) = UiItem(label, sub = sub, onSelect = onSelect)

/** Tweaks / Settings, grouped into sub lists like Onion's Tweaks app. */
class SettingsScene(host: SceneHost) : ListScene(host, "Settings") {
    override val showPreview = false

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        list.set(listOf(
            action("Theme", MainUI.themes.current.name) { host.push(ThemeSwitcherScene(host)) },
            action("User interface") { host.push(UiSettingsScene(host)) },
            action("Controls & in-game menu") { host.push(ControlSettingsScene(host)) },
            action("Library & storage") { host.push(LibrarySettingsScene(host)) },
            action("Emulators & cores") { host.push(EmulatorsScene(host)) },
            action("Scraper (ScreenScraper)") { host.push(ScraperScene(host, null)) },
            action("Process manager") { host.push(ProcessManagerScene(host)) },
            action("Permissions & setup") { host.push(SetupScene(host)) },
            action("Android settings") {
                host.hostContext.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            action("About", "v${BuildConfig.VERSION_NAME}") {
                host.push(DialogScene(host, "MainUI for Android",
                    "An OnionOS style frontend.\nThemes: ${MainUI.storage.themeDirs().joinToString { it.path }.ifEmpty { "<storage>/Themes" }}\nData: ${MainUI.storage.dataRoot().path}",
                    "OK", null))
            },
        ))
    }
}

class UiSettingsScene(host: SceneHost) : ListScene(host, "User interface") {
    override val showPreview = false

    override fun onEnter() {
        val s = MainUI.settings
        list.set(listOf(
            multi("Home screen", listOf("menu" to "Main menu", "carousel" to "Carousel"), { s.homeLayout }) {
                s.homeLayout = it; restartHome()
            },
            multi("Games view", listOf("grid" to "Grid", "carousel" to "Carousel"), { s.systemsLayout }) { s.systemsLayout = it },
            toggle("Show Recents", { s.showRecents }) { s.showRecents = it },
            toggle("Show Favorites", { s.showFavorites }) { s.showFavorites = it },
            toggle("Show Expert (RetroArch)", { s.showExpert }) { s.showExpert = it },
            toggle("Show Search tile", { s.showSearchTile }) { s.showSearchTile = it },
            toggle("Show Android system", { s.showAndroidSystem }) { s.showAndroidSystem = it },
            toggle("Show empty systems", { s.showEmptySystems }) { s.showEmptySystems = it },
            toggle("Show game art in lists", { s.showGameArt }) { s.showGameArt = it },
            multi("Background fit", listOf("cover" to "Crop", "stretch" to "Stretch", "fit" to "Fit"), { s.backgroundFit }) { s.backgroundFit = it },
            toggle("Smooth scaling", { s.smoothScaling }) { s.smoothScaling = it },
            toggle("Show clock", { s.showClock }) { s.showClock = it },
            toggle("Menu sounds", { s.uiSounds }) { s.uiSounds = it; MainUI.themes.reloadSounds() },
            toggle("Theme background music", { s.bgm }) { s.bgm = it; MainUI.themes.reloadSounds() },
        ))
    }

    private fun restartHome() {
        MainActivity.instance?.let { a -> a.view.post { a.view.setRoot(HomeScenes.root(a.view)); a.view.push(SettingsScene(a.view)); a.view.push(UiSettingsScene(a.view)) } }
    }
}

class ControlSettingsScene(host: SceneHost) : ListScene(host, "Controls") {
    override val showPreview = false

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        val s = MainUI.settings
        val overlayOn = OverlayService.isEnabled(host.hostContext)
        list.set(listOf(
            toggle("Swap A/B and X/Y", { s.swapAB }) { s.swapAB = it },
            multi("In-game menu trigger", listOf(
                "home" to "Home button", "select_start" to "Select+Start", "l3_r3" to "L3+R3", "off" to "Off",
            ), { s.ingameTrigger }) { s.ingameTrigger = it; OverlayService.instance?.updateHotkeyListener() },
            toggle("Hold Home: Game Switcher", { s.longHomeGameSwitcher }) { s.longHomeGameSwitcher = it },
            multi("Home inside MainUI", listOf(
                "menu_then_gs" to "Menu, then GS", "gs" to "Game Switcher", "menu" to "Main menu",
            ), { s.homeInMainUI }) { s.homeInMainUI = it },
            toggle("Pause game while menu is open", { s.pauseOnMenu }) { s.pauseOnMenu = it },
            toggle("Auto save state on exit", { s.autoSaveOnExit }) { s.autoSaveOnExit = it },
            action("Overlay service", if (overlayOn) "enabled" else "disabled") { openAccessibility(host) },
        ))
    }

    companion object {
        fun openAccessibility(host: SceneHost) {
            host.hostContext.startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            host.toast("Enable \"MainUI in-game menu\"", 3000)
        }
    }
}

class LibrarySettingsScene(host: SceneHost) : ListScene(host, "Library") {
    override val showPreview = false

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        val s = MainUI.settings
        val roots = MainUI.storage.romRoots()
        list.set(listOf(
            toggle("Auto-sort Android games", { s.autoSortAndroidGames }) { s.autoSortAndroidGames = it },
            action("Sort installed games now") {
                host.io({ s.lastPackageReconcile = 0; MainUI.apps.reconcileNewInstalls(MainUI.emulators); MainUI.library.androidGames().size }) {
                    host.toast("$it Android games")
                }
            },
            toggle("Show unknown rom folders", { s.showUnknownFolders }) { s.showUnknownFolders = it; MainUI.games.invalidate() },
            multi("MainUI data folder", listOf("auto" to "Auto", "sd" to "SD card", "internal" to "Internal"), { s.dataLocation }) {
                s.dataLocation = it; MainUI.reloadStorage()
            },
            action("Rom folders", if (roots.isEmpty()) "none found" else "${roots.size}") {
                host.push(DialogScene(host, "Rom folders",
                    (roots.map { it.path }.ifEmpty { listOf("Create <SD card>/roms/<system> folders") }).joinToString("\n"), "OK", null))
            },
            action("Add rom folder...") {
                host.push(io.github.marsinsanity.mainui.ui.KeyboardScene(host, "Absolute path", "/storage/") { p ->
                    if (p.isNotBlank()) { s.extraRomRoots = (s.extraRomRoots.lines() + p.trim()).filter { it.isNotBlank() }.joinToString("\n"); MainUI.games.invalidate(); build() }
                })
            },
            action("Create dArkOS folders") {
                host.push(DialogScene(host, "Create folders", "Create the dArkOS rom folders on ${MainUI.storage.sdCard?.label ?: "internal storage"}?") {
                    host.io({ createFolders() }) { host.toast("Created $it folders"); build() }
                })
            },
            action("Rescan library") { MainUI.games.invalidate(); MainUI.apps.invalidate(); host.toast("Library will be rescanned") },
        ))
    }

    private fun createFolders(): Int {
        val vol = MainUI.storage.sdCard ?: MainUI.storage.internal
        val roms = java.io.File(vol.dir, "roms")
        var n = 0
        for (d in MainUI.systems.defs) for (f in d.folders.take(1)) {
            val dir = java.io.File(roms, f)
            if (!dir.exists() && dir.mkdirs()) n++
        }
        java.io.File(roms, "bios").mkdirs()
        java.io.File(vol.dir, "Themes").mkdirs()
        MainUI.games.invalidate()
        return n
    }
}

/** Detected emulators, RetroArch cores and the RetroArch setup helper. */
class EmulatorsScene(host: SceneHost) : ListScene(host, "Emulators") {
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "RESCAN")

    override fun onEnter() = build()

    private fun build() {
        val em = MainUI.emulators
        val items = mutableListOf<UiItem>()
        val ra = em.raPackage
        val cores = em.raCores
        items += UiItem("RetroArch", sub = when {
            ra == null -> "not installed"
            cores == null -> "$ra (cores: root needed)"
            else -> "${cores.size} cores"
        }, onSelect = { if (cores != null) host.push(CoresScene(host)) })
        if (ra != null) items += action("Configure RetroArch for MainUI") {
            host.push(DialogScene(host, "Configure RetroArch",
                "Enables RetroArch's network commands (needed for Save, Load and Exit in the in-game menu) and auto save states. RetroArch will be closed.") {
                host.io({ em.configureRetroArch() }) { host.toast(it, 3000) }
            })
        }
        for (d in em.standaloneDefs()) {
            val pkg = em.installedPackage(d.id)
            items += UiItem(d.name, sub = pkg ?: "not installed", disabled = pkg == null)
        }
        list.set(items)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.Y && !repeat) {
            host.toast("Scanning...")
            host.io({ MainUI.emulators.loadDefs(); MainUI.emulators.refresh() }) { build() }
            return true
        }
        return super.onKey(b, repeat)
    }
}

class CoresScene(host: SceneHost) : ListScene(host, "RetroArch cores") {
    override val showPreview = false
    override fun onEnter() {
        val em = MainUI.emulators
        list.set((em.raCores ?: emptySet()).sorted().map { UiItem(em.coreDisplayName(it), sub = it) })
    }
}

/**
 * First run checklist: all files access, default launcher, overlay service,
 * root. Everything is optional except storage access.
 */
class SetupScene(host: SceneHost) : ListScene(host, "Setup") {
    override val showPreview = false
    override var aLabel: String? = "OPEN"
    override var bLabel: String? = "DONE"

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        val ctx = host.hostContext
        val storageOk = MainUI.storage.hasAllFilesAccess()
        val launcherOk = isDefaultLauncher()
        val overlayOk = OverlayService.isEnabled(ctx)
        val rootKnown = Root.knownAvailable()
        val writeOk = AndroidSettings.System.canWrite(ctx)
        list.set(listOf(
            UiItem("All files access", ItemType.ACTION, sub = if (storageOk) "OK" else "REQUIRED", onSelect = {
                if (Build.VERSION.SDK_INT >= 30) ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }),
            UiItem("Set MainUI as home app", sub = if (launcherOk) "OK" else "recommended", onSelect = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }),
            UiItem("In-game menu overlay", sub = if (overlayOk) "OK" else "recommended", onSelect = { ControlSettingsScene.openAccessibility(host) }),
            UiItem("Root (Magisk)", sub = if (rootKnown) "OK" else "tap to request", onSelect = {
                host.io({ Root.available() }) { ok ->
                    host.toast(if (ok) "Root granted" else "Root not available"); build()
                    if (ok) MainUI.worker.execute { MainUI.emulators.refresh() }
                }
            }),
            UiItem("Brightness control", sub = if (writeOk || rootKnown) "OK" else "optional", onSelect = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }),
            UiItem("Notifications (scraper progress)", sub = "optional", onSelect = {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }),
            UiItem("Continue", onSelect = { back() }),
        ))
    }

    private fun isDefaultLauncher(): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val res = host.hostContext.packageManager.resolveActivity(i, 0)
        return res?.activityInfo?.packageName == host.hostContext.packageName
    }

    override fun back() {
        if (!MainUI.storage.hasAllFilesAccess()) {
            host.toast("MainUI needs All files access to read your roms", 2500)
        }
        MainUI.settings.setupDone = true
        MainUI.reloadStorage()
        close()
    }
}
