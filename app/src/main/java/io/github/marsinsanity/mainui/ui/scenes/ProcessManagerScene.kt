package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

/**
 * MainUI's process viewer / switcher / killer. Games and apps started from
 * MainUI are listed first; everything else running as an app follows.
 * Needs root to see other apps' processes (Android hides them otherwise).
 */
class ProcessManagerScene(host: SceneHost) : ListScene(host, "Processes", large = true) {
    override var aLabel: String? = "SWITCH"
    override val showPreview = false
    override val extraHints get() = listOf("X" to "KILL", "Y" to "REFRESH")

    data class Proc(val pid: Int, val pkg: String, val name: String, val rssKb: Long, val cpu: String, val fromMainUI: Boolean, val isSession: Boolean)

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ scan() }) { procs ->
            if (procs == null) {
                list.set(listOf(UiItem("Root access is required", disabled = true),
                    UiItem("Grant root to MainUI in Magisk, then press Y", disabled = true)))
                return@io
            }
            list.set(procs.map { p ->
                val label = MainUI.apps.label(p.pkg).let { if (p.name != p.pkg) "$it (${p.name.substringAfter(':')})" else it }
                val tag = when { p.isSession -> "PLAYING  "; p.fromMainUI -> "MainUI  "; else -> "" }
                UiItem(label, icon = { MainUI.apps.icon(p.pkg, 96) }, data = p,
                    sub = "$tag${p.rssKb / 1024} MB  ${p.cpu}%")
            })
        }
    }

    /** Parses `ps` output; app processes run as u0_aNNN users. */
    private fun scan(): List<Proc>? {
        if (!Root.available()) return null
        val r = Root.exec("ps -A -o PID,USER,RSS,%CPU,NAME")
        if (!r.ok) return null
        val launched = MainUI.sessions.launchedPackages
        val sessionPkg = MainUI.sessions.active?.pkg
        val self = host.hostContext.packageName
        val out = ArrayList<Proc>()
        for (line in r.lines.drop(1)) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 5) continue
            val user = parts[1]
            if (!user.startsWith("u0_a") && !user.startsWith("u10_a")) continue
            val name = parts[4]
            val pkg = name.substringBefore(':')
            if (pkg == self || !pkg.contains('.')) continue
            if (!MainUI.apps.isInstalled(pkg)) continue
            out += Proc(parts[0].toIntOrNull() ?: continue, pkg, name, parts[2].toLongOrNull() ?: 0, parts[3],
                pkg in launched, pkg == sessionPkg)
        }
        return out.sortedWith(compareBy<Proc>({ !it.isSession }, { !it.fromMainUI }, { -it.rssKb }))
    }

    override fun activate() {
        val p = list.current?.data as? Proc ?: return
        val s = MainUI.sessions.active
        if (s != null && s.pkg == p.pkg) { MainUI.sessions.resume(); return }
        val intent = MainUI.emulators.resumeIntent(p.pkg, null)
        if (intent == null) host.toast("Cannot switch to ${p.pkg}") else host.hostContext.startActivity(intent)
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        val p = list.current?.data as? Proc
        when (b) {
            Btn.X -> {
                if (!repeat && p != null) host.push(DialogScene(host, "Kill process",
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
