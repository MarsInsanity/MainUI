package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.GameSystem
import io.github.marsinsanity.mainui.data.SystemDef
import io.github.marsinsanity.mainui.ui.Align
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.ItemType
import io.github.marsinsanity.mainui.ui.PopMenuScene
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Anything shown as a big icon: main menu entries and systems. */
class Tile(
    val id: String,
    val label: String,
    /** Glyph used when the theme has no artwork. */
    val glyph: String,
    /** Main menu skin base name: ic-<skin>-n.png / ic-<skin>-f.png. */
    val skin: String? = null,
    /** Theme icons/<name>.png candidates. */
    val icons: List<String> = emptyList(),
    val short: String = label.take(3).uppercase(),
    var sub: String? = null,
    val system: GameSystem? = null,
    val options: (() -> Unit)? = null,
    val open: () -> Unit,
)

fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

object HomeScenes {
    fun root(host: SceneHost): Scene =
        if (MainUI.settings.homeLayout == "row" || MainUI.settings.homeLayout == "carousel") RowScene(host, null, home = true)
        else MainMenuScene(host)

    /** OnionOS main menu entries (Recents and Expert can be hidden in settings). */
    fun menuTiles(host: SceneHost): List<Tile> {
        val s = MainUI.settings
        val out = mutableListOf<Tile>()
        if (s.showRecents) out += Tile("recents", "Recents", "recent", "recent") { host.push(RecentsScene(host)) }
        if (s.showFavorites) out += Tile("favorites", "Favorites", "favorite", "favorite") { host.push(FavoritesScene(host)) }
        out += Tile("games", "Games", "game", "game") { openSystems(host) }
        if (s.showExpert) out += Tile("expert", "Expert", "expert", "retroarch") { openRetroArch(host) }
        out += Tile("apps", "Apps", "app", "app") { host.push(AppsScene(host)) }
        out += Tile("settings", "Settings", "setting", "setting") { host.push(SettingsScene(host)) }
        return out
    }

    fun openSystems(host: SceneHost) {
        val l = MainUI.settings.systemsLayout
        if (l == "row" || l == "carousel") host.push(RowScene(host, "Games", home = false))
        else host.push(SystemsGridScene(host))
    }

    fun openRetroArch(host: SceneHost) {
        val pkg = MainUI.emulators.raPackage
        if (pkg == null) { host.toast("RetroArch is not installed"); return }
        val err = MainUI.sessions.launchApp(pkg, "RetroArch", asGame = false)
        if (err != null) host.toast(err)
    }

    /** Tiles for the systems grid / row: Search, every system with games, Android. */
    fun systemTiles(host: SceneHost, systems: List<GameSystem>, includeSearch: Boolean): List<Tile> {
        val s = MainUI.settings
        val out = mutableListOf<Tile>()
        if (includeSearch && s.showSearchTile) out += Tile("search", "Search", "search", icons = listOf("search"), short = "?") {
            SearchFlow.start(host)
        }
        for (sys in systems) {
            if (!s.showEmptySystems && sys.gameCount == 0) continue
            out += Tile(sys.id, sys.name, "game", icons = sys.def.icons, short = sys.def.short,
                sub = if (sys.gameCount >= 0) plural(sys.gameCount, "game") else null, system = sys,
                open = { host.push(GameListScene(host, sys, null)) },
                options = { systemOptions(host, sys) })
        }
        if (s.showAndroidSystem) {
            val n = MainUI.library.androidGames().size
            out += Tile("android", "Android", "android", icons = SystemDef.ANDROID.icons, short = "APK",
                sub = plural(n, "game"), open = { host.push(AndroidGamesScene(host)) })
        }
        return out
    }

    fun systemOptions(host: SceneHost, sys: GameSystem) {
        val cur = MainUI.emulators.choose(sys.def, null)
        host.push(PopMenuScene(host, sys.name, listOf(
            UiItem("Core: ${cur?.name ?: "none"}", onSelect = { host.push(CoreSelectScene(host, sys.def, null)) }),
            UiItem("Scrape artwork", onSelect = { host.push(ScraperScene(host, listOf(sys.id))) }),
            UiItem("Rescan folder", onSelect = {
                MainUI.games.invalidate()
                host.io({ MainUI.games.countGames(sys) }) { host.toast("${sys.name}: ${plural(it, "game")}") }
            }),
        )))
    }

    /** Loads systems + game counts in the background. */
    fun loadSystems(host: SceneHost, refresh: Boolean = false, done: (List<GameSystem>) -> Unit) {
        host.io({
            val list = MainUI.games.systems(MainUI.settings.showUnknownFolders, refresh)
            list.filter { it.gameCount < 0 || refresh }.forEach { MainUI.games.countGames(it) }
            list
        }, done)
    }

    /** Theme artwork for a tile, or null (caller draws the built-in replacement). */
    fun tileIcon(g: Gfx, t: Tile, selected: Boolean): Bitmap? = g.theme.icon(t.icons, selected)

    /** Size of one cell of OnionOS' games grid (bg-game-item-f/n). */
    const val CELL_W = 155f
    const val CELL_H = 170f

    /**
     * One cell of the games grid: bg-game-item-f / -n, the system icon and its
     * grid3x4 label. Shared by the grid and the row view.
     */
    fun drawCell(g: Gfx, t: Tile, x: Float, y: Float, sel: Boolean) {
        val cellW = CELL_W; val cellH = CELL_H
        val gs = g.theme.grid
        val bg = g.skin(if (sel) "bg-game-item-f" else "bg-game-item-n")
        if (bg != null) g.bmp(bg, x, y, cellW, cellH)
        else if (sel) g.fillRect(x + 4, y + 4, cellW - 8, cellH - 8, Color.argb(70, 255, 255, 255), 14f)
        val iconBoxH = if (g.theme.hideIconLabels) cellH - 20 else cellH - 46
        // System icon, or for main menu entries the theme's ic-*-n/f tile scaled down
        val icon = tileIcon(g, t, sel) ?: t.skin?.let { g.skin("ic-$it-${if (sel) "f" else "n"}") }
        if (icon != null) g.bmpFit(icon, x + 10, y + 8, cellW - 20, iconBoxH)
        else if (t.glyph == "game") g.systemTile(t.short, x + 22, y + 18, cellW - 44, iconBoxH - 20, sel)
        else {
            g.systemTile("", x + 22, y + 18, cellW - 44, iconBoxH - 20, sel)
            g.glyph(t.glyph, x + cellW / 2, y + 18 + (iconBoxH - 20) / 2, 30f, Color.WHITE)
        }
        if (!g.theme.hideIconLabels) {
            val style = io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, gs.size3x4, gs.color)
            g.text(t.label, style, x + cellW / 2, y + cellH - 22f, Align.CENTER, maxW = cellW - 8f,
                color = if (sel) gs.selectedColor else gs.color, marquee = sel)
        }
    }
}

/**
 * OnionOS main menu: 4 tiles per page (ic-*-n / ic-*-f), labels in the grid1x4
 * font, page dots, logo in the header.
 */
class MainMenuScene(host: SceneHost) : Scene(host) {
    private var tiles = HomeScenes.menuTiles(host)
    private var index = MainUI.settings.mainMenuIndex.coerceIn(0, tiles.size - 1)

    /**
     * The tiles sit in one continuous row and a window of [VISIBLE] slides along it
     * to keep the selection in view (instead of OnionOS's pages of four, which left
     * a mostly empty last page). [first] is the leftmost visible tile; [scroll]
     * follows it smoothly, in tiles.
     */
    private var first = 0
    private var scroll = Float.NaN
    private var lastFrame = 0L

    private fun follow() {
        if (index < first) first = index
        if (index >= first + VISIBLE) first = index - VISIBLE + 1
        first = first.coerceIn(0, max(0, tiles.size - VISIBLE))
    }

    init { follow(); checkForUpdate() }

    /** Automatic update checks (MainUI every few hours, emulators daily); say so when there is something new. */
    private fun checkForUpdate() {
        if (io.github.marsinsanity.mainui.store.EmulatorUpdates.isDue()) {
            host.io({ runCatching { io.github.marsinsanity.mainui.store.EmulatorUpdates.check() }.getOrNull() }) { ups ->
                if (!ups.isNullOrEmpty()) host.toast(
                    if (ups.size == 1) "${ups[0].name} ${ups[0].latest} is available: Settings > Emulators & cores"
                    else "${ups.size} emulator updates: Settings > Emulators & cores", 4500)
            }
        }
        if (!io.github.marsinsanity.mainui.store.Updater.isDue()) return
        host.io({ runCatching { io.github.marsinsanity.mainui.store.Updater.check() }.getOrNull() }) { u ->
            if (u != null && u.versionCode > io.github.marsinsanity.mainui.store.Updater.installed)
                host.toast("MainUI ${u.versionName} is available: Settings > Updates", 4000)
        }
    }

    override fun onResume() {
        checkForUpdate()
        tiles = HomeScenes.menuTiles(host)
        index = index.coerceIn(0, tiles.size - 1)
        follow()
    }

    override fun draw(g: Gfx) {
        g.background()
        g.header(null, logo = true)
        val tileW = TILE_W; val tileH = 278f
        val y = 90f + g.cy0
        // Fewer tiles than the window: center them.
        val x0 = g.cx0 + 8f + if (tiles.size < VISIBLE) (VISIBLE - tiles.size) * tileW / 2 else 0f

        // Ease towards the target window (about 120 ms), then stop redrawing.
        if (scroll.isNaN()) scroll = first.toFloat()
        val dt = if (lastFrame == 0L) 16L else (g.now - lastFrame).coerceIn(1L, 50L)
        lastFrame = g.now
        val target = first.toFloat()
        scroll += (target - scroll) * (1f - Math.pow(0.001, dt / 120.0).toFloat())
        if (kotlin.math.abs(target - scroll) < 0.01f) { scroll = target; lastFrame = 0L } else g.redrawAfter(16)

        val from = kotlin.math.floor(scroll).toInt().coerceAtLeast(0)
        val to = min(tiles.size - 1, kotlin.math.ceil(scroll).toInt() + VISIBLE - 1)
        for (idx in from..to) {
            val t = tiles[idx]
            val sel = idx == index
            val x = x0 + (idx - scroll) * tileW
            if (x + tileW < 0f || x > g.W) continue
            val img = t.skin?.let { g.skin("ic-$it-${if (sel) "f" else "n"}") }
            if (img != null) g.bmp(img, x, y, tileW, tileH) else g.menuTile(t.glyph, x, y, tileW, tileH, sel)
            if (!g.theme.hideIconLabels) {
                val gs = g.theme.grid
                g.text(t.label, io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, gs.size1x4, gs.color),
                    x + tileW / 2, y + 195f, Align.CENTER, maxW = tileW - 8f,
                    color = if (sel) gs.selectedColor else gs.color)
            }
        }
        // One dot per tile, so it's clear more tiles are to the side.
        if (tiles.size > VISIBLE) g.dots(tiles.size, index, 396f + g.cy0)
        g.footer("SELECT", null)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        when (b) {
            Btn.LEFT -> if (index > 0) index-- else if (!repeat) index = tiles.size - 1 else return false
            Btn.RIGHT -> if (index < tiles.size - 1) index++ else if (!repeat) index = 0 else return false
            Btn.L1 -> index = max(0, index - VISIBLE)
            Btn.R1 -> index = min(tiles.size - 1, index + VISIBLE)
            Btn.A -> if (!repeat) tiles[index].open()
            Btn.SELECT, Btn.MENU -> if (!repeat) host.push(GameSwitcherScene(host))
            Btn.Y -> if (!repeat) SearchFlow.start(host)
            else -> return false
        }
        follow()
        MainUI.settings.mainMenuIndex = index
        return true
    }

    override fun onSwipe(direction: Btn): Boolean = when (direction) {
        Btn.RIGHT -> onKey(Btn.R1, false)
        Btn.LEFT -> onKey(Btn.L1, false)
        else -> false
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val w = host.logicalWidth
        val x0 = (w - 640f) / 2f + 8f + if (tiles.size < VISIBLE) (VISIBLE - tiles.size) * TILE_W / 2 else 0f
        val col = kotlin.math.floor((x - x0) / TILE_W).toInt()
        if (col !in 0 until VISIBLE) return false
        val idx = first + col
        if (idx !in tiles.indices) return false
        if (idx == index) tiles[idx].open() else { index = idx; follow(); MainUI.settings.mainMenuIndex = index }
        return true
    }

    companion object {
        const val VISIBLE = 4
        const val TILE_W = 156f
    }
}

/**
 * OnionOS "Games" screen: systems in a 4x2 grid with the Search tile first,
 * bg-game-item-f behind the focused icon and grid3x4 labels.
 */
class SystemsGridScene(host: SceneHost) : Scene(host) {
    private var tiles: List<Tile> = emptyList()
    private var index = MainUI.settings.systemsIndex
    private var loading = true
    private val cols = 4
    private val rows = 2

    override fun onEnter() = reload(false)
    override fun onResume() = reload(false)

    private fun reload(refresh: Boolean) {
        HomeScenes.loadSystems(host, refresh) { list ->
            tiles = HomeScenes.systemTiles(host, list, includeSearch = true)
            index = index.coerceIn(0, max(0, tiles.size - 1))
            loading = false
        }
    }

    override fun draw(g: Gfx) {
        g.background()
        g.header("Games")
        if (tiles.isEmpty()) {
            g.text(if (loading) "Loading..." else "No roms found. Put games in <storage>/roms/<system>/",
                g.theme.list, g.W / 2, g.H / 2, Align.CENTER, maxW = g.W - 40f)
            g.footer(null, "BACK"); return
        }
        val perPage = cols * rows
        val page = index / perPage
        val pages = (tiles.size + perPage - 1) / perPage
        val cellW = HomeScenes.CELL_W; val cellH = HomeScenes.CELL_H
        val x0 = g.cx0 + (640f - cols * cellW) / 2
        val areaTop = Gfx.HEADER; val areaH = g.H - Gfx.HEADER - Gfx.FOOTER - 30f
        val y0 = areaTop + (areaH - rows * cellH) / 2
        for (i in 0 until perPage) {
            val idx = page * perPage + i
            if (idx >= tiles.size) break
            HomeScenes.drawCell(g, tiles[idx], x0 + (i % cols) * cellW, y0 + (i / cols) * cellH, idx == index)
        }
        g.dots(pages, page, g.H - Gfx.FOOTER - 18f)
        g.footer("SELECT", "BACK")
        g.footerStatus(index + 1, tiles.size)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (tiles.isEmpty()) { if (b == Btn.B) { close(); return true }; return false }
        val perPage = cols * rows
        when (b) {
            Btn.LEFT -> if (index > 0) index-- else if (!repeat) index = tiles.size - 1 else return false
            Btn.RIGHT -> if (index < tiles.size - 1) index++ else if (!repeat) index = 0 else return false
            Btn.UP -> if (index - cols >= 0) index -= cols else return false
            Btn.DOWN -> if (index + cols < tiles.size) index += cols else if ((index / cols + 1) * cols < tiles.size) index = tiles.size - 1 else return false
            Btn.L1 -> index = max(0, (index / perPage - 1) * perPage)
            Btn.R1 -> index = min(tiles.size - 1, (index / perPage + 1) * perPage)
            Btn.A -> if (!repeat) tiles[index].open()
            Btn.B -> if (!repeat) close()
            Btn.SELECT -> if (!repeat) tiles[index].options?.invoke()
            Btn.Y -> if (!repeat) SearchFlow.start(host)
            Btn.START -> if (!repeat) { host.toast("Rescanning..."); reload(true) }
            Btn.MENU -> if (!repeat) host.push(GameSwitcherScene(host))
            else -> return false
        }
        MainUI.settings.systemsIndex = index
        return true
    }

    override fun onSwipe(direction: Btn): Boolean = when (direction) {
        Btn.RIGHT, Btn.DOWN -> onKey(Btn.R1, false)
        Btn.LEFT, Btn.UP -> onKey(Btn.L1, false)
        else -> false
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (tiles.isEmpty()) return false
        val cellW = 155f; val cellH = 170f
        val x0 = (host.logicalWidth - 640f) / 2 + (640f - cols * cellW) / 2
        val areaH = host.logicalHeight - Gfx.HEADER - Gfx.FOOTER - 30f
        val y0 = Gfx.HEADER + (areaH - rows * cellH) / 2
        val c = floor((x - x0) / cellW).toInt(); val r = floor((y - y0) / cellH).toInt()
        if (c !in 0 until cols || r !in 0 until rows) return false
        val idx = (index / (cols * rows)) * cols * rows + r * cols + c
        if (idx >= tiles.size) return false
        if (idx == index) tiles[idx].open() else index = idx
        return true
    }
}

/**
 * Row view: the games grid's own cells (bg-game-item-f/n, icon, label) in a
 * single row that scrolls sideways. [home] = top level screen (main menu
 * entries + systems), otherwise systems only. Replaces the old carousel.
 */
class RowScene(host: SceneHost, private val title: String?, private val home: Boolean) : Scene(host) {
    private var tiles: List<Tile> = emptyList()
    private var index = if (home) MainUI.settings.mainMenuIndex else MainUI.settings.systemsIndex
    private var first = 0
    private var loading = true

    override fun onEnter() = reload(false)
    override fun onResume() = reload(false)

    private fun reload(refresh: Boolean) {
        HomeScenes.loadSystems(host, refresh) { list ->
            val sys = HomeScenes.systemTiles(host, list, includeSearch = true)
            tiles = if (home) {
                val menu = HomeScenes.menuTiles(host).filter { it.id != "games" }
                val front = menu.filter { it.id == "recents" || it.id == "favorites" }
                val back = menu.filter { it.id != "recents" && it.id != "favorites" }
                front + sys + back
            } else sys
            index = index.coerceIn(0, max(0, tiles.size - 1))
            loading = false
        }
    }

    private fun visible(w: Float) = max(1, ((w - 20f) / HomeScenes.CELL_W).toInt())

    private fun scrollTo(vis: Int) {
        if (index < first) first = index
        if (index >= first + vis) first = index - vis + 1
        first = first.coerceIn(0, max(0, tiles.size - vis))
    }

    override fun draw(g: Gfx) {
        g.background()
        g.header(title, logo = home)
        if (tiles.isEmpty()) {
            g.text(if (loading) "Loading..." else "No roms found. Put games in <storage>/roms/<system>/",
                g.theme.list, g.W / 2, g.H / 2, Align.CENTER, maxW = g.W - 40f)
            g.footer(null, if (home) null else "BACK"); return
        }
        val vis = visible(g.W)
        scrollTo(vis)
        val count = min(vis, tiles.size)
        val x0 = (g.W - count * HomeScenes.CELL_W) / 2
        val y0 = Gfx.HEADER + (g.H - Gfx.HEADER - Gfx.FOOTER - HomeScenes.CELL_H) / 2
        for (i in 0 until count) {
            val idx = first + i
            HomeScenes.drawCell(g, tiles[idx], x0 + i * HomeScenes.CELL_W, y0, idx == index)
        }
        if (first > 0) g.arrow(true, 6f, y0 + HomeScenes.CELL_H / 2)
        if (first + count < tiles.size) g.arrow(false, g.W - 6f, y0 + HomeScenes.CELL_H / 2)
        g.footer("SELECT", if (home) null else "BACK")
        g.footerStatus(index + 1, tiles.size)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (tiles.isEmpty()) { if (b == Btn.B && !home) { close(); return true }; return false }
        val vis = visible(host.logicalWidth)
        when (b) {
            Btn.LEFT -> if (index > 0) index-- else if (!repeat) index = tiles.size - 1 else return false
            Btn.RIGHT -> if (index < tiles.size - 1) index++ else if (!repeat) index = 0 else return false
            Btn.L1 -> index = max(0, index - vis)
            Btn.R1 -> index = min(tiles.size - 1, index + vis)
            Btn.A -> if (!repeat) tiles[index].open()
            Btn.B -> if (!repeat && !home) close() else return false
            Btn.SELECT -> if (!repeat) { tiles[index].options?.invoke() ?: host.push(GameSwitcherScene(host)) }
            Btn.MENU -> if (!repeat) host.push(GameSwitcherScene(host))
            Btn.Y -> if (!repeat) SearchFlow.start(host)
            Btn.START -> if (!repeat) { host.toast("Rescanning..."); reload(true) }
            else -> return false
        }
        if (home) MainUI.settings.mainMenuIndex = index else MainUI.settings.systemsIndex = index
        return true
    }

    private var dragAcc = 0f

    /** Dragging the row moves the selection a cell at a time. */
    override fun onDrag(dx: Float, dy: Float): Boolean {
        if (dragAcc == 0f && kotlin.math.abs(dy) > kotlin.math.abs(dx)) return false
        dragAcc += dx
        while (dragAcc <= -HomeScenes.CELL_W / 2) { if (index < tiles.size - 1) index++; dragAcc += HomeScenes.CELL_W / 2 }
        while (dragAcc >= HomeScenes.CELL_W / 2) { if (index > 0) index--; dragAcc -= HomeScenes.CELL_W / 2 }
        return true
    }

    override fun onDragEnd() { dragAcc = 0f }
    override fun onFling(vx: Float, vy: Float) = false

    override fun onTap(x: Float, y: Float): Boolean {
        if (tiles.isEmpty()) return false
        val vis = visible(host.logicalWidth)
        val count = min(vis, tiles.size)
        val x0 = (host.logicalWidth - count * HomeScenes.CELL_W) / 2
        val c = floor((x - x0) / HomeScenes.CELL_W).toInt()
        if (c !in 0 until count) return false
        val idx = first + c
        if (idx == index) tiles[idx].open() else index = idx
        return true
    }
}

/** Search: on-screen keyboard, then results across every system (Onion's Search tile). */
object SearchFlow {
    private var lastQuery = ""

    fun start(host: SceneHost) {
        host.push(io.github.marsinsanity.mainui.ui.KeyboardScene(host, "Search games", lastQuery) { q ->
            if (q.isBlank()) return@KeyboardScene
            lastQuery = q
            host.push(SearchResultsScene(host, q))
        })
    }
}
