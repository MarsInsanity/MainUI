package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.data.AndroidPorts
import io.github.marsinsanity.mainui.data.Apps
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import java.io.File
import java.io.IOException

/**
 * The Ports system's Android apps (unofficial ports: an Android build of a PC game). An app is a
 * port when a "<Name>.android" file in a rom root's ports folder names it (see [AndroidPorts]):
 * it is listed in Games > Ports, not in Apps or Games > Android.
 */
object Ports {
    /** Writes a port file, with root where MainUI can't write (some SD cards). */
    private fun write(f: File, text: String) {
        try {
            f.parentFile?.mkdirs()
            f.writeText(text)
        } catch (e: Exception) {
            if (!Root.knownAvailable()) throw e
            f.parentFile?.let { Root.exec("mkdir -p ${Root.q(it.path)}") }
            val w = Root.writeFile(f.path, text)
            if (!w.ok) throw IOException(w.error ?: "could not write ${f.name}")
        }
    }

    /** Puts [label] ([pkg]) into Games > Ports (out of Apps and Games > Android). */
    fun add(host: SceneHost, label: String, pkg: String, done: () -> Unit = {}) {
        host.io({
            runCatching {
                MainUI.games.addPort(label, pkg, ::write).also { MainUI.library.setAndroidGame(pkg, false) }
            }
        }) { r ->
            r.onSuccess { host.toast("$label moved to Games > Ports") }
                .onFailure { host.toast("Couldn't add $label to Ports: ${it.message}", 3000) }
            done()
        }
    }

    /** Takes [pkg] out of Ports: back to Apps, or into Games > Android with [toAndroid]. */
    fun remove(host: SceneHost, label: String, pkg: String, toAndroid: Boolean, done: () -> Unit = {}) {
        host.io({
            val failed = MainUI.games.removePort(pkg)
            if (failed.isEmpty() && toAndroid) MainUI.library.setAndroidGame(pkg, true)
            failed
        }) { failed ->
            host.toast(when {
                failed.isNotEmpty() -> "Couldn't delete ${failed.joinToString { it.name }}"
                toAndroid -> "$label moved to Games > Android"
                else -> "$label moved back to Apps"
            }, 2500)
            done()
        }
    }

    /** The empty Ports list (or the end of it): what can go there. */
    fun hints(sys: io.github.marsinsanity.mainui.data.GameSystem, host: SceneHost, empty: Boolean, added: () -> Unit): List<UiItem> = listOfNotNull(
        if (empty) UiItem("No ports yet", disabled = true) else null,
        UiItem("Add an Android app...", onSelect = { host.push(PortAppPickerScene(host, added)) }),
        // PortMaster's ports (a .sh script each) are kept in the same folder; they can't run on Android yet.
        (sys.otherFiles["sh"] ?: 0).takeIf { empty && it > 0 }?.let { UiItem("$it PortMaster ports (.sh): not on Android yet", disabled = true) },
    )
}

/** Picks an installed app to put into Games > Ports. */
class PortAppPickerScene(host: SceneHost, private val added: () -> Unit) : ListScene(host, "Add to Ports", large = true) {
    override var aLabel: String? = "ADD"
    override val showPreview = false

    override fun onEnter() {
        host.io({
            // Apps and Android games (hidden apps stay hidden; ports are already there).
            val ports = MainUI.games.portPackages()
            (MainUI.apps.visibleApps() + MainUI.apps.androidGames()).filter { it.pkg !in ports }
                .distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
        }) { apps ->
            list.set(if (apps.isEmpty()) listOf(UiItem("No apps to add", disabled = true)) else apps.map { a ->
                UiItem(a.label, icon = { MainUI.apps.icon(a.pkg, 96) }, data = a,
                    sub = if (MainUI.library.isAndroidGame(a.pkg)) "Android game" else null)
            })
        }
    }

    override fun activate() {
        val a = list.current?.data as? Apps.AppInfo ?: return
        close()
        Ports.add(host, a.label, a.pkg, added)
    }
}
