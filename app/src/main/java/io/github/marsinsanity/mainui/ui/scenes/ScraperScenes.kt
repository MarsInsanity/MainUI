package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.scraper.ScrapeState
import io.github.marsinsanity.mainui.scraper.ScreenScraper
import io.github.marsinsanity.mainui.service.ScraperService
import io.github.marsinsanity.mainui.ui.Align
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.KeyboardScene
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import io.github.marsinsanity.mainui.ui.withAlpha

/** Scraper configuration. [systems] null = every system. */
class ScraperScene(host: SceneHost, private val systems: List<String>?) :
    ListScene(host, if (systems == null) "Scraper" else "Scrape ${systems.joinToString()}") {
    override val showPreview = false

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        val s = MainUI.settings
        val devFromBuild = BuildConfig.SS_DEV_ID.isNotBlank()
        val items = mutableListOf(
            UiItem(if (ScrapeState.running) "Show progress" else "Start scraping", onSelect = {
                if (!ScrapeState.running) {
                    if (s.artSources == "ss" && !ScreenScraper.hasDevCredentials()) {
                        host.toast("Enter a ScreenScraper developer ID first, or choose free box art sources", 3000); return@UiItem
                    }
                    ScraperService.start(host.hostContext, systems)
                }
                host.push(ScraperProgressScene(host))
            }),
            multi("Box art sources", listOf(
                "ss,gametdb,libretro" to "All",
                "gametdb,libretro" to "Free only",
                "ss" to "ScreenScraper",
            ), { s.artSources }) { s.artSources = it },
            action("Username", s.ssUser.ifBlank { "(anonymous: 1 thread)" }) {
                host.push(KeyboardScene(host, "ScreenScraper username", s.ssUser) { v -> s.ssUser = v.trim(); build() })
            },
            action("Password", if (s.ssPassword.isBlank()) "not set" else "••••••") {
                host.push(KeyboardScene(host, "ScreenScraper password", "", password = true) { v -> s.ssPassword = v; build() })
            },
            multi("Media", ScreenScraper.MEDIA_TYPES, { s.ssMedia }) { s.ssMedia = it },
            multi("Region priority", listOf(
                "us,wor,eu,ss,jp" to "USA first", "eu,wor,us,ss,jp" to "Europe first", "jp,ss,wor,us,eu" to "Japan first",
                "wor,us,eu,ss,jp" to "World first",
            ), { s.ssRegions }) { s.ssRegions = it },
            multi("Language", listOf("en" to "English", "fr" to "Français", "de" to "Deutsch", "es" to "Español",
                "it" to "Italiano", "pt" to "Português", "ja" to "日本語"), { s.ssLanguage }) { s.ssLanguage = it },
            multi("Save images to", listOf("sd" to "SD card", "internal" to "Internal storage"), { s.ssImageStorage }) { s.ssImageStorage = it },
            toggle("Only games without art", { s.ssOnlyMissing }) { s.ssOnlyMissing = it },
            multi("Write metadata to", listOf("gamelist" to "gamelist.xml", "logiqx" to "Logiqx DAT",
                "both" to "Both", "none" to "Images only"), { s.ssMetaFormat }) { s.ssMetaFormat = it },
            multi("Threads", listOf("0" to "Account max", "1" to "1", "2" to "2", "4" to "4", "8" to "8", "16" to "16"),
                { s.ssThreadsOverride.toString() }) { s.ssThreadsOverride = it.toInt() },
            multi("Image size", listOf("320" to "320 px", "480" to "480 px", "640" to "640 px", "1000" to "1000 px", "0" to "Original"),
                { s.ssMaxImageWidth.toString() }) { s.ssMaxImageWidth = it.toInt() },
            action("Check account") {
                host.io({
                    runCatching { ScreenScraper.fromSettings().userInfo() }.fold(
                        { u -> "Level ${u.level}: ${u.maxThreads} threads, ${u.requestsToday}/${u.maxRequestsPerDay} today" },
                        { e -> "Failed: ${e.message?.take(80)}" })
                }) { host.toast(it, 3500) }
            },
        )
        if (!devFromBuild) {
            items += action("Developer ID", s.ssDevId.ifBlank { "required" }) {
                host.push(KeyboardScene(host, "ScreenScraper developer ID", s.ssDevId) { v -> s.ssDevId = v.trim(); build() })
            }
            items += action("Developer password", if (s.ssDevPassword.isBlank()) "required" else "••••••") {
                host.push(KeyboardScene(host, "ScreenScraper developer password", "", password = true) { v -> s.ssDevPassword = v; build() })
            }
        }
        list.set(items)
    }
}

/** Live progress of the background scraper. B leaves it running, X stops it. */
class ScraperProgressScene(host: SceneHost) : Scene(host) {
    override fun draw(g: Gfx) {
        g.redrawAfter(500) // live progress
        val st = ScrapeState
        g.background()
        g.header("Scraper")
        val x = 30f
        var y = Gfx.HEADER + 40f
        val total = st.total
        val done = st.done.get()
        g.text(st.message.ifBlank { if (st.running) "Working..." else "Idle" }, g.theme.title, g.W / 2, y, Align.CENTER, maxW = g.W - 40f, size = 26f)
        y += 50f
        // progress bar
        val bw = g.W - 60f
        g.fillRect(x, y, bw, 22f, withAlpha(android.graphics.Color.WHITE, 50), 11f)
        if (total > 0) g.fillRect(x, y, bw * done / total, 22f, g.theme.accent, 11f)
        g.text(if (total > 0) "$done / $total" else "", g.theme.list, g.W / 2, y + 11f, Align.CENTER, size = 18f)
        y += 50f
        val lines = listOf(
            "Found: ${st.found.get()}    Not found: ${st.missing.get()}    Errors: ${st.errors.get()}",
            "Already had art: ${st.skipped.get()}",
            "Threads: ${st.threads}    ${st.quota}",
            st.current,
        )
        for (l in lines) { g.text(l, g.theme.list, x, y, maxW = g.W - 60f, size = 22f); y += 34f }
        y += 6f
        for (l in st.log) { g.text(l, g.theme.list, x, y, maxW = g.W - 60f, size = 18f, alpha = 160); y += 26f; if (y > g.H - 80f) break }
        g.footer(null, "BACK", if (st.running) listOf("X" to "STOP") else emptyList())
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (repeat) return false
        when (b) {
            Btn.B, Btn.A -> close()
            Btn.X -> if (ScrapeState.running) ScraperService.stop(host.hostContext)
            else -> return false
        }
        return true
    }
}
