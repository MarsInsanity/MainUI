package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.data.RunningApps
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

/**
 * MainUI's process viewer / switcher / killer: the apps that are running, one row per app.
 * The game being played and apps started from MainUI come first. Only apps you open yourself
 * are listed ([RunningApps.killable]); Android's own processes are never offered.
 * Needs root to see other apps' processes (Android hides them otherwise).
 *
 * [overlayMode]: shown as a popup over another app (hold Back, see OverlayService); switching
 * to an app or pressing B closes the popup.
 */
class ProcessManagerScene(host: SceneHost, private val overlayMode: Boolean = false) : ListScene(host, "Processes", large = true) {
    override var aLabel: String? = "SWITCH"
    override val showPreview = false
    override val extraHints get() = listOf("X" to "KILL", "Y" to "REFRESH")

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ scan() }) { apps ->
            if (apps == null) {
                list.set(listOf(UiItem("Root access is required", disabled = true),
                    UiItem("Grant root to MainUI in Magisk, then press Y", disabled = true)))
                return@io
            }
            if (apps.isEmpty()) {
                list.set(listOf(UiItem("No apps running", disabled = true)))
                return@io
            }
            list.set(apps.map { p ->
                val tag = when { p.isSession -> "PLAYING  "; p.fromMainUI -> "MainUI  "; else -> "" }
                UiItem(MainUI.apps.label(p.pkg), icon = { MainUI.apps.icon(p.pkg, 96) }, data = p,
                    sub = "$tag${p.rssKb / 1024} MB  ${"%.1f".format(p.cpu)}%")
            })
        }
    }

    private fun scan(): List<RunningApps.App>? {
        if (!Root.available()) return null
        val r = Root.exec("ps -A -o PID,USER,RSS,%CPU,NAME")
        if (!r.ok) return null
        val launched = MainUI.sessions.launchedPackages.toSet()
        val recents = RunningApps.recentPackages(Root.exec("dumpsys activity recents").out)
        return RunningApps.parse(r.lines, RunningApps.killable(host.hostContext, recents + launched),
            launched, MainUI.sessions.active?.pkg)
    }

    override fun activate() {
        val p = list.current?.data as? RunningApps.App ?: return
        val s = MainUI.sessions.active
        if (s != null && s.pkg == p.pkg) {
            MainUI.sessions.resume()
        } else {
            val intent = MainUI.emulators.resumeIntent(p.pkg, null)
            if (intent == null) { host.toast("Cannot switch to ${MainUI.apps.label(p.pkg)}"); return }
            runCatching { host.hostContext.startActivity(intent) }
        }
        if (overlayMode) host.finishHost()
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val p = list.current?.data as? RunningApps.App
        when (b) {
            Btn.X -> {
                if (!repeat && p != null) host.push(DialogScene(host, "Kill app",
                    "Force stop ${MainUI.apps.label(p.pkg)}?\nUnsaved progress will be lost.") {
                    host.io({
                        val ok = Root.forceStop(p.pkg)
                        if (MainUI.sessions.active?.pkg == p.pkg) MainUI.sessions.endActive()
                        ok
                    }) { ok -> host.toast(if (ok) "Stopped" else "Failed"); reload() }
                })
                return true
            }
            Btn.Y -> { if (!repeat) reload(); return true }
            Btn.START -> {
                if (!repeat) host.push(DialogScene(host, "Kill all", "Force stop every app MainUI launched?") {
                    host.io({
                        MainUI.sessions.launchedPackages.toList().forEach { Root.forceStop(it) }
                        MainUI.sessions.endActive()
                    }) { reload() }
                })
                return true
            }
            else -> return super.onKey(b, repeat)
        }
    }
}
