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
        if (MainUI.settings.homeLayout == "carousel") CarouselScene(host, null, home = true) else MainMenuScene(host)

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
        if (MainUI.settings.systemsLayout == "carousel") host.push(CarouselScene(host, "Games", home = false))
        else host.push(SystemsGridScene(host))
    }

    fun openRetroArch(host: SceneHost) {
        val pkg = MainUI.emulators.raPackage
        if (pkg == null) { host.toast("RetroArch is not installed"); return }
        val err = MainUI.sessions.launchApp(pkg, "RetroArch", asGame = false)
        if (err != null) host.toast(err)
    }

    /** Tiles for the systems grid / carousel: Search, every system with games, Android. */
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
}

/**
 * OnionOS main menu: 4 tiles per page (ic-*-n / ic-*-f), labels in the grid1x4
 * font, page dots, logo in the header.
 */
class MainMenuScene(host: SceneHost) : Scene(host) {
    private var tiles = HomeScenes.menuTiles(host)
    private var index = MainUI.settings.mainMenuIndex.coerceIn(0, tiles.size - 1)

    override fun onResume() {
        tiles = HomeScenes.menuTiles(host)
        index = index.coerceIn(0, tiles.size - 1)
    }

    override fun draw(g: Gfx) {
        g.background()
        g.header(null, logo = true)
        val perPage = 4
        val page = index / perPage
        val pages = (tiles.size + perPage - 1) / perPage
        val tileW = 156f; val tileH = 278f
        val y = 90f + g.cy0
        val x0 = g.cx0 + 8f
        for (i in 0 until perPage) {
            val idx = page * perPage + i
            if (idx >= tiles.size) break
            val t = tiles[idx]
            val sel = idx == index
            val x = x0 + i * tileW
            val img = t.skin?.let { g.skin("ic-$it-${if (sel) "f" else "n"}") }
            if (img != null) g.bmp(img, x, y, tileW, tileH) else g.menuTile(t.glyph, x, y, tileW, tileH, sel)
            if (!g.theme.hideIconLabels) {
                val gs = g.theme.grid
                g.text(t.label, io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, gs.size1x4, gs.color),
                    x + tileW / 2, y + 195f, Align.CENTER, maxW = tileW - 8f,
                    color = if (sel) gs.selectedColor else gs.color)
            }
        }
        g.dots(pages, page, 396f + g.cy0)
        g.footer("SELECT", null)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        when (b) {
            Btn.LEFT -> if (index > 0) index-- else if (!repeat) index = tiles.size - 1 else return false
            Btn.RIGHT -> if (index < tiles.size - 1) index++ else if (!repeat) index = 0 else return false
            Btn.L1 -> index = max(0, index - 4)
            Btn.R1 -> index = min(tiles.size - 1, index + 4)
            Btn.A -> if (!repeat) tiles[index].open()
            Btn.SELECT, Btn.MENU -> if (!repeat) host.push(GameSwitcherScene(host))
            Btn.Y -> if (!repeat) SearchFlow.start(host)
            else -> return false
        }
        MainUI.settings.mainMenuIndex = index
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val w = host.logicalWidth
        val col = ((x - (w - 640f) / 2f - 8f) / 156f).toInt()
        if (col !in 0..3) return false
        val idx = (index / 4) * 4 + col
        if (idx >= tiles.size) return false
        if (idx == index) tiles[idx].open() else index = idx
        return true
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
        val cellW = 155f; val cellH = 170f
        val x0 = g.cx0 + (640f - cols * cellW) / 2
        val areaTop = Gfx.HEADER; val areaH = g.H - Gfx.HEADER - Gfx.FOOTER - 30f
        val y0 = areaTop + (areaH - rows * cellH) / 2
        val gs = g.theme.grid
        val labelStyle = io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, gs.size3x4, gs.color)
        val focus = g.skin("bg-game-item-f"); val normal = g.skin("bg-game-item-n")
        for (i in 0 until perPage) {
            val idx = page * perPage + i
            if (idx >= tiles.size) break
            val t = tiles[idx]
            val sel = idx == index
            val x = x0 + (i % cols) * cellW
            val y = y0 + (i / cols) * cellH
            val bg = if (sel) focus else normal
            if (bg != null) g.bmp(bg, x, y, cellW, cellH)
            else if (sel) g.fillRect(x + 4, y + 4, cellW - 8, cellH - 8, Color.argb(70, 255, 255, 255), 14f)
            val icon = HomeScenes.tileIcon(g, t, sel)
            val iconBoxH = if (g.theme.hideIconLabels) cellH - 20 else cellH - 46
            if (icon != null) g.bmpFit(icon, x + 10, y + 8, cellW - 20, iconBoxH)
            else if (t.glyph == "game") g.systemTile(t.short, x + 22, y + 18, cellW - 44, iconBoxH - 20, sel)
            else { g.systemTile("", x + 22, y + 18, cellW - 44, iconBoxH - 20, sel); g.glyph(t.glyph, x + cellW / 2, y + 18 + (iconBoxH - 20) / 2, 30f, Color.WHITE) }
            if (!g.theme.hideIconLabels) {
                g.text(t.label, labelStyle, x + cellW / 2, y + cellH - 22f, Align.CENTER, maxW = cellW - 8f,
                    color = if (sel) gs.selectedColor else gs.color, marquee = sel)
            }
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
 * Miyoo P60 style carousel: the focused icon large in the centre with its
 * neighbours scaled down on both sides, smooth scrolling between them.
 * [home] = top level screen (menu entries + systems), otherwise systems only.
 */
class CarouselScene(host: SceneHost, private val title: String?, private val home: Boolean) : Scene(host) {
    private var tiles: List<Tile> = emptyList()
    private var index = if (home) MainUI.settings.mainMenuIndex else MainUI.settings.systemsIndex
    private var pos = index.toFloat()
    private var lastFrame = 0L
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
            if (loading) pos = index.toFloat()
            loading = false
        }
    }

    override fun animating(now: Long) = abs(pos - index) > 0.001f

    override fun draw(g: Gfx) {
        val now = g.now
        val dt = if (lastFrame == 0L) 16f else min(64f, (now - lastFrame).toFloat())
        lastFrame = now
        pos += (index - pos) * min(1f, dt / 90f)
        if (abs(pos - index) < 0.002f) pos = index.toFloat()

        g.background()
        g.header(title, logo = home)
        if (tiles.isEmpty()) {
            g.text(if (loading) "Loading..." else "Nothing here yet", g.theme.list, g.W / 2, g.H / 2, Align.CENTER)
            g.footer(null, if (home) null else "BACK"); return
        }
        val cy = Gfx.HEADER + (g.H - Gfx.HEADER - Gfx.FOOTER) * 0.43f
        val baseW = 190f; val baseH = 206f
        val spacing = 210f
        // draw far items first so the focused one is on top
        val order = tiles.indices.filter { abs(it - pos) < 3.2f }.sortedByDescending { abs(it - pos) }
        for (i in order) {
            val d = i - pos
            val ad = abs(d)
            val s = max(0.45f, 1.25f - ad * 0.42f)
            val alpha = (255 * max(0.25f, 1f - ad * 0.38f)).toInt()
            val w = baseW * s; val h = baseH * s
            val cx = g.W / 2 + d * spacing * (1f - min(ad, 2f) * 0.1f)
            val x = cx - w / 2; val y = cy - h / 2
            val t = tiles[i]
            val sel = i == index && ad < 0.5f
            val img = t.skin?.let { g.skin("ic-$it-${if (sel) "f" else "n"}") } ?: HomeScenes.tileIcon(g, t, sel)
            if (img != null) g.bmpFit(img, x, y, w, h, alpha)
            else if (t.skin != null || t.glyph != "game") {
                g.systemTile("", x + w * 0.1f, y + h * 0.1f, w * 0.8f, h * 0.8f, sel, alpha)
                g.glyph(t.glyph, cx, cy, w * 0.22f, Color.argb(alpha, 255, 255, 255))
            } else g.systemTile(t.short, x + w * 0.1f, y + h * 0.1f, w * 0.8f, h * 0.8f, sel, alpha)
        }
        val cur = tiles[index]
        val nameY = cy + baseH * 1.25f / 2 + 34f
        g.text(cur.label, g.theme.title, g.W / 2, nameY, Align.CENTER, maxW = g.W - 80f, marquee = true)
        cur.sub?.let { g.text(it, g.theme.list, g.W / 2, nameY + 38f, Align.CENTER, size = g.theme.list.size * 0.85f, alpha = 190) }
        g.arrow(true, 16f, cy, withBg = true)
        g.arrow(false, g.W - 16f, cy, withBg = true)
        g.footer("SELECT", if (home) null else "BACK")
        g.footerStatus(index + 1, tiles.size)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (tiles.isEmpty()) { if (b == Btn.B && !home) { close(); return true }; return false }
        when (b) {
            Btn.LEFT, Btn.UP -> if (index > 0) index-- else if (!repeat) index = tiles.size - 1 else return false
            Btn.RIGHT, Btn.DOWN -> if (index < tiles.size - 1) index++ else if (!repeat) index = 0 else return false
            Btn.L1 -> index = max(0, index - 5)
            Btn.R1 -> index = min(tiles.size - 1, index + 5)
            Btn.A -> if (!repeat) tiles[index].open()
            Btn.B -> if (!repeat && !home) close() else return false
            Btn.SELECT -> if (!repeat) { tiles[index].options?.invoke() ?: host.push(GameSwitcherScene(host)) }
            Btn.MENU -> if (!repeat) host.push(GameSwitcherScene(host))
            Btn.Y -> if (!repeat) SearchFlow.start(host)
            Btn.START -> if (!repeat) { host.toast("Rescanning..."); reload(true) }
            else -> return false
        }
        if (home) MainUI.settings.mainMenuIndex = index else MainUI.settings.systemsIndex = index
        invalidate()
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (tiles.isEmpty()) return false
        val w = host.logicalWidth
        when {
            x < w * 0.3f -> if (index > 0) index--
            x > w * 0.7f -> if (index < tiles.size - 1) index++
            else -> tiles[index].open()
        }
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
