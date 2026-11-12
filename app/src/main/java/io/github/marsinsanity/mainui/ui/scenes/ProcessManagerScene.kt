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
            list.set(apps.map { Processes.row(it) })
        }
    }

    private fun scan(): List<RunningApps.App>? = Processes.scan(host.hostContext)

    override fun activate() {
        val p = list.current?.data as? RunningApps.App ?: return
        host.io({ Processes.switchTo(host.hostContext, p) }) { ok ->
            if (!ok) host.toast("Cannot switch to ${MainUI.apps.label(p.pkg)}") else if (overlayMode) host.finishHost()
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val p = list.current?.data as? RunningApps.App
        when (b) {
            Btn.X -> {
                if (!repeat && p != null) host.push(DialogScene(host, "Kill app",
                    "Force stop ${MainUI.apps.label(p.pkg)}?\nUnsaved progress will be lost.") {
                    host.io({ Processes.kill(p) }) { ok -> host.toast(if (ok) "Stopped" else "Failed"); reload() }
                })
                return true
            }
            Btn.Y -> { if (!repeat) reload(); return true }
            Btn.START -> {
                if (!repeat) host.push(DialogScene(host, "Kill all", "Force stop every app MainUI launched?") {
                    host.io({
                        // Only what the list would offer (never Play Store, the keyboard, an
                        // accessibility service...), plus the running game; nothing without root.
                        if (Root.available()) {
                            val launched = MainUI.sessions.launchedPackages.toSet()
                            val keep = io.github.marsinsanity.mainui.data.RunningApps.killable(host.hostContext, launched)
                            val game = MainUI.sessions.active?.pkg
                            // Running ones only: stopping one that isn't drops its alarms and jobs.
                            val running = Root.exec("ps -A -o NAME").out.lines().map { it.trim().substringBefore(':') }.toSet()
                            // (The game too when MainUI restarted since it launched it: it isn't in the list then.)
                            (launched + listOfNotNull(game)).filter { it in running && (keep(it) || it == game) }.forEach { Root.forceStop(it) }
                            MainUI.sessions.endActive()
                        }
                    }) { reload() }
                })
                return true
            }
            else -> return super.onKey(b, repeat)
        }
    }
}

/** What the process manager page and the hold-Back popup share: the list, switching, stopping. */
object Processes {
    /** Running apps you opened, or null without root (Android hides other apps' processes). */
    fun scan(ctx: android.content.Context): List<RunningApps.App>? {
        if (!Root.available()) return null
        val r = Root.exec("ps -A -o PID,USER,RSS,%CPU,NAME")
        if (!r.ok) return null
        val launched = MainUI.sessions.launchedPackages.toSet()
        val recents = RunningApps.recentPackages(Root.exec("dumpsys activity recents").out)
        return RunningApps.parse(r.lines, RunningApps.killable(ctx, recents + launched), launched, MainUI.sessions.active?.pkg)
    }

    /** Brings [p] to the front (the game being played comes back where it was). */
    fun switchTo(ctx: android.content.Context, p: RunningApps.App): Boolean {
        val s = MainUI.sessions.active
        if (s != null && s.pkg == p.pkg) return MainUI.sessions.resume()
        val intent = MainUI.emulators.resumeIntent(p.pkg, null) ?: return false
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    /** Force stops [p] (root); ends the session when it was the game being played. Off the UI thread. */
    fun kill(p: RunningApps.App): Boolean {
        val ok = Root.forceStop(p.pkg)
        if (MainUI.sessions.active?.pkg == p.pkg) MainUI.sessions.endActive()
        return ok
    }

    fun row(p: RunningApps.App, iconPx: Int = 96): UiItem {
        val tag = when { p.isSession -> "PLAYING  "; p.fromMainUI -> "MainUI  "; else -> "" }
        return UiItem(MainUI.apps.label(p.pkg), icon = { MainUI.apps.icon(p.pkg, iconPx) }, data = p,
            sub = "$tag${p.rssKb / 1024} MB  ${"%.1f".format(p.cpu)}%")
    }
}
