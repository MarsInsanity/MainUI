package io.github.marsinsanity.mainui.ui.scenes

import android.graphics.Bitmap
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.AndroidPorts
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.GameListXml
import io.github.marsinsanity.mainui.data.GameSystem
import io.github.marsinsanity.mainui.data.RomFiles
import io.github.marsinsanity.mainui.data.Library
import io.github.marsinsanity.mainui.data.SystemDef
import io.github.marsinsanity.mainui.scraper.Scraper
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.Glyphs
import io.github.marsinsanity.mainui.ui.Images
import io.github.marsinsanity.mainui.ui.ItemType
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.PopMenuScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import java.io.File

/** Shared item factory so every game list looks and behaves the same. */
internal object GameItems {

    fun preview(host: SceneHost, sys: GameSystem?, g: Game): (() -> Bitmap?) = {
        if (g.isAndroid) Images.getBitmap("banner:${g.pkg}", host) { MainUI.apps.banner(g.pkg!!) ?: MainUI.apps.icon(g.pkg, 256) }
        // An Android port: its art in the ports folder (images/<Name>.png), else the app's banner / icon.
        else if (AndroidPorts.isPort(g.file)) Images.getBitmap("art:${g.key}", host) {
            MainUI.games.imageFor(sys ?: Launch.gameSystem(g.systemId), g)?.let { Images.decode(it, 360) }
                ?: AndroidPorts.packageOf(g.file!!)?.let { MainUI.apps.banner(it) ?: MainUI.apps.icon(it, 256) }
        }
        else Images.get("art:${g.key}", host) { MainUI.games.imageFor(sys ?: Launch.gameSystem(g.systemId), g) }
    }

    /** The list icon: folder, the app's icon (Android games, ports), else the favorite mark. */
    fun icon(g: Game): (() -> Bitmap?)? = when {
        g.isFolder -> ({ MainUI.themes.current.skin("icon-folder") ?: Glyphs.get("folder", 40) })
        g.isAndroid -> ({ MainUI.apps.icon(g.pkg!!, 64) })
        // (The package as read when the list was loaded: no file reading while drawing.)
        AndroidPorts.isPort(g.file) -> ({ AndroidPorts.knownPackage(g.file)?.let { MainUI.apps.icon(it, 64) } })
        MainUI.library.isFavorite(g.key) -> ({ MainUI.themes.current.skin("ic-favorite-mark") })
        else -> null
    }

    fun item(host: SceneHost, sys: GameSystem?, g: Game, sub: String? = null): UiItem {
        return UiItem(
            g.name, sub = sub ?: if (!g.isFolder && MainUI.games.isHidden(g)) "hidden" else null, data = g,
            icon = icon(g),
            preview = if (g.isFolder) null else preview(host, sys, g),
        )
    }

    /** OnionOS style game options (SELECT on a game). */
    fun options(host: SceneHost, g: Game, extra: List<UiItem> = emptyList(), onChanged: () -> Unit = {}) {
        // A port whose app isn't known yet (from Recents after a restart): its file read off the UI thread first.
        if (AndroidPorts.isPort(g.file) && AndroidPorts.knownPackage(g.file) == null) {
            host.io({ runCatching { AndroidPorts.packageOf(g.file!!) } }) { optionsNow(host, g, extra, onChanged) }
            return
        }
        optionsNow(host, g, extra, onChanged)
    }

    private fun optionsNow(host: SceneHost, g: Game, extra: List<UiItem>, onChanged: () -> Unit) {
        val def = Launch.systemDef(g.systemId)
        // A system menu entry (PICO-8's Splore, from Recents / Favorites): no core, file or rom of its own.
        val isMenu = g.file != null && def?.menuItemFor(g.file) != null
        // An Android app in Ports: no core or scraping; it leaves Ports instead of being deleted.
        val port = if (AndroidPorts.isPort(g.file)) AndroidPorts.knownPackage(g.file) else null
        val items = mutableListOf<UiItem>()
        val fav = MainUI.library.isFavorite(g.key)
        items += UiItem(if (fav) "Remove from favorites" else "Add to favorites", onSelect = {
            val now = MainUI.library.toggleFavorite(g)
            host.toast(if (now) "Added to favorites" else "Removed from favorites"); onChanged()
        })
        if (def != null && !g.isAndroid && !isMenu && !AndroidPorts.isPort(g.file)) {
            val cur = MainUI.emulators.choose(def, g)
            val own = MainUI.library.coreForGame(g.key) != null
            items += UiItem("Core: ${if (own) cur?.name ?: "none" else "Default (${cur?.shortName ?: "none"})"}",
                onSelect = { host.push(CoreSelectScene(host, def, g)) })
            items += UiItem("System core...", onSelect = { host.push(CoreSelectScene(host, def, null)) })
            items += UiItem("Scrape artwork", onSelect = {
                host.toast("Scraping ${g.name}...")
                host.io({ Scraper.scrapeOne(g) }) { msg -> Images.invalidate("art:${g.key}"); host.toast(msg, 2500); onChanged() }
            })
        }
        g.desc?.takeIf { it.isNotBlank() }?.let { d ->
            items += UiItem("Game info", onSelect = { host.push(DialogScene(host, g.name, d.take(400), "OK", null)) })
        }
        items += extra
        if (!g.isAndroid && !g.isFolder && g.file != null && !isMenu) {
            // Files that must stay but aren't games (BIOS sets like neogeo.zip, which are hidden by themselves).
            val hidden = MainUI.games.isHidden(g)
            items += UiItem(if (hidden) "Unhide ROM" else "Hide ROM", onSelect = {
                MainUI.library.setGameHidden(g.key, !hidden, io.github.marsinsanity.mainui.data.GameRepository.isBios(g.file))
                host.toast(if (hidden) "${g.name} is listed again" else "${g.name} hidden (Settings > Library > Show hidden games)", 2500)
                onChanged()
            })
        }
        // Last, away from the everyday entries.
        if (port != null) {
            items += UiItem("Move back to Apps", onSelect = { Ports.remove(host, g.name, port, toAndroid = false, done = onChanged) })
            items += UiItem("Move to Android games", onSelect = { Ports.remove(host, g.name, port, toAndroid = true, done = onChanged) })
            // (Once it is gone its port file goes too: see Ports.forgetUninstalled.)
            items += UiItem("Uninstall game", onSelect = { uninstallApp(host, port) })
        } else if (!g.isAndroid && !g.isFolder && g.file != null && !isMenu) items += UiItem("Delete ROM", onSelect = { confirmDelete(host, g, onChanged) })
        host.push(PopMenuScene(host, g.name, items))
    }

    /**
     * Deletes [g] from storage after asking: the rom and the files that belong to it (tracks,
     * discs, companions, see RomFiles), its box art, its gamelist.xml entry, and MainUI's own
     * records of it (recents, favorites, per-game core).
     */
    private fun confirmDelete(host: SceneHost, g: Game, onChanged: () -> Unit) {
        val file = g.file ?: return
        if (MainUI.sessions.active?.gameKey == g.key) { host.toast("${g.name} is running: exit it first", 2500); return }
        val sys = Launch.gameSystem(g.systemId)
        host.io({ RomFiles.related(file, sys?.def?.unzip.orEmpty()) }) { files ->
            // Also when the running game is one of its files (the image a zip was unpacked to, a disc of an .m3u).
            val running = MainUI.sessions.active?.path
            if (running != null && files.any { it.absolutePath == running || running.startsWith(it.absolutePath + "/") }) {
                host.toast("${g.name} is running: exit it first", 2500); return@io
            }
            val others = files.size - 1
            val what = when {
                file.isDirectory -> "the folder \"${file.name}\" and everything in it"
                others == 1 -> "\"${file.name}\" and 1 file that goes with it"
                others > 1 -> "\"${file.name}\" and $others files that go with it"
                else -> "\"${file.name}\""
            }
            host.push(DialogScene(host, "Delete ROM", "Delete $what from storage?\nThis can't be undone.", "DELETE", "CANCEL") {
                host.io({ delete(sys, g, files) }) { failed ->
                    if (failed.isEmpty()) host.toast("Deleted ${g.name}")
                    else host.toast("Could not delete ${failed.joinToString { it.name }.take(80)}", 3000)
                    onChanged()
                }
            })
        }
    }

    /** Deletes the files and what MainUI keeps about the game. Returns the files that couldn't be deleted. */
    private fun delete(sys: GameSystem?, g: Game, files: List<File>): List<File> {
        val file = g.file ?: return emptyList()
        // Found before the rom goes (the lookup uses its gamelist entry); only art named after this rom,
        // never one several games share.
        val art = runCatching { MainUI.games.imageFor(sys, g) }.getOrNull()
            ?.takeIf { it.isFile && it.nameWithoutExtension.startsWith(file.nameWithoutExtension, ignoreCase = true) }
        val failed = RomFiles.delete(files)
        if (file.exists()) return failed
        // Kept while another rom of the same name still uses it (Game.chd beside the deleted Game.cue).
        val stillUsed = file.parentFile?.listFiles()?.any { it.isFile && it.nameWithoutExtension.equals(file.nameWithoutExtension, true) &&
            sys?.def?.accepts(it) == true } == true
        if (!stillUsed) art?.delete()
        if (sys != null) runCatching {
            val gl = GameListXml(MainUI.games.systemRootFor(sys, file.parentFile ?: file)).load()
            if (gl.parseError == null && gl.remove(file)) gl.save()
        }
        // Every deleted file that is a game of its own (a zip and the image unpacked from it, an .m3u's
        // discs played directly): its recents, favorite, core choice and art go too.
        for (f in listOf(file) + files) if (f !in failed) {
            val k = "${g.systemId}:${f.absolutePath}"
            MainUI.library.removeRecent(k)
            if (MainUI.library.isFavorite(k)) MainUI.library.toggleFavorite(if (k == g.key) g else Game(g.systemId, f, g.name))
            MainUI.library.setCoreForGame(k, null)
            Images.invalidate("art:$k")
        }
        MainUI.games.invalidate()
        return failed
    }
}

/** One folder of a system, like MainUI's game list. Sub folders open nested lists. */
class GameListScene(host: SceneHost, private var sys: GameSystem, private val dir: File?) :
    ListScene(host, if (dir == null) sys.name else dir.name) {

    private var loaded = false
    override var aLabel: String? = "OPEN"

    override fun onEnter() = reload()

    override fun onResume() {
        // favorites marks / names may have changed
        if (loaded) reload()
    }

    private fun reload() {
        host.io({
            // Listed before its folder existed (Ports): the system as it is now, folder and all.
            if (sys.dirs.isEmpty()) MainUI.games.systems(MainUI.settings.showUnknownFolders).firstOrNull { it.id == sys.id }?.let { sys = it }
            // The system's menu entries first (PICO-8: Splore), at its top level only.
            val menu = if (dir == null) sys.menu.map { MainUI.games.menuGame(sys, it) } else emptyList()
            val games = MainUI.games.list(sys, dir).filter { g -> menu.none { it.file?.name.equals(g.file?.name, ignoreCase = true) } }
            // Android ports: their apps known before drawing (icons).
            games.forEach { g -> if (AndroidPorts.isPort(g.file)) AndroidPorts.packageOf(g.file!!) }
            if (sys.id == AndroidPorts.SYSTEM && sys.gameCount < 0) runCatching { MainUI.games.countGames(sys) }
            menu to games
        }) { (menu, games) ->
            // With the emulator app's icon (the PICO-8 app for Splore).
            val top = menu.map { g ->
                GameItems.item(host, sys, g).also { item ->
                    val pkg = sys.def.menuItemFor(g.file)?.emulator?.takeIf { it.startsWith("sa:") }
                        ?.let { MainUI.emulators.installedPackage(it.removePrefix("sa:")) }
                    if (pkg != null && item.icon == null) item.icon = { MainUI.apps.icon(pkg, 64) }
                }
            }
            if (sys.id == AndroidPorts.SYSTEM && dir == null) {
                // Ports: its apps; empty, how to add one (PortMaster's .sh ports don't run here yet).
                list.set(top + games.map { GameItems.item(host, sys, it) } + Ports.hints(sys, games.isEmpty()), keepIndex = loaded)
                if (!loaded || list.current?.disabled == true) {
                    (0 until list.size).firstOrNull { list.items[it].disabled.not() }?.let { list.active = it }
                }
            } else if (games.isEmpty() && dir == null) {
                // Say why: the folder has files, but not of a kind this system's emulators open.
                val exts = sys.def.extensions.sorted().joinToString(" ") { ".$it" }
                val found = sys.unplayableSummary
                // Zipped games of a system that can't open zips: one button unzips them all.
                val zips = UnzipFlow.archives(sys)
                list.set(top + listOfNotNull(
                    UiItem("No games found", disabled = true),
                    found?.let { UiItem("Found: $it", disabled = true) },
                    UiItem("${sys.def.short} games must be: $exts", disabled = true),
                    if (zips > 0) UiItem(UnzipFlow.label(sys),
                        onSelect = { UnzipFlow.start(host, sys) { reload() } }) else null,
                    // No folder yet (listed for its menu): where its games go.
                    if (sys.dirs.isEmpty()) menu.firstOrNull()?.file?.parentFile?.let { UiItem("Put them in ${it.path}", disabled = true) } else null,
                ), keepIndex = loaded)
                // Start on something that can be chosen (Unzip all, Splore), not on a message.
                if (!loaded || list.current?.disabled == true) {
                    (0 until list.size).firstOrNull { list.items[it].disabled.not() }?.let { list.active = it }
                }
            } else list.set(top + games.map { GameItems.item(host, sys, it) }, keepIndex = loaded)
            menuKeys = menu.map { it.key }.toSet()
            loaded = true
        }
    }

    /** Keys of the menu entries shown (no ROM options for them: there is no file). */
    private var menuKeys = emptySet<String>()

    override fun draw(g: io.github.marsinsanity.mainui.ui.Gfx) {
        super.draw(g)
        if (!loaded) g.infoPanel("Loading...")
    }

    override fun activate() {
        val cur = list.current ?: return
        if (cur.disabled) return
        val game = cur.data as? Game ?: run { cur.onSelect?.invoke(cur); return } // (Unzip all)
        if (game.isFolder) host.push(GameListScene(host, sys, game.file)) else Launch.game(host, game)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val game = list.current?.data as? Game
        when (b) {
            Btn.Y -> {
                if (repeat || game == null || game.isFolder) return true
                val now = MainUI.library.toggleFavorite(game)
                host.toast(if (now) "Added to favorites" else "Removed from favorites")
                list.current?.icon = GameItems.icon(game)
                return true
            }
            Btn.SELECT -> { if (!repeat && game != null && !game.isFolder && game.key !in menuKeys) GameItems.options(host, game) { reload() }; return true }
            Btn.MENU -> { if (!repeat) host.push(GameSwitcherScene(host)); return true }
            Btn.X -> { if (!repeat) SearchFlow.start(host, sys); return true } // search this system's games
            else -> return super.onKey(b, repeat)
        }
    }

    override val extraHints get() = listOf("X" to "SEARCH", "Y" to "FAV")
}

/** The "Android" system: Android native games, added by hand or auto-sorted on install. */
class AndroidGamesScene(host: SceneHost) : ListScene(host, "Android") {
    override var aLabel: String? = "PLAY"

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ MainUI.apps.androidGames() }) { apps ->
            // Empty: how to add one, like Ports.
            list.set(if (apps.isEmpty()) listOf(
                UiItem("No Android games yet", disabled = true),
                UiItem("Apps: SELECT on an app > Move to Android games", disabled = true),
            ) else apps.map { a ->
                GameItems.item(host, null, Game(SystemDef.ANDROID.id, null, a.label, pkg = a.pkg))
            }, keepIndex = true)
        }
    }

    override fun activate() {
        val g = list.current?.data as? Game ?: return
        Launch.game(host, g)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val g = list.current?.data as? Game
        when (b) {
            Btn.SELECT -> {
                if (!repeat && g != null) GameItems.options(host, g, listOf(
                    UiItem("Move back to Apps", onSelect = { MainUI.library.setAndroidGame(g.pkg!!, false); reload() }),
                    UiItem("Move to Ports", onSelect = { Ports.add(host, g.name, g.pkg!!) { reload() } }),
                    UiItem("Hide", onSelect = { MainUI.library.setHidden(g.pkg!!, true); MainUI.library.setAndroidGame(g.pkg, false); reload() }),
                    UiItem("Uninstall game", onSelect = { uninstallApp(host, g.pkg!!) }),
                )) { reload() }
                return true
            }
            Btn.Y -> {
                if (!repeat && g != null) host.toast(if (MainUI.library.toggleFavorite(g)) "Added to favorites" else "Removed from favorites")
                return true
            }
            else -> return super.onKey(b, repeat)
        }
    }
}

/** Reads a port's app (off the UI thread) so its list icon can be drawn. */
internal fun warmPort(g: Game) { if (AndroidPorts.isPort(g.file)) runCatching { AndroidPorts.packageOf(g.file!!) } }

/** Shared list for Recents and Favorites (both are just library entries). */
abstract class EntryListScene(host: SceneHost, title: String) : ListScene(host, title) {
    abstract fun entries(): List<Library.Entry>
    open fun sub(e: Library.Entry): String? = Launch.systemDef(e.systemId)?.short

    override fun onEnter() = reload()
    override fun onResume() = reload()

    protected fun reload() {
        host.io({ entries().also { es -> es.forEach { e -> warmPort(e.toGame()) } } }) { es ->
            list.set(es.map { e -> GameItems.item(host, null, e.toGame(), sub(e)).also { it.data = e } })
        }
    }

    override fun activate() {
        val e = list.current?.data as? Library.Entry ?: return
        Launch.game(host, e.toGame())
    }
}

class RecentsScene(host: SceneHost) : EntryListScene(host, "Recents") {
    override var aLabel: String? = "PLAY"
    override fun entries() = MainUI.library.recents()
    override val extraHints get() = listOf("X" to "REMOVE")

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val e = list.current?.data as? Library.Entry
        if (b == Btn.X && !repeat && e != null) {
            host.push(DialogScene(host, "Remove from history", "Are you sure you want to\nremove game from history?") {
                MainUI.library.removeRecent(e.key); reload()
            })
            return true
        }
        if (b == Btn.SELECT && !repeat && e != null) { GameItems.options(host, e.toGame()) { reload() }; return true }
        return super.onKey(b, repeat)
    }
}

class FavoritesScene(host: SceneHost) : EntryListScene(host, "Favorites") {
    override var aLabel: String? = "PLAY"
    override fun entries() = MainUI.library.favorites()
    override val extraHints get() = listOf("Y" to "REMOVE")

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val e = list.current?.data as? Library.Entry
        if (b == Btn.Y && !repeat && e != null) { MainUI.library.toggleFavorite(e.toGame()); reload(); return true }
        if (b == Btn.SELECT && !repeat && e != null) { GameItems.options(host, e.toGame()) { reload() }; return true }
        return super.onKey(b, repeat)
    }
}

class SearchResultsScene(host: SceneHost, private val query: String, private val system: GameSystem? = null) :
    ListScene(host, if (system == null) "Search: $query" else "${system.def.short}: $query") {
    private var done = false
    override var aLabel: String? = "PLAY"

    override fun onEnter() {
        host.io({
            if (system != null) MainUI.games.search(query, listOf(system)) // one system's games only
            else {
                val systems = MainUI.games.systems(MainUI.settings.showUnknownFolders)
                val roms = MainUI.games.search(query, systems)
                val q = query.lowercase()
                val android = MainUI.apps.androidGames().filter { it.label.lowercase().contains(q) }
                    .map { Game(SystemDef.ANDROID.id, null, it.label, pkg = it.pkg) }
                roms + android
            }.also { it.forEach(::warmPort) }
        }) { results ->
            done = true
            list.set(results.map { g ->
                GameItems.item(host, null, g, Launch.systemDef(g.systemId)?.short ?: g.systemId)
            }, false)
            title = (if (system == null) "Search: $query" else "${system.def.short}: $query") + " (${results.size})"
        }
    }

    override fun draw(g: io.github.marsinsanity.mainui.ui.Gfx) {
        super.draw(g)
        if (!done) g.infoPanel("Searching...")
    }

    override fun activate() {
        val g = list.current?.data as? Game ?: return
        Launch.game(host, g)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val g = list.current?.data as? Game
        if (b == Btn.SELECT && !repeat && g != null) {
            GameItems.options(host, g) {
                // Deleted: take it out of the results (nothing else here changes what's listed).
                if (g.file?.exists() == false) list.set(list.items.filter { it.data != g }, keepIndex = true)
            }
            return true
        }
        if (b == Btn.Y && !repeat) { close(); SearchFlow.start(host, system); return true }
        return super.onKey(b, repeat)
    }

    override val extraHints get() = listOf("Y" to "NEW")
}

/**
 * OnionOS core picker. [game] null = choose the system default, otherwise a
 * per-game override. Recommended (dArkOS) emulators first, then any other
 * installed RetroArch core that supports the system's extensions.
 */
class CoreSelectScene(host: SceneHost, private val def: SystemDef, private val game: Game?) :
    ListScene(host, if (game == null) "${def.short} core" else "Game core") {

    private companion object { val DEFAULT = Any() }

    /** Back to "Default": the game follows the system again / the system its recommended core. */
    private fun reset() {
        if (game != null) MainUI.library.setCoreForGame(game.key, null) else MainUI.library.setCoreForSystem(def.id, null)
        val now = MainUI.emulators.choose(def, game)
        host.toast("Default: ${now?.shortName ?: "none"}")
        close()
    }

    override var aLabel: String? = "SELECT"
    override val showPreview = false
    override val extraHints get() = listOf("X" to "RESET")

    override fun onEnter() {
        build()
        // Pick up cores added in RetroArch since the last scan.
        host.io({ MainUI.emulators.refreshCoresIfStale(3000) }) { build() }
    }

    private fun build() {
        val current = if (game != null) MainUI.library.coreForGame(game.key) else MainUI.library.coreForSystem(def.id)
        // "Default": a game follows its system's core, a system the recommended (dArkOS) one.
        val auto = if (game != null) MainUI.emulators.choose(def, game, gameOverride = false)
            else MainUI.emulators.choose(def, null, systemOverride = false)
        val opts = MainUI.emulators.optionsFor(def)
        val items = mutableListOf(UiItem("Default (${auto?.shortName ?: "none"})", ItemType.TOGGLE,
            value = if (current == null) 1 else 0, data = DEFAULT))
        items += opts.map { o ->
            // Missing RetroArch cores download in place; missing emulators open their store page.
            val canGet = o.installed == false
            val status = when (o.installed) { true -> null; false -> if (canGet) "get" else "not installed"; null -> "?" }
            UiItem(o.name, ItemType.TOGGLE, value = if (current != null && o.ref == current) 1 else 0, sub = status,
                disabled = o.installed == false && !canGet, data = o)
        }
        list.set(items, false)
        list.active = list.items.indexOfFirst { it.value == 1 }.coerceAtLeast(0)
    }

    override fun onResume() = build()

    override fun activate() {
        if (list.current?.data === DEFAULT) { reset(); return }
        val o = list.current?.data as? io.github.marsinsanity.mainui.emu.EmuOption ?: return
        if (list.current?.disabled == true) return
        val select = {
            if (game != null) MainUI.library.setCoreForGame(game.key, o.ref) else MainUI.library.setCoreForSystem(def.id, o.ref)
            host.toast("${MainUI.emulators.nameOf(o.ref)} selected")
            close()
        }
        // Not installed yet: download the core, or open the emulator's page in the App Store.
        when {
            o.installed != false -> select()
            o.isRetroArch && MainUI.emulators.raPackage != null -> CoreInstall.offer(host, o.core) { select() }
            o.isRetroArch -> host.push(EmulatorDetailScene(host, "retroarch"))
            else -> host.push(EmulatorDetailScene(host, o.ref.removePrefix("sa:")))
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.X && !repeat) { reset(); return true }
        if (b == Btn.LEFT || b == Btn.RIGHT) return false
        return super.onKey(b, repeat)
    }
}
