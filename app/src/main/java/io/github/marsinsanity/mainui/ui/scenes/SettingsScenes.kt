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
            action("About", "v${BuildConfig.VERSION_NAME}") { host.push(AboutScene(host)) },
        ))
    }
}

class UiSettingsScene(host: SceneHost) : ListScene(host, "User interface") {
    override val showPreview = false

    override fun onEnter() {
        val s = MainUI.settings
        list.set(listOf(
            multi("Home screen", listOf("menu" to "Main menu", "row" to "Systems row"), { s.homeLayout.replace("carousel", "row") }) {
                s.homeLayout = it; restartHome()
            },
            multi("Games view", listOf("grid" to "Grid", "row" to "Row"), { s.systemsLayout.replace("carousel", "row") }) { s.systemsLayout = it },
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
            multi("Press Home in game", HOME_ACTIONS, { s.homePress }) { s.homePress = it },
            multi("Hold Home in game", HOME_ACTIONS, { s.homeHold }) { s.homeHold = it },
            multi("Hold time", listOf("300" to "0.3 s", "500" to "0.5 s", "700" to "0.7 s", "1000" to "1 s", "1500" to "1.5 s"),
                { s.homeHoldMs.toString() }) { s.homeHoldMs = it.toInt() },
            multi("Extra menu combo", listOf(
                "off" to "Off", "select_start" to "Select+Start", "l3_r3" to "L3+R3",
            ), { s.comboTrigger }) { s.comboTrigger = it; OverlayService.instance?.updateHotkeyListener() },
            multi("Press Home in MainUI", listOf(
                "menu_then_gs" to "Menu, then GS", "gs" to "Game Switcher", "menu" to "Main menu",
            ), { s.homeInMainUI }) { s.homeInMainUI = it },
            multi("Hold Home in MainUI", listOf("gs" to "Game Switcher", "none" to "Nothing"),
                { s.homeHoldInMainUI }) { s.homeHoldInMainUI = it },
            toggle("Pause game while menu is open", { s.pauseOnMenu }) { s.pauseOnMenu = it },
            toggle("Freeze standalone emulators (root)", { s.freezeStandalone }) { s.freezeStandalone = it },
            toggle("Auto save state on exit", { s.autoSaveOnExit }) { s.autoSaveOnExit = it },
            action("Overlay service", if (overlayOn) "enabled" else "disabled") { openAccessibility(host) },
        ))
    }

    companion object {
        val HOME_ACTIONS = listOf("menu" to "In-game menu", "gs" to "Game Switcher", "home" to "Go to MainUI", "none" to "Nothing")

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
            action("Hidden apps", "${MainUI.library.hiddenApps().size}") { host.push(HiddenAppsScene(host)) },
            toggle("Auto-sort Android games", { s.autoSortAndroidGames }) { s.autoSortAndroidGames = it },
            action("Sort installed games now") {
                host.io({ s.lastPackageReconcile = 0; MainUI.apps.reconcileNewInstalls(MainUI.emulators); MainUI.library.androidGames().size }) {
                    host.toast("$it Android games")
                }
            },
            multi("Game metadata", listOf("both" to "gamelist + DAT", "gamelist" to "gamelist.xml", "logiqx" to "Logiqx DAT"),
                { s.metadataSource }) { s.metadataSource = it; MainUI.games.invalidate() },
            toggle("Show unknown rom folders", { s.showUnknownFolders }) { s.showUnknownFolders = it; MainUI.games.invalidate() },
            multi("MainUI data folder", listOf("auto" to "Auto", "sd" to "SD card", "internal" to "Internal"), { s.dataLocation }) {
                s.dataLocation = it; MainUI.reloadStorage()
            },
            action("Rom folders", if (roots.isEmpty()) "none found" else "${roots.size}") { host.push(RomFoldersScene(host)) },
            action("Create dArkOS folders") {
                // Pick the storage first: internal storage or the SD card
                host.push(io.github.marsinsanity.mainui.ui.PopMenuScene(host, "Create rom folders on",
                    MainUI.storage.volumes().map { v ->
                        val label = if (v.removable) "SD card (${v.dir.name})" else "Internal storage"
                        UiItem(label, onSelect = {
                            host.push(DialogScene(host, "Create folders",
                                "Create roms/<system> folders for every dArkOS system on $label?\nExisting folders are kept and used, nothing is overwritten.") {
                                host.io({ createFolders(v) }) { host.toast("Created $it folders on $label", 2500); build() }
                            })
                        })
                    }))
            },
            action("Rescan library") { MainUI.games.invalidate(); MainUI.apps.invalidate(); host.toast("Library will be rescanned") },
        ))
    }

    /**
     * Creates the dArkOS rom folders on [vol]. Purely additive: an existing roms
     * folder (any letter case) is reused, and a system is skipped when any of its
     * folder names (e.g. "genesis" for "megadrive") already exists. Nothing is
     * ever overwritten, moved or deleted.
     */
    private fun createFolders(vol: io.github.marsinsanity.mainui.core.Storage.Volume): Int {
        val roms = vol.dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("roms", true) } ?: java.io.File(vol.dir, "roms")
        roms.mkdirs()
        val existing = (roms.listFiles()?.filter { it.isDirectory }?.map { it.name.lowercase() } ?: emptyList()).toSet()
        var n = 0
        for (d in MainUI.systems.defs) {
            if (d.folders.isEmpty()) continue
            val already = d.folders.any { f ->
                val parts = f.split('/')
                if (parts.size == 1) parts[0].lowercase() in existing
                else java.io.File(roms, f).isDirectory // nested (pico-8/carts)
            }
            if (already) continue
            if (java.io.File(roms, d.folders.first()).mkdirs()) n++
        }
        if ("bios" !in existing) java.io.File(roms, "bios").mkdirs()
        if (vol.dir.listFiles()?.none { it.isDirectory && it.name.equals("Themes", true) } != false) java.io.File(vol.dir, "Themes").mkdirs()
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
        items += UiItem("RetroArch cores", sub = when {
            ra == null -> "RetroArch not installed"
            cores == null -> "${cores?.size ?: "?"} (root needed)"
            else -> "${cores.size} installed"
        }, onSelect = { if (ra != null) host.push(CoresScene(host)) else CoreInstall.offer(host, "mgba") })
        if (ra != null) items += action("Configure RetroArch for MainUI") {
            host.push(DialogScene(host, "Configure RetroArch",
                "Sets RetroArch up for MainUI's in-game menu: pause while the menu is open, save games every 10 s, auto save state on exit, and the command port (used by builds that have it). RetroArch will be closed.") {
                host.io({ em.configureRetroArch() }) { host.toast(it, 3000) }
            })
        }
        for (d in em.standaloneDefs()) {
            val pkg = em.installedPackage(d.id)
            val states = when (d.states) {
                io.github.marsinsanity.mainui.emu.StateSupport.API -> "slots"
                io.github.marsinsanity.mainui.emu.StateSupport.HOTKEY -> "hotkeys " + listOfNotNull(d.save, d.load)
                    .joinToString("/") { io.github.marsinsanity.mainui.emu.StandaloneController.keyLabel(it) }
                io.github.marsinsanity.mainui.emu.StateSupport.MENU -> "own menu"
                io.github.marsinsanity.mainui.emu.StateSupport.NONE -> "no states"
            }
            items += UiItem(d.name, sub = (if (pkg == null) "not installed  " else "") + states, disabled = pkg == null)
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

/**
 * RetroArch cores: installed ones, then every core MainUI recommends for a
 * system (A downloads it). "Get cores for my games" fetches the default core
 * of every system that has roms but no working RetroArch core yet.
 */
class CoresScene(host: SceneHost) : ListScene(host, "RetroArch cores") {
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "RESCAN")

    private var needed: List<String> = emptyList()
    private var local: Map<String, java.io.File> = emptyMap()

    override fun onEnter() {
        build()
        rescan()
    }

    override fun onResume() = rescan()

    private fun rescan() {
        host.io({ MainUI.emulators.refreshCores(); coresForMyGames() to MainUI.emulators.localCores() }) { (n, l) ->
            needed = n; local = l; build()
        }
    }

    private fun build() {
        val em = MainUI.emulators
        val installed = (em.raCores ?: emptySet()).sorted()
        val missing = em.recommendedCores().filter { em.coreInstalled(it) == false }.distinct()
            .sortedBy { em.coreDisplayName(it).lowercase() }
        val items = mutableListOf<UiItem>()
        val needed = needed
        items += UiItem("Get cores for my games", sub = if (needed.isEmpty()) "nothing missing" else "${needed.size} to download",
            disabled = needed.isEmpty(), onSelect = { CoreInstall.install(host, needed) { rescan() } })
        em.raCoreDir?.let { items += UiItem("Folder", sub = it.removePrefix("/data/data/"), disabled = true) }
        // Custom builds dropped into MainUI/cores.
        for (c in local.keys.sorted()) items += UiItem(em.coreDisplayName(c), sub = if (c in installed) "file: reinstall" else "file: install",
            data = c, onSelect = { CoreInstall.install(host, listOf(c)) { rescan() } })
        for (c in installed) if (c !in local) items += UiItem(em.coreDisplayName(c), sub = "installed", data = c)
        for (c in missing) if (c !in local) items += UiItem(em.coreDisplayName(c), sub = "A: get", data = c,
            onSelect = { CoreInstall.offer(host, c) { rescan() } })
        list.set(items)
        title = "RetroArch cores (${installed.size})"
    }

    /** Default RetroArch core of every system that has games but nothing installed to run them. */
    private fun coresForMyGames(): List<String> {
        val em = MainUI.emulators
        if (em.raPackage == null) return emptyList()
        return MainUI.games.systems(false).filter { it.gameCount > 0 || it.dirs.any { d -> (d.listFiles()?.size ?: 0) > 0 } }
            .mapNotNull { s ->
                val opts = em.optionsFor(s.def)
                if (opts.any { it.installed == true }) null
                else opts.firstOrNull { it.isRetroArch && it.installed == false }?.core
            }.distinct()
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.Y && !repeat) { host.toast("Scanning..."); rescan(); return true }
        return super.onKey(b, repeat)
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
