package io.github.marsinsanity.mainui.ui.scenes

import android.graphics.Bitmap
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.GameSystem
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
        else Images.get("art:${g.key}", host) { MainUI.games.imageFor(sys ?: Launch.gameSystem(g.systemId), g) }
    }

    fun item(host: SceneHost, sys: GameSystem?, g: Game, sub: String? = null): UiItem {
        val fav = !g.isFolder && MainUI.library.isFavorite(g.key)
        return UiItem(
            g.name, sub = sub, data = g,
            icon = when {
                g.isFolder -> ({ MainUI.themes.current.skin("icon-folder") ?: Glyphs.get("folder", 40) })
                g.isAndroid -> ({ MainUI.apps.icon(g.pkg!!, 64) })
                fav -> ({ MainUI.themes.current.skin("ic-favorite-mark") })
                else -> null
            },
            preview = if (g.isFolder) null else preview(host, sys, g),
        )
    }

    /** OnionOS style game options (SELECT on a game). */
    fun options(host: SceneHost, g: Game, extra: List<UiItem> = emptyList(), onChanged: () -> Unit = {}) {
        val def = Launch.systemDef(g.systemId)
        val items = mutableListOf<UiItem>()
        val fav = MainUI.library.isFavorite(g.key)
        items += UiItem(if (fav) "Remove from favorites" else "Add to favorites", onSelect = {
            val now = MainUI.library.toggleFavorite(g)
            host.toast(if (now) "Added to favorites" else "Removed from favorites"); onChanged()
        })
        if (def != null && !g.isAndroid) {
            val cur = MainUI.emulators.choose(def, g)
            items += UiItem("Core: ${cur?.name ?: "none"}", onSelect = { host.push(CoreSelectScene(host, def, g)) })
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
        host.push(PopMenuScene(host, g.name, items))
    }
}

/** One folder of a system, like MainUI's game list. Sub folders open nested lists. */
class GameListScene(host: SceneHost, private val sys: GameSystem, private val dir: File?) :
    ListScene(host, if (dir == null) sys.name else dir.name) {

    private var loaded = false
    override var aLabel: String? = "OPEN"

    override fun onEnter() = reload()

    override fun onResume() {
        // favorites marks / names may have changed
        if (loaded) reload()
    }

    private fun reload() {
        host.io({ MainUI.games.list(sys, dir) }) { games ->
            list.set(games.map { GameItems.item(host, sys, it) }, keepIndex = loaded)
            loaded = true
        }
    }

    override fun draw(g: io.github.marsinsanity.mainui.ui.Gfx) {
        super.draw(g)
        if (!loaded) g.infoPanel("Loading...")
    }

    override fun activate() {
        val game = list.current?.data as? Game ?: return
        if (game.isFolder) host.push(GameListScene(host, sys, game.file)) else Launch.game(host, game)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val game = list.current?.data as? Game
        when (b) {
            Btn.Y -> {
                if (repeat || game == null || game.isFolder) return true
                val now = MainUI.library.toggleFavorite(game)
                host.toast(if (now) "Added to favorites" else "Removed from favorites")
                list.current?.icon = if (now) ({ MainUI.themes.current.skin("ic-favorite-mark") }) else null
                return true
            }
            Btn.SELECT -> { if (!repeat && game != null && !game.isFolder) GameItems.options(host, game) { reload() }; return true }
            Btn.MENU -> { if (!repeat) host.push(GameSwitcherScene(host)); return true }
            else -> return super.onKey(b, repeat)
        }
    }

    override val extraHints get() = listOf("Y" to "FAV")
}

/** The "Android" system: Android native games, added by hand or auto-sorted on install. */
class AndroidGamesScene(host: SceneHost) : ListScene(host, "Android") {
    override var aLabel: String? = "PLAY"

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ MainUI.apps.androidGames() }) { apps ->
            list.set(apps.map { a ->
                GameItems.item(host, null, Game(SystemDef.ANDROID.id, null, a.label, pkg = a.pkg))
            })
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
                    UiItem("Hide", onSelect = { MainUI.library.setHidden(g.pkg!!, true); MainUI.library.setAndroidGame(g.pkg, false); reload() }),
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

/** Shared list for Recents and Favorites (both are just library entries). */
abstract class EntryListScene(host: SceneHost, title: String) : ListScene(host, title) {
    abstract fun entries(): List<Library.Entry>
    open fun sub(e: Library.Entry): String? = Launch.systemDef(e.systemId)?.short

    override fun onEnter() = reload()
    override fun onResume() = reload()

    protected fun reload() {
        host.io({ entries() }) { es ->
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

class SearchResultsScene(host: SceneHost, private val query: String) : ListScene(host, "Search: $query") {
    private var done = false
    override var aLabel: String? = "PLAY"

    override fun onEnter() {
        host.io({
            val systems = MainUI.games.systems(MainUI.settings.showUnknownFolders)
            val roms = MainUI.games.search(query, systems)
            val q = query.lowercase()
            val android = MainUI.apps.androidGames().filter { it.label.lowercase().contains(q) }
                .map { Game(SystemDef.ANDROID.id, null, it.label, pkg = it.pkg) }
            roms + android
        }) { results ->
            done = true
            list.set(results.map { g ->
                GameItems.item(host, null, g, Launch.systemDef(g.systemId)?.short ?: g.systemId)
            }, false)
            title = "Search: $query (${results.size})"
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
        if (b == Btn.SELECT && !repeat && g != null) { GameItems.options(host, g); return true }
        if (b == Btn.Y && !repeat) { close(); SearchFlow.start(host); return true }
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
        val effective = MainUI.emulators.choose(def, game)?.ref
        val opts = MainUI.emulators.optionsFor(def)
        var defaultMarked = false
        list.set(opts.map { o ->
            val canGet = o.installed == false && o.isRetroArch
            val status = when (o.installed) { true -> null; false -> if (canGet) "get" else "not installed"; null -> "?" }
            val isDefault = !defaultMarked && o.installed != false && o.recommended
            if (isDefault) defaultMarked = true
            val label = o.name + if (isDefault) " (default)" else ""
            UiItem(label, ItemType.TOGGLE, value = if (o.ref == effective) 1 else 0, sub = status,
                disabled = o.installed == false && !canGet, data = o)
        }, false)
        list.active = list.items.indexOfFirst { it.value == 1 }.coerceAtLeast(0)
        if (current == null && game != null) title = "Game core (using system)"
    }

    override fun onResume() = build()

    override fun activate() {
        val o = list.current?.data as? io.github.marsinsanity.mainui.emu.EmuOption ?: return
        if (list.current?.disabled == true) return
        val select = {
            if (game != null) MainUI.library.setCoreForGame(game.key, o.ref) else MainUI.library.setCoreForSystem(def.id, o.ref)
            host.toast("${MainUI.emulators.nameOf(o.ref)} selected")
            close()
        }
        // A RetroArch core that isn't installed yet: offer to download it first.
        if (o.isRetroArch && o.installed == false) CoreInstall.offer(host, o.core) { select() } else select()
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.X && !repeat) {
            if (game != null) MainUI.library.setCoreForGame(game.key, null) else MainUI.library.setCoreForSystem(def.id, null)
            host.toast("Reset to default"); close(); return true
        }
        if (b == Btn.LEFT || b == Btn.RIGHT) return false
        return super.onKey(b, repeat)
    }
}
