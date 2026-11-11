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
            action("Device", "brightness, volume, Wi-Fi") { host.push(DeviceScene(host)) },
            action("Theme", MainUI.themes.current.name) { host.push(ThemeSwitcherScene(host)) },
            action("User interface") { host.push(UiSettingsScene(host)) },
            action("Controls & in-game menu") { host.push(ControlSettingsScene(host)) },
            action("Library & storage") { host.push(LibrarySettingsScene(host)) },
            action("Emulators & cores", io.github.marsinsanity.mainui.store.EmulatorUpdates.known().size.let {
                if (it > 0) "$it update${if (it > 1) "s" else ""}" else null
            }) { host.push(EmulatorsScene(host)) },
            action("Scraper", "box art & game info") { host.push(ScraperScene(host, null)) },
            action("Process manager") { host.push(ProcessManagerScene(host)) },
            action("Permissions & setup") { host.push(SetupScene(host)) },
            action("Android settings") {
                host.hostContext.startActivity(Intent(AndroidSettings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            action("Updates", io.github.marsinsanity.mainui.store.Updater.knownAvailable().let {
                if (it.isNotEmpty()) "$it available" else "v${BuildConfig.VERSION_NAME}"
            }) { host.push(UpdateScene(host)) },
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
            toggle("Show Emulators", { s.showEmulators }) { s.showEmulators = it },
            toggle("Show Expert (RetroArch)", { s.showExpert }) { s.showExpert = it },
            toggle("Show Search tile", { s.showSearchTile }) { s.showSearchTile = it },
            toggle("Show Android system", { s.showAndroidSystem }) { s.showAndroidSystem = it },
            toggle("Show Ports system", { s.showPortsSystem }) { s.showPortsSystem = it; MainUI.games.invalidate() },
            toggle("Show empty systems", { s.showEmptySystems }) { s.showEmptySystems = it },
            toggle("Show game art in lists", { s.showGameArt }) { s.showGameArt = it },
            multi("Background fit", listOf("cover" to "Crop", "stretch" to "Stretch", "fit" to "Fit"), { s.backgroundFit }) { s.backgroundFit = it },
            toggle("Smooth scaling", { s.smoothScaling }) { s.smoothScaling = it },
            toggle("Show clock", { s.showClock }) { s.showClock = it },
            multi("Clock format", listOf("24" to "24-hour", "12" to "12-hour", "system" to "Like Android"),
                { s.clockFormat }) { s.clockFormat = it },
            toggle("Menu sounds", { s.uiSounds }) { s.uiSounds = it; MainUI.themes.reloadSounds() },
            multi("Haptic feedback", listOf("off" to "Off", "light" to "Light", "medium" to "Medium", "strong" to "Strong"),
                { s.haptics }) {
                s.haptics = it
                io.github.marsinsanity.mainui.core.Haptics.press()
                if (it != "off" && !io.github.marsinsanity.mainui.core.Haptics.available())
                    host.toast("No vibration motor found on this device", 2500)
            },
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
            multi("Hold Back", listOf("processes" to "Process manager", "none" to "Nothing"),
                { s.backHold }) { s.backHold = it },
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
            toggle("Auto save & load (exit, Game Switcher)", { s.autoSaveOnExit }) { s.autoSaveOnExit = it },
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
                host.io({ s.lastPackageReconcile = 0; MainUI.apps.reconcileNewInstalls(MainUI.emulators, force = true); MainUI.apps.androidGames().size }) {
                    host.toast("$it Android games")
                }
            },
            multi("Game metadata", listOf("both" to "gamelist + DAT", "gamelist" to "gamelist.xml", "logiqx" to "Logiqx DAT"),
                { s.metadataSource }) { s.metadataSource = it; MainUI.games.invalidate() },
            toggle("Show unknown rom folders", { s.showUnknownFolders }) { s.showUnknownFolders = it; MainUI.games.invalidate() },
            toggle("Show hidden games", { s.showHiddenGames }) { s.showHiddenGames = it; MainUI.games.invalidate() },
            multi("MainUI data folder", listOf("auto" to "Auto", "sd" to "SD card", "internal" to "Internal"), { s.dataLocation }) {
                // The library (favorites, recents, play time...) comes along to a data folder that has
                // none yet; otherwise it would look emptied, and stay behind in the old folder.
                val choice = it
                host.io({
                    val old = MainUI.storage.configDir
                    s.dataLocation = choice
                    MainUI.storage.invalidate()
                    val new = MainUI.storage.configDir
                    if (new.canonicalPath != old.canonicalPath && new.list().isNullOrEmpty())
                        runCatching { old.copyRecursively(new, overwrite = false) }
                }) { MainUI.reloadStorage() }
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
    // Coming back from the cores list or an emulator page: counts and "get" marks may have changed.
    override fun onResume() = build()

    private fun build() {
        val em = MainUI.emulators
        val items = mutableListOf<UiItem>()
        val ra = em.raPackage
        val cores = em.raCores
        items += UiItem("Get emulators...", sub = "F-Droid, GitHub, retroarch.com", onSelect = { host.push(EmulatorStoreScene(host)) })
        val ups = io.github.marsinsanity.mainui.store.EmulatorUpdates.known().size
        items += UiItem("Emulator updates", sub = when {
            ups > 0 -> "$ups available"
            io.github.marsinsanity.mainui.store.EmulatorUpdates.lastCheck() == 0L -> "check"
            else -> "up to date"
        }, onSelect = { host.push(EmulatorUpdatesScene(host)) })
        items += UiItem("RetroArch cores", sub = when {
            ra == null -> "RetroArch not installed"
            cores == null -> if (io.github.marsinsanity.mainui.core.Root.knownAvailable()) "? (can't read RetroArch's folder)" else "? (root needed)"
            else -> "${cores.size} installed"
        }, onSelect = { if (ra != null) host.push(CoresScene(host)) else CoreInstall.offer(host, "mgba") })
        if (ra != null) items += action("Configure RetroArch for MainUI") {
            host.push(DialogScene(host, "Configure RetroArch",
                "Pause behind MainUI's menu, save every 10 s, save state on exit, keyboard hotkeys for Save / Load / Menu (F2 / F4 / F1). RetroArch will be closed.") {
                host.toast("Configuring RetroArch...", 8000)
                host.io({ em.configureRetroArch() }) { (ok, msg) ->
                    if (ok) host.toast(msg, 3000) else host.push(DialogScene(host, "Configure RetroArch", msg, "OK", null))
                }
            })
        }
        items += UiItem("Auto load waits", sub = "standalone emulators", onSelect = { host.push(AutoLoadScene(host)) })
        for (d in em.standaloneDefs()) {
            val pkg = em.installedPackage(d.id)
            val states = if (pkg != null && io.github.marsinsanity.mainui.emu.StateApi.version(pkg) > 0) "MainUI build: slots" else when (d.states) {
                io.github.marsinsanity.mainui.emu.StateSupport.API, io.github.marsinsanity.mainui.emu.StateSupport.DIRECT -> "slots"
                io.github.marsinsanity.mainui.emu.StateSupport.HOTKEY -> "hotkeys " + listOfNotNull(d.save, d.load)
                    .joinToString("/") { io.github.marsinsanity.mainui.emu.StandaloneController.keyLabel(it) }
                io.github.marsinsanity.mainui.emu.StateSupport.MENU -> "own menu"
                io.github.marsinsanity.mainui.emu.StateSupport.NONE -> "no states"
            }
            items += UiItem(d.name, sub = (if (pkg == null) "get  " else "") + states,
                onSelect = { host.push(EmulatorDetailScene(host, d.id)) })
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
 * How long MainUI waits before pressing each standalone emulator's load key when a game it saved
 * starts again (auto load, see SessionManager.autoLoad), counted from when the emulator's screen
 * stops changing. The same setting as the in-game menu's Save / load keys > Auto load wait.
 */
class AutoLoadScene(host: SceneHost) : ListScene(host, "Auto load waits") {
    override val showPreview = false

    override fun onEnter() = build()

    private fun build() {
        val delays = io.github.marsinsanity.mainui.emu.Hotkeys.DELAYS
        val items = mutableListOf<UiItem>()
        for (d in MainUI.emulators.standaloneDefs()) {
            val c = io.github.marsinsanity.mainui.emu.StandaloneController("", d)
            if (c.loadSpec == null || c.saveSpec == null) continue // no keys: nothing to load with
            val def = (c.defaultLoadDelayMs / 1000).toInt()
            val cur = (c.loadDelayMs / 1000).toInt()
            fun label(s: Int) = (if (s == 0) "Off" else "$s s") + if (s == def) " (default)" else ""
            items += UiItem(d.name, ItemType.MULTI, values = delays.map(::label),
                value = delays.indexOfFirst { it >= cur }.let { if (it < 0) delays.size - 1 else it }, wrap = false,
                onChange = { item ->
                    val s = delays[item.value]
                    io.github.marsinsanity.mainui.emu.Hotkeys.setLoadDelay(d.id, if (s == def) null else s)
                })
        }
        if (items.isEmpty()) items += UiItem("No standalone emulator with save / load keys", disabled = true)
        list.set(items, keepIndex = true)
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
        items += UiItem("Download all cores", sub = "everything on the libretro buildbot",
            onSelect = { CoreInstall.installAll(host) { rescan() } })
        em.raCoreDir?.let { items += UiItem("Folder", sub = it.removePrefix("/data/data/"), disabled = true) }
        // Custom builds dropped into MainUI/cores.
        for (c in local.keys.sorted()) items += UiItem(em.coreDisplayName(c), sub = if (c in installed) "file: reinstall" else "file: install",
            data = c, onSelect = { CoreInstall.install(host, listOf(c)) { rescan() } })
        for (c in installed) if (c !in local) items += UiItem(em.coreDisplayName(c), sub = "installed", data = c)
        for (c in missing) if (c !in local) items += UiItem(em.coreDisplayName(c), sub = "A: get", data = c,
            onSelect = { CoreInstall.offer(host, c) { rescan() } })
        list.set(items)
        title = "RetroArch cores (${if (em.raCores == null) "?" else installed.size})"
    }

    /** Default RetroArch core of every system that has games but nothing installed to run them. */
    private fun coresForMyGames(): List<String> {
        val em = MainUI.emulators
        if (em.raPackage == null) return emptyList()
        return MainUI.games.systems(MainUI.settings.showUnknownFolders).filter { it.gameCount > 0 || it.dirs.any { d -> (d.listFiles()?.size ?: 0) > 0 } }
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
