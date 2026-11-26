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
            action("Android settings") { openSystem(host, Intent(AndroidSettings.ACTION_SETTINGS)) },
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
            // (Nothing to count again: the Ports system is added on every look, see GameRepository.withMenuSystems.)
            toggle("Show Ports system", { s.showPortsSystem }) { s.showPortsSystem = it },
            toggle("Show empty systems", { s.showEmptySystems }) { s.showEmptySystems = it },
            toggle("Show game art in lists", { s.showGameArt }) { s.showGameArt = it },
            toggle("OnionOS icons for missing systems", { s.onionIcons }) { s.onionIcons = it; io.github.marsinsanity.mainui.theme.Theme.onionIcons = it },
            multi("Background fit", listOf("auto" to "Auto", "cover" to "Crop", "stretch" to "Stretch", "fit" to "Fit"), { s.backgroundFit }) { s.backgroundFit = it },
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
            action("Emulator controls...", "buttons, hotkeys, Wii") { host.push(EmulatorControlsScene(host)) },
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
            toggle("Pause game while asleep", { s.pauseWhenAsleep }) { s.pauseWhenAsleep = it },
            toggle("Freeze standalone emulators (root)", { s.freezeStandalone }) { s.freezeStandalone = it },
            toggle("Auto save & load (exit, Game Switcher)", { s.autoSaveOnExit }) { s.autoSaveOnExit = it },
            action("Overlay service", if (overlayOn) "enabled" else "disabled") { openAccessibility(host) },
        ))
    }

    companion object {
        val HOME_ACTIONS = listOf("menu" to "In-game menu", "gs" to "Game Switcher", "home" to "Go to MainUI", "none" to "Nothing")

        /**
         * Android 13+ greys out the service of an app installed from a file, not a store ("Restricted
         * setting") until App info > ⋮ > Allow restricted settings (that entry appears after one try),
         * and MainUI never said so: the in-game menu, hold-Home and hold-Back stayed off on the
         * user's own handhelds.
         */
        fun openAccessibility(host: SceneHost) {
            if (Build.VERSION.SDK_INT >= 33 && !OverlayService.isEnabled(host.hostContext)) {
                host.push(DialogScene(host, "In-game menu overlay",
                    "Turn on \"MainUI in-game menu\" in Accessibility.\n" +
                        "Greyed out, or \"Restricted setting\"? Then: Settings > Apps > MainUI, the three-dot menu (top right) > " +
                        "Allow restricted settings, and turn it on again (Setup has a shortcut to App info).",
                    "OPEN", "BACK") { openAccessibilityNow(host) })
                return
            }
            openAccessibilityNow(host)
        }

        private fun openAccessibilityNow(host: SceneHost) {
            if (openSystem(host, Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))) host.toast("Enable \"MainUI in-game menu\"", 3000)
        }

        /** App info, for Android 13+'s "Allow restricted settings" (in its ⋮ menu). */
        fun openAppInfo(host: SceneHost) {
            if (openSystem(host, Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${host.hostContext.packageName}"))))
                host.toast("Three-dot menu (top right) > Allow restricted settings", 4000)
        }
    }
}

class LibrarySettingsScene(host: SceneHost) : ListScene(host, "Library") {
    override val showPreview = false
    private val dataLocations = listOf("auto" to "Auto", "sd" to "SD card", "internal" to "Internal")

    /** The setting, and where "Auto" resolves to (it keeps a MainUI folder found on the SD card). */
    private fun dataLocationLabel(): String {
        val s = MainUI.settings
        val label = dataLocations.firstOrNull { it.first == s.dataLocation }?.second ?: "Auto"
        if (s.dataLocation != "auto") return label
        val sd = MainUI.storage.volumes().firstOrNull { it.removable }?.dir?.absolutePath
        val onSd = sd != null && MainUI.storage.dataRoot().absolutePath.startsWith(sd)
        return "$label (${if (onSd) "SD card" else "Internal"})"
    }

    override fun onEnter() = build()
    override fun onResume() = build()

    /**
     * Uses [choice] for MainUI's data. The whole MainUI folder (library, recipes, cores, fonts, state
     * pictures; not its cache) comes along to a folder that has no data yet; otherwise it would look
     * emptied and stay behind in the old one. Files already there are kept.
     */
    private data class MoveTarget(val problem: String?, val label: String = "", val hasData: Boolean = false, val theirTime: Long = 0L, val same: Boolean = false)

    private fun moveData(choice: String) {
        // The target first: refused, or already holding MainUI data (an older copy left there by an
        // earlier move: switching back must not silently bring back its old favorites, recents and
        // play time), which the user decides about.
        host.io({
            runCatching {
                val st = MainUI.storage
                val oldRoot = st.dataRoot()
                val newRoot = st.dataRootFor(choice, create = false)
                val sd = st.volumes().firstOrNull { it.removable }?.dir?.canonicalPath
                if (choice == "sd" && (sd == null || !newRoot.canonicalPath.startsWith(sd)))
                    return@runCatching MoveTarget("Can't write to the SD card: MainUI's data stays where it is")
                val same = newRoot.canonicalPath == oldRoot.canonicalPath
                val theirs = java.io.File(newRoot, "config").listFiles().orEmpty()
                // "Auto" named with where it resolves (the first volume holding a MainUI folder).
                val name = dataLocations.firstOrNull { it.first == choice }?.second ?: choice
                val label = if (choice != "auto") name
                    else "$name (${if (sd != null && newRoot.canonicalPath.startsWith(sd)) "SD card" else "Internal"})"
                MoveTarget(null, label, !same && theirs.isNotEmpty(), theirs.maxOfOrNull { it.lastModified() } ?: 0L, same)
            }.getOrElse { MoveTarget("Couldn't move MainUI's data: ${it.message}") }
        }) { (problem, label, hasData, theirTime, same) ->
            if (problem != null) { host.toast(problem, 3500); return@io }
            // The folder in use already ("Auto" while it resolves there): only the setting changes.
            if (same) { performMove(choice, replace = false); return@io }
            if (!hasData) {
                host.push(DialogScene(host, "MainUI data folder",
                    "Keep MainUI's data (favorites, recents, play time, recipes, cores) in: $label?\n" +
                        "It is copied there.") { performMove(choice, replace = false) })
                return@io
            }
            val date = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(theirTime))
            host.push(DialogScene(host, "MainUI data folder",
                "$label already has older MainUI data (from $date).\n" +
                    "Next, pick which to keep: the data in use (the older copy is kept as MainUI.old) or the data there.") {
                // A menu, not a two-button dialog: B (or a stray tap beside it) backs out of the
                // move instead of picking one of the two copies. Short labels: the menu is narrow.
                host.push(io.github.marsinsanity.mainui.ui.PopMenuScene(host, "Keep which data?", listOf(
                    UiItem("The data in use", onSelect = { performMove(choice, replace = true) }),
                    UiItem("The older data", onSelect = { performMove(choice, replace = false) }),
                )))
            })
        }
    }

    /**
     * Uses [choice] for MainUI's data. The whole MainUI folder (library, recipes, cores, fonts, state
     * pictures; not its cache) is copied there when it has no library yet, or with [replace] (the
     * target's older MainUI folder is first set aside whole as MainUI.old, MainUI.old2...: nothing
     * of it is deleted or merged). The setting changes only once the library is copied.
     */
    private fun performMove(choice: String, replace: Boolean) {
        val busy = io.github.marsinsanity.mainui.ui.BusyScene(host, "Moving MainUI's data...")
        host.push(busy)
        host.io({
            runCatching {
                val st = MainUI.storage
                val oldRoot = st.dataRoot()
                // Copied first, switched after: until then MainUI keeps reading and writing the old
                // folder (a library read from an empty new one would look emptied, and what was
                // written there meanwhile would keep the copy from coming along).
                val newRoot = st.dataRootFor(choice)
                // Checked again: the card can be gone, or read-only, since the question.
                val sd = st.volumes().firstOrNull { it.removable }?.dir?.canonicalPath
                if (choice == "sd" && (sd == null || !newRoot.canonicalPath.startsWith(sd)))
                    return@runCatching "Can't write to the SD card: MainUI's data stays where it is"
                var note: String? = null
                if (newRoot.canonicalPath != oldRoot.canonicalPath) {
                    var keptAs: java.io.File? = null
                    if (replace && !java.io.File(newRoot, "config").list().isNullOrEmpty()) {
                        val parent = newRoot.parentFile
                        val aside = generateSequence(1) { it + 1 }
                            .map { java.io.File(parent, if (it == 1) "MainUI.old" else "MainUI.old$it") }.first { !it.exists() }
                        if (!newRoot.renameTo(aside))
                            return@runCatching "Couldn't set the older data aside as ${aside.name}: MainUI's data stays where it is"
                        keptAs = aside
                        newRoot.mkdirs()
                    }
                    val target = java.io.File(newRoot, "config")
                    if (target.list().isNullOrEmpty()) {
                        // Files already there are kept (FileAlreadyExists is no failure).
                        val failed = ArrayList<String>()
                        fun copy(f: java.io.File) {
                            val done = runCatching {
                                f.copyRecursively(java.io.File(newRoot, f.name), overwrite = false) { file, e ->
                                    if (e !is FileAlreadyExistsException) failed += file.path
                                    OnErrorAction.SKIP
                                }
                            }
                            if (done.isFailure) failed += f.path
                        }
                        val items = oldRoot.listFiles().orEmpty().filter { !it.name.equals("cache", ignoreCase = true) }
                        // The library first: if it doesn't copy whole, nothing changes (the
                        // older folder back in place, the partial copy removed: all of it ours).
                        items.filter { it.name.equals("config", ignoreCase = true) }.forEach(::copy)
                        if (failed.isNotEmpty()) {
                            val undone = if (keptAs != null) newRoot.deleteRecursively() && keptAs.renameTo(newRoot)
                                else target.deleteRecursively()
                            return@runCatching "Couldn't copy MainUI's library (${failed.size} files): MainUI's data stays where it is" +
                                if (!undone && keptAs != null) ". The older data is in ${keptAs.name}" else ""
                        }
                        items.filter { !it.name.equals("config", ignoreCase = true) }.forEach(::copy)
                        if (failed.isNotEmpty()) note = "MainUI's data moved, but ${failed.size} files (pictures, logs...) couldn't be copied"
                    }
                    if (keptAs != null) note = listOfNotNull(note, "The older data is kept as ${keptAs.name}").joinToString(". ")
                }
                MainUI.settings.dataLocation = choice
                st.invalidate()
                note
            }.getOrElse { "Couldn't move MainUI's data: ${it.message}" }
        }) { problem ->
            host.pop(busy)
            MainUI.reloadStorage()
            build()
            problem?.let { host.toast(it, 4000) }
        }
    }

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
            // A choice and a confirmation, not a value stepped through with LEFT / RIGHT: every
            // change moves MainUI's data, so looking at the options mustn't.
            action("MainUI data folder", dataLocationLabel()) {
                host.push(io.github.marsinsanity.mainui.ui.PopMenuScene(host, "MainUI data folder", dataLocations.map { (id, label) ->
                    UiItem(label, sub = if (id == s.dataLocation) "in use" else null, onSelect = {
                        if (id != s.dataLocation) moveData(id)
                    })
                }))
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
                                host.io({ createFolders(v) }) { n ->
                                    // (Not writable: no All files access, or Android 10's SD card. "Created 0" looked done.)
                                    host.toast(if (n < 0) "Can't write to $label: no folders created" + (if (MainUI.storage.hasAllFilesAccess()) "" else " (All files access is off)")
                                        else "Created $n folders on $label", 3000)
                                    build()
                                }
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
    /** The number of folders made, or -1 when [vol] can't be written. */
    private fun createFolders(vol: io.github.marsinsanity.mainui.core.Storage.Volume): Int {
        val roms = vol.dir.listFiles()?.firstOrNull { it.isDirectory && it.name.equals("roms", true) } ?: java.io.File(vol.dir, "roms")
        roms.mkdirs()
        if (!roms.isDirectory || !roms.canWrite()) return -1
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
        // The user's MainUI/emulators.json replaces whole recipes: said, since MainUI's own later
        // fixes to them (launch flags, save keys, download sources) don't apply while it does.
        val overridden = em.overriddenRecipes
        if (overridden.isNotEmpty()) items += UiItem("${overridden.size} recipe${if (overridden.size > 1) "s" else ""} from MainUI/emulators.json",
            sub = overridden.joinToString(", ").take(40), onSelect = {
                host.push(DialogScene(host, "Your emulators.json",
                    "Replaces MainUI's own recipes for: ${overridden.joinToString(", ")}. MainUI's later fixes to them don't apply. " +
                        "Remove those entries (or the file) to use MainUI's.", "OK", null))
            })
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
                host.io({
                    // (It closes RetroArch once root is there: a game running in it has ended.)
                    em.configureRetroArch().also {
                        if (io.github.marsinsanity.mainui.core.Root.knownAvailable() && MainUI.sessions.active?.pkg == em.raPackage) MainUI.sessions.endActive()
                    }
                }) { (ok, msg) ->
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
            items += UiItem(d.name, sub = states, mark = pkg != null,
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
        // Without root RetroArch's cores can't be read (unknown, not "nothing missing"): where to get them.
        if (em.raPackage != null && em.raCores == null && !Root.knownAvailable())
            items += UiItem("Cores can't be read without root", sub = "open RetroArch > Online Updater > Core Downloader",
                onSelect = { CoreInstall.openRetroArch(host) })
        else items += UiItem("Get cores for my games", sub = if (needed.isEmpty()) "nothing missing" else "${needed.size} to download",
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
            UiItem("All files access", ItemType.ACTION, sub = if (storageOk) "OK" else "REQUIRED", onSelect = { requestStorage() }),
            UiItem("Set MainUI as home app", sub = if (launcherOk) "OK" else "recommended", onSelect = {
                openSystem(host, Intent(AndroidSettings.ACTION_HOME_SETTINGS))
            }),
            UiItem("In-game menu overlay", sub = if (overlayOk) "OK" else "recommended", onSelect = { ControlSettingsScene.openAccessibility(host) }),
        ) + listOfNotNull(
            // Android 13+: what to do when the service is greyed out (an APK installed from a file).
            if (Build.VERSION.SDK_INT >= 33 && !overlayOk) UiItem("Allow restricted settings", sub = "if it is greyed out",
                onSelect = { ControlSettingsScene.openAppInfo(host) }) else null,
        ) + listOf(
            UiItem("Root (Magisk)", sub = if (rootKnown) "OK" else "tap to request", onSelect = {
                host.io({ Root.available(recheck = true) }) { ok ->
                    host.toast(if (ok) "Root granted" else "Root not available"); build()
                    if (ok) MainUI.worker.execute { MainUI.emulators.refresh() }
                }
            }),
            UiItem("Brightness control", sub = if (writeOk || rootKnown) "OK" else "optional", onSelect = {
                openSystem(host, Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                    Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS))
            }),
            UiItem("Notifications (scraper progress)", sub = "optional", onSelect = {
                openSystem(host, Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName))
            }),
            UiItem("Continue", onSelect = { back() }),
        ))
    }

    private fun requestStorage() {
        val ctx = host.hostContext
        if (Build.VERSION.SDK_INT >= 30) {
            openSystem(host, Intent(AndroidSettings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")),
                Intent(AndroidSettings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            return
        }
        // Android 10: the storage permissions. Refused for good ("don't ask again"): only App info can grant them.
        val activity = ctx as? android.app.Activity ?: MainActivity.instance ?: return
        val asked = MainUI.settings.prefs.getBoolean("storage_asked", false)
        if (asked && io.github.marsinsanity.mainui.core.Storage.STORAGE_PERMISSIONS.none { activity.shouldShowRequestPermissionRationale(it) }) {
            openSystem(host, Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            host.toast("Permissions > Storage: Allow", 3000)
            return
        }
        MainUI.settings.prefs.edit().putBoolean("storage_asked", true).apply()
        activity.requestPermissions(io.github.marsinsanity.mainui.core.Storage.STORAGE_PERMISSIONS, 1)
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

/** Opens the first of [intents] the device has (firmwares lack some settings screens), else says so. */
internal fun openSystem(host: SceneHost, vararg intents: Intent): Boolean {
    for (i in intents) if (runCatching { host.hostContext.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return true
    host.toast("Not available on this device")
    return false
}
