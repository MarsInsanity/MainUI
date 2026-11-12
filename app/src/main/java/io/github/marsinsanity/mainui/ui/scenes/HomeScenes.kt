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
        if (s.showEmulators) out += Tile("emulators", "Emulators", "emulators", "emulators") { host.push(EmulatorAppsScene(host)) }
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
        Launch.app(host, pkg, "RetroArch", asGame = false)
    }

    /** Tiles for the systems grid / row: Search, Android, then every system with games. */
    fun systemTiles(host: SceneHost, systems: List<GameSystem>, includeSearch: Boolean, onRescan: () -> Unit = {}): List<Tile> {
        val s = MainUI.settings
        val out = mutableListOf<Tile>()
        if (includeSearch && s.showSearchTile) out += Tile("search", "Search", "search", icons = listOf("search"), short = "?") {
            SearchFlow.start(host)
        }
        if (s.showAndroidSystem) {
            // Installed ones (uninstalled games stay in the library), counted with the systems off
            // the UI thread: listing the apps takes a while after every install / update.
            val n = androidCount.takeIf { it >= 0 } ?: MainUI.library.androidGames().size
            out += Tile("android", "Android", "android", icons = SystemDef.ANDROID.icons, short = "APK",
                sub = plural(n, "game"), open = { host.push(AndroidGamesScene(host)) },
                // Like the Ports tile: apps are added here too.
                options = { host.push(PopMenuScene(host, "Android", listOf(
                    UiItem("Add an Android app...", onSelect = { host.push(AppPickerScene.android(host, onRescan)) }),
                ))) })
        }
        for (sys in systems) {
            // Empty folders stay hidden; a folder with files MainUI can't play is shown so it can say why,
            // and a system with an installed emulator's menu (PICO-8: Splore) always is.
            val unplayable = sys.unplayableSummary
            val menu = sys.menu
            // (Ports is listed even empty: it is where Android apps can be put. Settings > User
            // interface > Show Ports system off: only while it has ports.)
            val ports = sys.id == io.github.marsinsanity.mainui.data.AndroidPorts.SYSTEM
            if (ports && !s.showPortsSystem && sys.gameCount <= 0) continue
            if (!ports && !s.showEmptySystems && sys.gameCount == 0 && unplayable == null && menu.isEmpty()) continue
            out += Tile(sys.id, sys.name, "game", icons = sys.def.icons, short = sys.def.short,
                sub = when {
                    ports && sys.gameCount <= 0 -> "add Android ports"
                    ports -> plural(sys.gameCount, "port")
                    sys.gameCount <= 0 && menu.isNotEmpty() -> menu.joinToString(" / ") { it.name }
                    unplayable != null -> "no playable files"
                    sys.gameCount >= 0 -> plural(sys.gameCount, "game")
                    else -> null
                }, system = sys,
                open = { host.push(GameListScene(host, sys, null)) },
                options = { systemOptions(host, sys, onRescan) })
        }
        return out
    }

    /** [onRescan]: the screen's tiles are built again (new counts) after a rescan or unzip. */
    fun systemOptions(host: SceneHost, sys: GameSystem, onRescan: () -> Unit = {}) {
        if (sys.id == io.github.marsinsanity.mainui.data.AndroidPorts.SYSTEM) {
            // Ports: no core or scraper; Android apps are added here.
            host.push(PopMenuScene(host, sys.name, listOf(
                UiItem("Add an Android app...", onSelect = { host.push(AppPickerScene.ports(host, onRescan)) }),
                UiItem("Rescan folder", onSelect = {
                    MainUI.games.invalidate()
                    // (The system as it is now: its folder may have appeared since the tile was made.)
                    host.io({ MainUI.games.countGames(MainUI.games.systems(MainUI.settings.showUnknownFolders).firstOrNull { it.id == sys.id } ?: sys) }) {
                        host.toast("${sys.name}: ${plural(it, "port")}"); onRescan()
                    }
                }),
            )))
            return
        }
        val cur = MainUI.emulators.choose(sys.def, null)
        host.push(PopMenuScene(host, sys.name, listOf(
            UiItem("Core: ${if (MainUI.library.coreForSystem(sys.def.id) != null) cur?.name ?: "none" else "Default (${cur?.shortName ?: "none"})"}",
                onSelect = { host.push(CoreSelectScene(host, sys.def, null)) }),
            UiItem("Scrape artwork", onSelect = { host.push(ScraperScene(host, listOf(sys.id))) }),
            UiItem("Rescan folder", onSelect = {
                MainUI.games.invalidate()
                host.io({ MainUI.games.countGames(sys) }) { host.toast("${sys.name}: ${plural(it, "game")}"); onRescan() }
            }),
        ) + listOfNotNull(
            // Zipped (.zip / .rar) games of a system whose emulators can't open them.
            UnzipFlow.archives(sys).takeIf { it > 0 }?.let {
                UiItem(UnzipFlow.label(sys), onSelect = {
                    UnzipFlow.start(host, sys) { onRescan(); host.invalidate() }
                })
            },
        )))
    }

    /**
     * Automatic update checks (MainUI every few hours, emulators daily) when the home screen
     * shows, whichever layout it has; says so when there is something new.
     */
    fun checkForUpdate(host: SceneHost) {
        if (io.github.marsinsanity.mainui.store.EmulatorUpdates.isDue()) {
            // (null while another check runs: the App Store's, or one from an earlier visit here)
            host.io({ runCatching { io.github.marsinsanity.mainui.store.EmulatorUpdates.checkIfIdle() }.getOrNull() }) { ups ->
                if (!ups.isNullOrEmpty()) host.toast(
                    if (ups.size == 1) "${ups[0].name} ${ups[0].latest} is available: Settings > Emulators & cores"
                    else "${ups.size} emulator updates: Settings > Emulators & cores", 4500)
            }
        }
        if (!io.github.marsinsanity.mainui.store.Updater.isDue()) return
        host.io({ runCatching { io.github.marsinsanity.mainui.store.Updater.checkIfIdle() }.getOrNull() }) { u ->
            if (u != null && u.versionCode > io.github.marsinsanity.mainui.store.Updater.installed)
                host.toast("MainUI ${u.versionName} is available: Settings > Updates", 4000)
        }
    }

    /** The Android tile's count, from the last [loadSystems] (-1: not counted yet). */
    @Volatile private var androidCount = -1

    /** Loads systems + game counts in the background. */
    fun loadSystems(host: SceneHost, refresh: Boolean = false, done: (List<GameSystem>) -> Unit) {
        host.io({
            if (MainUI.settings.showAndroidSystem) androidCount = runCatching { MainUI.apps.androidGames().size }.getOrDefault(androidCount)
            val list = MainUI.games.systems(MainUI.settings.showUnknownFolders, refresh)
            list.filter { it.gameCount < 0 || refresh }.forEach { runCatching { MainUI.games.countGames(it) } } // (one unreadable system never blanks the others)
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
 * The top-level page's row of tall tiles: [VISIBLE] side by side, the window sliding smoothly
 * to keep the selection in view, page dots below (one per tile, or per group when they don't
 * fit). Used by the top-level page and the Games screen's row view, so both look the same.
 */
class TileStrip {
    /** The leftmost tile in view. */
    var first = 0
        private set
    /** Follows [first] smoothly, in tiles. */
    private var scroll = Float.NaN
    private var lastFrame = 0L

    fun follow(index: Int, count: Int) {
        if (index < first) first = index
        if (index >= first + VISIBLE) first = index - VISIBLE + 1
        first = first.coerceIn(0, max(0, count - VISIBLE))
    }

    /** Fewer tiles than the window: centered. */
    private fun x0(width: Float, count: Int) =
        (width - 640f) / 2f + 8f + if (count < VISIBLE) (VISIBLE - count) * TILE_W / 2 else 0f

    fun draw(g: Gfx, count: Int, index: Int, tile: (idx: Int, x: Float, y: Float, w: Float, h: Float, sel: Boolean) -> Unit) {
        val y = TOP + g.cy0
        val x0 = x0(g.W, count)
        // Ease towards the target window (about 120 ms), then stop redrawing.
        if (scroll.isNaN()) scroll = first.toFloat()
        val dt = if (lastFrame == 0L) 16L else (g.now - lastFrame).coerceIn(1L, 50L)
        lastFrame = g.now
        val target = first.toFloat()
        scroll += (target - scroll) * (1f - Math.pow(0.001, dt / 120.0).toFloat())
        if (kotlin.math.abs(target - scroll) < 0.01f) { scroll = target; lastFrame = 0L } else g.redrawAfter(16)

        val from = kotlin.math.floor(scroll).toInt().coerceAtLeast(0)
        val to = min(count - 1, kotlin.math.ceil(scroll).toInt() + VISIBLE - 1)
        for (idx in from..to) {
            val x = x0 + (idx - scroll) * TILE_W
            if (x + TILE_W < 0f || x > g.W) continue
            tile(idx, x, y, TILE_W, TILE_H, idx == index)
        }
        // So it's clear more tiles are to the side.
        if (count > VISIBLE) g.dots(count, index, DOTS_Y + g.cy0)
    }

    /** The tile at [x], [y] (a tap), or null: the header, dots and footer are no tile. */
    fun hit(x: Float, y: Float, width: Float, height: Float, count: Int): Int? {
        val top = TOP + (height - 480f) / 2f
        if (y < top || y > top + TILE_H) return null
        val col = kotlin.math.floor((x - x0(width, count)) / TILE_W).toInt()
        if (col !in 0 until VISIBLE) return null
        return (first + col).takeIf { it in 0 until count }
    }

    companion object {
        const val VISIBLE = 4
        const val TILE_W = 156f
        const val TILE_H = 278f
        const val TOP = 90f
        const val DOTS_Y = 396f

        /**
         * A system (or Search / Android) as a top-level page tile: the same rounded box, its theme
         * icon in it (or the built-in system tile), the label in the grid1x4 font, its game count.
         */
        fun drawSystem(g: Gfx, t: Tile, x: Float, y: Float, w: Float, h: Float, sel: Boolean) {
            val img = t.skin?.let { g.skin("ic-$it-${if (sel) "f" else "n"}") }
            if (img != null) g.bmp(img, x, y, w, h)
            else {
                // The theme's blank main menu tile (MainUI's own theme has one), else the same box drawn.
                val blank = g.skin(if (sel) "bg-home-tile-f" else "bg-home-tile-n")
                if (blank != null) g.bmp(blank, x, y, w, h) else g.menuTileBox(x, y, w, h, sel)
                val cx = x + w / 2; val cy = y + h * 0.36f
                val box = w * 0.66f
                val icon = HomeScenes.tileIcon(g, t, sel)
                when {
                    // Selected: with the soft glow the main menu's selected glyph has.
                    icon != null && sel -> g.bmpFitGlow(icon, cx - box / 2, cy - box / 2, box, box,
                        io.github.marsinsanity.mainui.ui.withAlpha(io.github.marsinsanity.mainui.ui.tileHighlight(g.theme.accent), 150))
                    icon != null -> g.bmpFit(icon, cx - box / 2, cy - box / 2, box, box)
                    t.glyph == "game" -> g.systemTile(t.short, cx - box / 2, cy - box * 0.38f, box, box * 0.76f, sel)
                    else -> g.glyph(t.glyph, cx, cy, w * 0.26f, if (sel) Color.WHITE else Color.argb(200, 255, 255, 255))
                }
            }
            if (g.theme.hideIconLabels) return
            val gs = g.theme.grid
            val color = if (sel) gs.selectedColor else gs.color
            val maxW = w - 14f
            // The main menu's label size when it fits; system names are longer ("Nintendo Switch"):
            // a little smaller, then on two lines, and only then scrolling.
            val (lines, size) = fitLabel(t.label, gs.size1x4, kotlin.math.min(gs.size3x4, gs.size1x4 * 0.8f)) { str, sz ->
                g.textWidth(str, io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, sz, gs.color), sz) <= maxW
            }
            val style = io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, size, gs.color)
            if (lines.size == 1) g.text(lines[0], style, x + w / 2, y + 195f, Align.CENTER, maxW = maxW, color = color, marquee = sel)
            else lines.forEachIndexed { i, l ->
                g.text(l, style, x + w / 2, y + 195f + (i - 0.5f) * size * 1.05f - 8f, Align.CENTER, maxW = maxW, color = color, marquee = sel)
            }
            t.sub?.let { sub ->
                g.text(sub, io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, gs.size3x4 * 0.8f, gs.color),
                    x + w / 2, y + 222f, Align.CENTER, maxW = maxW,
                    color = if (sel) gs.selectedColor else Color.argb(170, Color.red(gs.color), Color.green(gs.color), Color.blue(gs.color)))
            }
        }

        /**
         * [label] as one line at the largest size from [max] down to [min] that [fits], else on two
         * lines split where they balance best, at a space or after a "/" or "-" ("Genesis/" over
         * "Megadrive"), at the largest size both fit (at least 85% of [min]), else one line at
         * [min] (scrolled when selected).
         */
        fun fitLabel(label: String, max: Float, min: Float, fits: (String, Float) -> Boolean): Pair<List<String>, Float> {
            var size = max
            while (size >= min) { if (fits(label, size)) return listOf(label) to size; size -= 1f }
            // Where the second line starts: after a space (dropped) or after "/" / "-" (kept above).
            val breaks = (1 until label.length).filter { i -> label[i - 1] == ' ' || label[i - 1] == '/' || label[i - 1] == '-' }
                .filter { label.substring(0, it).isNotBlank() && label.substring(it).isNotBlank() }
            if (breaks.isNotEmpty()) {
                val at = breaks.minByOrNull { kotlin.math.abs(label.length - 2 * it) }!!
                val two = listOf(label.substring(0, at).trim(), label.substring(at).trim())
                size = kotlin.math.min(max, min * 1.15f)
                while (size >= min * 0.85f) { if (two.all { fits(it, size) }) return two to size; size -= 1f }
                return two to min * 0.85f
            }
            return listOf(label) to min
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
     * a mostly empty last page).
     */
    private val strip = TileStrip()

    private fun follow() = strip.follow(index, tiles.size)

    init { follow(); HomeScenes.checkForUpdate(host) }

    override fun onResume() {
        HomeScenes.checkForUpdate(host)
        tiles = HomeScenes.menuTiles(host)
        index = index.coerceIn(0, tiles.size - 1)
        follow()
    }

    override fun draw(g: Gfx) {
        g.background()
        g.header(null, logo = true)
        strip.draw(g, tiles.size, index) { idx, x, y, tileW, tileH, sel ->
            val t = tiles[idx]
            val img = t.skin?.let { g.skin("ic-$it-${if (sel) "f" else "n"}") }
            if (img != null) g.bmp(img, x, y, tileW, tileH) else g.menuTile(t.glyph, x, y, tileW, tileH, sel)
            if (!g.theme.hideIconLabels) {
                val gs = g.theme.grid
                g.text(t.label, io.github.marsinsanity.mainui.theme.FontStyle(gs.typeface, gs.size1x4, gs.color),
                    x + tileW / 2, y + 195f, Align.CENTER, maxW = tileW - 8f,
                    color = if (sel) gs.selectedColor else gs.color)
            }
        }
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
        follow()
        val idx = strip.hit(x, y, host.logicalWidth, host.logicalHeight, tiles.size) ?: return false
        if (idx == index) tiles[idx].open() else { index = idx; follow(); MainUI.settings.mainMenuIndex = index }
        return true
    }

    companion object {
        const val VISIBLE = TileStrip.VISIBLE
        const val TILE_W = TileStrip.TILE_W
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
            tiles = HomeScenes.systemTiles(host, list, includeSearch = true) { reload(false) }
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
        if (idx == index) tiles[idx].open() else { index = idx; MainUI.settings.systemsIndex = index }
        return true
    }
}

/**
 * Row view: a single row of tiles that scrolls sideways. [home] = top level screen (main menu
 * entries + systems) in the games grid's own cells (bg-game-item-f/n, icon, label). Otherwise
 * the Games screen: systems as the top-level page's tall tiles with its page dots ([TileStrip]).
 * Replaces the old carousel.
 */
class RowScene(host: SceneHost, private val title: String?, private val home: Boolean) : Scene(host) {
    private var tiles: List<Tile> = emptyList()
    private var index = if (home) MainUI.settings.mainMenuIndex else MainUI.settings.systemsIndex
    private var first = 0
    private var loading = true
    /** The Games screen looks like the top-level page. */
    private val strip = if (home) null else TileStrip()

    override fun onEnter() {
        if (home) {
            HomeScenes.checkForUpdate(host)
            // The menu entries (Settings, Apps...) right away, not only once every system is counted.
            if (tiles.isEmpty()) {
                val (front, back) = menuParts()
                tiles = front + back
                early = true
                savedIndex = index
                index = index.coerceIn(0, max(0, tiles.size - 1))
            }
        }
        reload(false)
    }
    override fun onResume() {
        if (home) HomeScenes.checkForUpdate(host)
        reload(false)
    }

    /** Showing only the menu entries until the systems are in ([savedIndex]: the remembered selection). */
    private var early = false
    private var savedIndex = 0
    private var movedEarly = false

    private fun menuParts(): Pair<List<Tile>, List<Tile>> {
        val menu = HomeScenes.menuTiles(host).filter { it.id != "games" }
        return menu.filter { it.id == "recents" || it.id == "favorites" } to menu.filter { it.id != "recents" && it.id != "favorites" }
    }

    private fun reload(refresh: Boolean) {
        HomeScenes.loadSystems(host, refresh) { list ->
            val sys = HomeScenes.systemTiles(host, list, includeSearch = true) { reload(false) }
            val picked = if (early && movedEarly) tiles.getOrNull(index)?.id else null
            tiles = if (home) {
                val (front, back) = menuParts()
                front + sys + back
            } else sys
            // Moved while the systems were loading: the same entry stays selected; else the saved one.
            if (early) index = if (picked != null) tiles.indexOfFirst { it.id == picked }.takeIf { it >= 0 } ?: savedIndex else savedIndex
            early = false
            index = index.coerceIn(0, max(0, tiles.size - 1))
            loading = false
        }
    }

    private fun visible(w: Float) = if (strip != null) TileStrip.VISIBLE else max(1, ((w - 20f) / HomeScenes.CELL_W).toInt())

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
        if (strip != null) {
            strip.follow(index, tiles.size)
            strip.draw(g, tiles.size, index) { idx, x, y, w, h, sel -> TileStrip.drawSystem(g, tiles[idx], x, y, w, h, sel) }
            g.footer("SELECT", "BACK")
            return
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
        val before = index
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
        // (Only a real move: MENU, Y... while systems load keep the remembered tile.)
        if (early && index != before) movedEarly = true
        if (!early) { if (home) MainUI.settings.mainMenuIndex = index else MainUI.settings.systemsIndex = index }
        return true
    }

    private var dragAcc = 0f

    /** Dragging the row moves the selection a cell at a time. */
    override fun onDrag(dx: Float, dy: Float): Boolean {
        if (dragAcc == 0f && kotlin.math.abs(dy) > kotlin.math.abs(dx)) return false
        val before = index
        val step = (if (strip != null) TileStrip.TILE_W else HomeScenes.CELL_W) / 2
        dragAcc += dx
        while (dragAcc <= -step) { if (index < tiles.size - 1) index++; dragAcc += step }
        while (dragAcc >= step) { if (index > 0) index--; dragAcc -= step }
        if (early && index != before) movedEarly = true
        return true
    }

    override fun onDragEnd() { dragAcc = 0f }
    override fun onFling(vx: Float, vy: Float) = false

    override fun onTap(x: Float, y: Float): Boolean {
        if (tiles.isEmpty()) return false
        // (The window as it is for the current tiles: they may have changed since the last frame.)
        if (strip != null) {
            strip.follow(index, tiles.size)
            val idx = strip.hit(x, y, host.logicalWidth, host.logicalHeight, tiles.size) ?: return false
            if (idx == index) tiles[idx].open() else { index = idx; if (early) movedEarly = true; MainUI.settings.systemsIndex = index }
            return true
        }
        val vis = visible(host.logicalWidth)
        scrollTo(vis)
        val count = min(vis, tiles.size)
        val x0 = (host.logicalWidth - count * HomeScenes.CELL_W) / 2
        val y0 = Gfx.HEADER + (host.logicalHeight - Gfx.HEADER - Gfx.FOOTER - HomeScenes.CELL_H) / 2
        if (y < y0 || y > y0 + HomeScenes.CELL_H) return false
        val c = floor((x - x0) / HomeScenes.CELL_W).toInt()
        if (c !in 0 until count) return false
        val idx = first + c
        if (idx !in tiles.indices) return false
        if (idx == index) tiles[idx].open() else { index = idx; if (early) movedEarly = true }
        if (!early) { if (home) MainUI.settings.mainMenuIndex = index else MainUI.settings.systemsIndex = index }
        return true
    }
}

/** Search: on-screen keyboard, then results across every system (Onion's Search tile). */
object SearchFlow {
    private var lastQuery = ""

    /** Asks for a name and lists the games having it: in every system, or only in [system]. */
    fun start(host: SceneHost, system: io.github.marsinsanity.mainui.data.GameSystem? = null) {
        val title = if (system == null) "Search games" else "Search ${system.def.short}"
        host.push(io.github.marsinsanity.mainui.ui.KeyboardScene(host, title, lastQuery) { q ->
            if (q.isBlank()) return@KeyboardScene
            lastQuery = q
            host.push(SearchResultsScene(host, q, system))
        })
    }
}
