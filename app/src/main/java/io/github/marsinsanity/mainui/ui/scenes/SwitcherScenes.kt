package io.github.marsinsanity.mainui.ui.scenes

import android.graphics.Bitmap
import android.graphics.Color
import android.provider.Settings as AndroidSettings
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.data.Library
import io.github.marsinsanity.mainui.theme.ThemeManager
import io.github.marsinsanity.mainui.ui.Align
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.Images
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.withAlpha
import java.io.File

/** Screen brightness helper (root or WRITE_SETTINGS), 0..10 like Onion. */
object Brightness {
    fun get(ctx: android.content.Context): Int = runCatching {
        val v = AndroidSettings.System.getInt(ctx.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS)
        Math.round(v / 25.5f).coerceIn(0, 10)
    }.getOrDefault(5)

    fun set(ctx: android.content.Context, level: Int) {
        val v = (level.coerceIn(0, 10) * 25.5f).toInt().coerceIn(3, 255)
        if (AndroidSettings.System.canWrite(ctx)) {
            AndroidSettings.System.putInt(ctx.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, 0)
            AndroidSettings.System.putInt(ctx.contentResolver, AndroidSettings.System.SCREEN_BRIGHTNESS, v)
        } else if (Root.knownAvailable()) {
            Thread { Root.exec("settings put system screen_brightness_mode 0; settings put system screen_brightness $v") }.start()
        }
    }
}

/**
 * OnionOS GameSwitcher: recently played games as full screen screenshots.
 * LEFT/RIGHT browse, A resume, B back, X remove, Y view mode (normal /
 * minimal / fullscreen), SELECT toggles play time, UP/DOWN brightness.
 */
class GameSwitcherScene(host: SceneHost) : Scene(host) {
    private var games: List<Library.Entry> = emptyList()
    private var index = 0
    private var view = MainUI.settings.prefs.getInt("gs_view", 0) // 0 normal, 1 minimal, 2 fullscreen
    private var viewRestore = 0
    private var timeMode = MainUI.settings.prefs.getInt("gs_time", 0) // 0 title, 1 game time, 2 game + total
    private var legendUntil = 0L
    private var brightnessUntil = 0L
    private var brightness = 5
    private var yDownAt = 0L

    override fun onEnter() {
        games = MainUI.library.recents()
        val active = MainUI.sessions.active
        index = games.indexOfFirst { it.key == active?.gameKey }.coerceAtLeast(0)
        legendUntil = System.currentTimeMillis() + 4000
        brightness = Brightness.get(ctx)
    }

    override fun animating(now: Long) = now < legendUntil || now < brightnessUntil

    private fun screenshot(e: Library.Entry): Bitmap? {
        val f = e.screenshot?.let { File(it) }?.takeIf { it.isFile }
            ?: MainUI.sessions.screenshotFile(e.key).takeIf { it.isFile }
        if (f != null) return Images.get("shot:${f.absolutePath}:${f.lastModified()}", host, 1600) { f }
        // No screenshot yet: fall back to box art like Onion's rom screen fallback
        return GameItems.preview(host, null, e.toGame())()
    }

    override fun draw(g: Gfx) {
        g.c.drawColor(Color.BLACK)
        if (games.isEmpty()) {
            g.background()
            g.header("GameSwitcher")
            g.text("No recently played games", g.theme.list, g.W / 2, g.H / 2, Align.CENTER)
            g.footer(null, "BACK")
            return
        }
        val e = games[index]
        val shot = screenshot(e)
        if (shot != null) {
            val isScreenshot = shot.width >= 320 && e.screenshot != null
            if (isScreenshot) g.bmpCover(shot, 0f, 0f, g.W, g.H) else { g.background(); g.bmpFit(shot, 40f, 70f, g.W - 80f, g.H - 200f) }
        } else {
            g.background()
            g.text("No screenshot", g.theme.list, g.W / 2, g.H / 2, Align.CENTER, alpha = 150)
        }

        val topBar = g.skin("extra/gs-top-bar")
        val bottomBar = g.skin("extra/gs-bottom-bar")
        val headerH = if (view == 0) (topBar?.height?.toFloat() ?: Gfx.HEADER) else 0f
        val footerH = if (view == 0) (bottomBar?.height?.toFloat() ?: Gfx.FOOTER) else 0f

        if (view == 0) {
            val title = when (timeMode) {
                1 -> Launch.formatTime(MainUI.library.playTime(e.key))
                2 -> "${Launch.formatTime(MainUI.library.playTime(e.key))} / ${Launch.formatTime(MainUI.library.totalPlayTime())}"
                else -> "GameSwitcher"
            }
            if (topBar != null) {
                g.bmp(topBar, 0f, 0f, g.W, topBar.height.toFloat())
                g.text(title, g.theme.title, g.W / 2, headerH / 2, Align.CENTER)
                g.battery(g.W - 44f, headerH / 2)
            } else g.header(title)
            if (bottomBar != null) g.bmp(bottomBar, 0f, g.H - bottomBar.height, g.W, bottomBar.height.toFloat())
            else { g.footer("RESUME", "BACK"); g.footerStatus(index + 1, games.size) }
        }

        if (view != 2) {
            // game name bar with arrows
            val barH = 60f
            val y = g.H - footerH - barH
            g.fillRect(0f, y, g.W, barH, Color.argb(150, 0, 0, 0))
            val left = g.skin("extra/arrowLeft") ?: g.skin("icon-left-arrow-24")
            val pad = (left?.width ?: 24) + 20f
            if (index > 0) g.arrow(true, 10f, y + 30f, withBg = true)
            if (index < games.size - 1) g.arrow(false, g.W - 10f, y + 30f, withBg = true)
            g.text(e.name, g.theme.title, g.W / 2, y + 30f, Align.CENTER, maxW = g.W - 2 * pad, color = Color.WHITE, marquee = true)
        }

        if (g.now < legendUntil && view != 2) {
            g.skin("extra/gs-legend")?.let { g.bmp(it, g.W - it.width, headerH) }
        }
        if (g.now < brightnessUntil) {
            val lum = g.skin("extra/lum$brightness")
            if (lum != null) {
                if (lum.height > lum.width) g.bmp(lum, 0f, g.H / 2 - lum.height / 2f)
                else g.bmp(lum, (g.W - lum.width) / 2, headerH)
            } else {
                val w = 300f; val x = (g.W - w) / 2; val y = headerH + 20f
                g.fillRect(x, y, w, 24f, Color.argb(160, 0, 0, 0), 12f)
                g.fillRect(x + 4, y + 4, (w - 8) * brightness / 10f, 16f, withAlpha(g.theme.accent, 255), 8f)
            }
        }
    }

    private fun resume() {
        val e = games.getOrNull(index) ?: return
        close()
        Launch.game(host, e.toGame())
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (games.isEmpty()) { if (b == Btn.B || b == Btn.MENU) { close(); return true }; return false }
        when (b) {
            Btn.LEFT -> if (index > 0) index-- else return false
            Btn.RIGHT -> if (index < games.size - 1) index++ else return false
            Btn.A, Btn.MENU -> if (!repeat) resume()
            Btn.B -> if (!repeat) close()
            Btn.X -> if (!repeat) {
                val e = games[index]
                host.push(DialogScene(host, "Remove from history", "Are you sure you want to\nremove game from history?") {
                    MainUI.library.removeRecent(e.key)
                    games = MainUI.library.recents()
                    index = index.coerceAtMost(maxOf(0, games.size - 1))
                })
            }
            Btn.Y -> if (!repeat) {
                view = if (view == 2) viewRestore else if (view == 0) 1 else 0
                MainUI.settings.prefs.edit().putInt("gs_view", view).apply()
            }
            Btn.START -> if (!repeat) { viewRestore = view; view = if (view == 2) viewRestore else 2 }
            Btn.SELECT -> if (!repeat) {
                timeMode = (timeMode + 1) % 3
                MainUI.settings.prefs.edit().putInt("gs_time", timeMode).apply()
                legendUntil = System.currentTimeMillis() + 2000
            }
            Btn.UP, Btn.DOWN -> {
                brightness = (brightness + if (b == Btn.UP) 1 else -1).coerceIn(0, 10)
                Brightness.set(ctx, brightness)
                brightnessUntil = System.currentTimeMillis() + 1500
            }
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        if (games.isEmpty()) return false
        val w = host.logicalWidth
        when {
            x < w * 0.25f -> if (index > 0) index--
            x > w * 0.75f -> if (index < games.size - 1) index++
            else -> resume()
        }
        return true
    }
}

/**
 * Theme Switcher app: full screen theme previews, LEFT/RIGHT to browse,
 * A to apply. Installs .zip themes found in a Themes folder on the fly.
 */
class ThemeSwitcherScene(host: SceneHost) : Scene(host) {
    private var themes: List<ThemeManager.ThemeEntry> = emptyList()
    private var index = 0
    private var busy = false

    override fun onEnter() {
        host.io({ MainUI.themes.list() }) { list ->
            themes = list
            val cur = MainUI.settings.themePath
            index = list.indexOfFirst { (it.dir?.absolutePath ?: "") == cur }.coerceAtLeast(0)
        }
    }

    override fun draw(g: Gfx) {
        g.c.drawColor(Color.BLACK)
        if (themes.isEmpty()) { g.background(); g.header("Themes"); g.footer(null, "BACK"); return }
        val t = themes[index]
        val prev = Images.getBitmap("themepreview:${t.id}", host) { MainUI.themes.preview(t) }
        if (prev != null) g.bmpFit(prev, 0f, 0f, g.W, g.H) else {
            g.background()
            g.text("No preview", g.theme.list, g.W / 2, g.H / 2, Align.CENTER, alpha = 150)
        }
        // name bar
        val y = g.H - Gfx.FOOTER - 70f
        g.fillRect(0f, y, g.W, 70f, Color.argb(170, 0, 0, 0))
        if (index > 0) g.arrow(true, 10f, y + 35f, withBg = true)
        if (index < themes.size - 1) g.arrow(false, g.W - 10f, y + 35f, withBg = true)
        g.text(t.name, g.theme.title, g.W / 2, y + 24f, Align.CENTER, maxW = g.W - 100f, color = Color.WHITE)
        val by = listOf(t.author.takeIf { it.isNotBlank() }?.let { "by $it" }, t.description.takeIf { it.isNotBlank() }).filterNotNull().joinToString(" - ")
        g.text(by, g.theme.list, g.W / 2, y + 54f, Align.CENTER, maxW = g.W - 100f, size = 18f, color = Color.LTGRAY, marquee = true)
        val active = (t.dir?.absolutePath ?: "") == MainUI.settings.themePath && t.zip == null
        g.header(if (active) "Themes (current)" else "Themes")
        g.footer(if (active) null else "APPLY", "BACK")
        g.footerStatus(index + 1, themes.size)
        if (busy) g.infoPanel("Applying theme...")
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (busy) return true
        when (b) {
            Btn.LEFT, Btn.L1 -> if (index > 0) index-- else return false
            Btn.RIGHT, Btn.R1 -> if (index < themes.size - 1) index++ else return false
            Btn.A -> if (!repeat && themes.isNotEmpty()) {
                val t = themes[index]
                busy = true
                host.io({ MainUI.themes.apply(t) }) { err ->
                    busy = false
                    io.github.marsinsanity.mainui.ui.Glyphs.clear()
                    Images.clear()
                    if (err != null) host.toast(err) else {
                        host.toast("Theme applied")
                        themes = MainUI.themes.list()
                        index = themes.indexOfFirst { (it.dir?.absolutePath ?: "") == MainUI.settings.themePath }.coerceAtLeast(0)
                    }
                }
            }
            Btn.B -> if (!repeat) close()
            else -> return false
        }
        return true
    }

    override fun onTap(x: Float, y: Float): Boolean {
        val w = host.logicalWidth
        when {
            x < w * 0.25f -> if (index > 0) index--
            x > w * 0.75f -> if (index < themes.size - 1) index++
            else -> onKey(Btn.A, false)
        }
        return true
    }
}
