package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.Apps
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.Glyphs
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.PopMenuScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

/**
 * Apps menu: MainUI's own tools (like Onion's bundled apps) followed by every
 * launchable Android app. Hidden apps get their own menu at the bottom.
 */
class AppsScene(host: SceneHost) : ListScene(host, "Apps", large = true) {
    override var aLabel: String? = "OPEN"
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "HIDE")

    private class Builtin(val label: String, val icon: String, val glyph: String, val open: () -> Unit)

    private fun builtins(): List<Builtin> = listOf(
        Builtin("Theme Switcher", "themes", "theme") { host.push(ThemeSwitcherScene(host)) },
        Builtin("Theme Downloader", "download", "theme") { host.push(ThemeStoreScene(host)) },
        Builtin("App Store", "pacman", "app") { host.push(AppStoreScene(host)) },
        Builtin("Game Switcher", "gameswitcher", "switcher") { host.push(GameSwitcherScene(host)) },
        Builtin("Search", "search", "search") { SearchFlow.start(host) },
        Builtin("Scraper", "gallery", "scraper") { host.push(ScraperScene(host, null)) },
        Builtin("Process Manager", "activity", "process") { host.push(ProcessManagerScene(host)) },
        Builtin("Emulators & Cores", "retroarch", "expert") { host.push(EmulatorsScene(host)) },
        Builtin("Tweaks", "tweaks", "setting") { host.push(SettingsScene(host)) },
    )

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ MainUI.apps.visibleApps() to MainUI.apps.hiddenApps().size }) { (apps, hiddenCount) ->
            val items = mutableListOf<UiItem>()
            for (b in builtins()) {
                items += UiItem(b.label, icon = { MainUI.themes.current.appIcon(b.icon) ?: Glyphs.get(b.glyph) },
                    sub = "MainUI", data = b, onSelect = { b.open() })
            }
            items += UiItem("Hidden apps", icon = { Glyphs.get("hidden") }, sub = "$hiddenCount hidden",
                onSelect = { host.push(HiddenAppsScene(host)) })
            for (a in apps) items += appItem(a)
            list.set(items)
        }
    }

    private fun appItem(a: Apps.AppInfo) = UiItem(a.label, icon = { MainUI.apps.icon(a.pkg, 96) }, data = a, onSelect = {
        val err = MainUI.sessions.launchApp(a.pkg, a.label, asGame = false)
        if (err != null) host.toast(err)
    })

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val a = list.current?.data as? Apps.AppInfo
        when (b) {
            Btn.Y -> {
                if (!repeat && a != null) { MainUI.library.setHidden(a.pkg, true); host.toast("${a.label} hidden"); reload() }
                return true
            }
            Btn.SELECT -> { if (!repeat && a != null) appOptions(host, a) { reload() }; return true }
            else -> return super.onKey(b, repeat)
        }
    }

    companion object {
        fun appOptions(host: SceneHost, a: Apps.AppInfo, changed: () -> Unit) {
            val hidden = MainUI.library.isHidden(a.pkg)
            val isGame = MainUI.library.isAndroidGame(a.pkg)
            host.push(PopMenuScene(host, a.label, listOf(
                UiItem(if (hidden) "Unhide" else "Hide", onSelect = { MainUI.library.setHidden(a.pkg, !hidden); changed() }),
                UiItem(if (isGame) "Remove from Android games" else "Move to Android games", onSelect = {
                    MainUI.library.setAndroidGame(a.pkg, !isGame)
                    host.toast(if (isGame) "Moved to Apps" else "Moved to Games > Android"); changed()
                }),
                UiItem("App info", onSelect = {
                    host.hostContext.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }),
                UiItem("Uninstall", onSelect = {
                    host.hostContext.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.pkg}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }),
            )))
        }
    }
}

/**
 * The hidden apps menu: launch hidden apps, unhide them (Y), or open
 * "Edit hidden apps" to hide / show any app with a toggle.
 */
class HiddenAppsScene(host: SceneHost) : ListScene(host, "Hidden apps", large = true) {
    override var aLabel: String? = "OPEN"
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "UNHIDE")

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ MainUI.apps.hiddenApps() }) { apps ->
            val items = mutableListOf(
                UiItem("Edit hidden apps...", icon = { Glyphs.get("hidden") }, sub = "show / hide any app",
                    onSelect = { host.push(AppVisibilityScene(host)) }),
            )
            if (apps.isEmpty()) items += UiItem("No hidden apps. Press Y on an app in Apps to hide it.", disabled = true)
            items += apps.map { a ->
                UiItem(a.label, icon = { MainUI.apps.icon(a.pkg, 96) }, data = a, onSelect = {
                    val err = MainUI.sessions.launchApp(a.pkg, a.label, asGame = false)
                    if (err != null) host.toast(err)
                })
            }
            list.set(items)
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val a = list.current?.data as? Apps.AppInfo
        when (b) {
            Btn.Y -> { if (!repeat && a != null) { MainUI.library.setHidden(a.pkg, false); host.toast("${a.label} visible"); reload() }; return true }
            Btn.SELECT -> { if (!repeat && a != null) AppsScene.appOptions(host, a) { reload() }; return true }
            else -> return super.onKey(b, repeat)
        }
    }
}

/** Every installed app with a "shown" toggle: the quick way to hide many apps at once. */
class AppVisibilityScene(host: SceneHost) : ListScene(host, "Edit hidden apps", large = true) {
    override var aLabel: String? = "TOGGLE"
    override val showPreview = false

    override fun onEnter() {
        host.io({ MainUI.apps.all() }) { apps ->
            list.set(apps.map { a ->
                UiItem(a.label, io.github.marsinsanity.mainui.ui.ItemType.TOGGLE, icon = { MainUI.apps.icon(a.pkg, 96) },
                    value = if (MainUI.library.isHidden(a.pkg)) 0 else 1, data = a,
                    sub = if (MainUI.library.isAndroidGame(a.pkg)) "game" else null,
                    onChange = { item -> MainUI.library.setHidden(a.pkg, item.value == 0) })
            })
        }
    }
}
