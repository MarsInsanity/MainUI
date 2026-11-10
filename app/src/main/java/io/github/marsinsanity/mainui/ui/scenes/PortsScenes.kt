package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.data.AndroidPorts
import io.github.marsinsanity.mainui.data.Apps
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.SystemDef
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

    /** Deletes a port file, with root where MainUI can't (the card it was written to with root). */
    private fun delete(f: File): Boolean {
        if (f.delete() || !f.exists()) return true
        if (Root.knownAvailable()) Root.exec("rm -f ${Root.q(f.path)}")
        return !f.exists()
    }

    /** [pkg] is the game running now: it isn't moved meanwhile (its session goes by its entry). */
    private fun running(host: SceneHost, label: String, pkg: String): Boolean =
        (MainUI.sessions.active?.pkg == pkg).also { if (it) host.toast("$label is running: exit it first", 2500) }

    /** The library's entries for [from] (recents, favorite) become [to]'s (null: just go). */
    private fun moveEntries(from: Game, to: Game?) {
        val lib = MainUI.library
        lib.removeRecent(from.key)
        if (lib.isFavorite(from.key)) {
            lib.toggleFavorite(from)
            if (to != null && !lib.isFavorite(to.key)) lib.toggleFavorite(to)
        }
        io.github.marsinsanity.mainui.ui.Images.invalidate("art:${from.key}")
    }

    /** Puts [label] ([pkg]) into Games > Ports (out of Apps, Hidden apps and Games > Android). */
    fun add(host: SceneHost, label: String, pkg: String, done: () -> Unit = {}) {
        if (running(host, label, pkg)) return
        host.io({
            runCatching {
                MainUI.games.addPort(label, pkg, ::write).also { f ->
                    MainUI.library.setAndroidGame(pkg, false)
                    MainUI.library.setHidden(pkg, false)
                    // Its favorite (as an Android game) is the port's now.
                    moveEntries(Game(SystemDef.ANDROID.id, null, label, pkg = pkg), Game(AndroidPorts.SYSTEM, f, f.nameWithoutExtension))
                }
            }
        }) { r ->
            r.onSuccess { host.toast("$label moved to Games > Ports") }
                .onFailure { host.toast("Couldn't add $label to Ports: ${it.message}", 3000) }
            done()
        }
    }

    /** Takes [pkg] out of Ports: back to Apps, or into Games > Android with [toAndroid]. */
    fun remove(host: SceneHost, label: String, pkg: String, toAndroid: Boolean, done: () -> Unit = {}) {
        if (running(host, label, pkg)) return
        host.io({
            val r = MainUI.games.removePort(pkg, ::delete)
            if (r.failed.isEmpty() && r.deleted.isNotEmpty()) {
                // Where it goes, said outright (an app can be an Android game or hidden as well).
                MainUI.library.setAndroidGame(pkg, toAndroid)
                MainUI.library.setHidden(pkg, false)
                val android = Game(SystemDef.ANDROID.id, null, label, pkg = pkg)
                for (f in r.deleted) moveEntries(Game(AndroidPorts.SYSTEM, f, label), if (toAndroid) android else null)
            }
            r
        }) { r ->
            host.toast(when {
                r.failed.isNotEmpty() -> "Couldn't delete ${r.failed.joinToString { it.name }}"
                r.deleted.isEmpty() -> "$label's port file wasn't found (in a folder the Ports list skips?)"
                toAndroid -> "$label moved to Games > Android"
                else -> "$label moved back to Apps"
            }, 2500)
            done()
        }
    }

    /** The Ports entry of the app [pkg] (its port file), or null when it isn't a port. Off the UI thread. */
    fun gameFor(pkg: String): Game? =
        MainUI.games.portFiles().firstOrNull { it.second == pkg }?.first?.let { Game(AndroidPorts.SYSTEM, it, it.nameWithoutExtension) }

    /** The empty Ports list: what can go there (like the Android system's list, no add row in it). */
    fun hints(sys: io.github.marsinsanity.mainui.data.GameSystem, empty: Boolean): List<UiItem> = if (!empty) emptyList() else listOfNotNull(
        UiItem("No ports yet", disabled = true),
        UiItem("Apps: SELECT on an app > Move to Ports", disabled = true),
        // PortMaster's ports (a .sh script each) are kept in the same folder; they can't run on Android yet.
        (sys.otherFiles["sh"] ?: 0).takeIf { it > 0 }?.let { UiItem("$it PortMaster ports (.sh): not on Android yet", disabled = true) },
    )
}

/** Picks an installed app ([candidates], read off the UI thread) for [pick]: Add to Ports / Android games. */
class AppPickerScene(
    host: SceneHost, title: String,
    private val candidates: () -> List<Apps.AppInfo>,
    private val pick: (Apps.AppInfo) -> Unit,
) : ListScene(host, title, large = true) {
    override var aLabel: String? = "ADD"
    override val showPreview = false

    override fun onEnter() {
        host.io({ candidates().distinctBy { it.pkg }.sortedBy { it.label.lowercase() } }) { apps ->
            list.set(if (apps.isEmpty()) listOf(UiItem("No apps to add", disabled = true)) else apps.map { a ->
                UiItem(a.label, icon = { MainUI.apps.icon(a.pkg, 96) }, data = a,
                    sub = if (MainUI.library.isAndroidGame(a.pkg)) "Android game" else null)
            })
        }
    }

    override fun activate() {
        val a = list.current?.data as? Apps.AppInfo ?: return
        close()
        pick(a)
    }

    companion object {
        /** Apps and Android games into Games > Ports (hidden apps stay hidden; ports are there already). */
        fun ports(host: SceneHost, added: () -> Unit) = AppPickerScene(host, "Add to Ports", {
            val ports = MainUI.games.portPackages()
            (MainUI.apps.visibleApps() + MainUI.apps.androidGames()).filter { it.pkg !in ports }
        }) { a -> Ports.add(host, a.label, a.pkg, added) }

        /** Apps (and ports) into Games > Android. */
        fun android(host: SceneHost, added: () -> Unit) = AppPickerScene(host, "Add to Android", {
            val ports = MainUI.games.portPackages()
            MainUI.apps.visibleApps() + MainUI.apps.all().filter { it.pkg in ports }
        }) { a ->
            if (a.pkg in MainUI.games.portPackages()) Ports.remove(host, a.label, a.pkg, toAndroid = true, done = added)
            else {
                MainUI.library.setAndroidGame(a.pkg, true); MainUI.library.setHidden(a.pkg, false)
                host.toast("${a.label} moved to Games > Android"); added()
            }
        }
    }
}

/** Android's own uninstall (it asks first) for an app or game. */
fun uninstallApp(host: SceneHost, pkg: String) {
    runCatching {
        host.hostContext.startActivity(android.content.Intent(android.content.Intent.ACTION_DELETE, android.net.Uri.parse("package:$pkg"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure { host.toast("Can't uninstall: ${it.message}") }
}

/**
 * The Emulators menu (main menu): the emulator apps, like Games > Ports for ports. Installed
 * emulators MainUI knows are here by themselves; any app can be put here from Apps (SELECT >
 * Move to Emulators) and moved back. They aren't listed in Apps.
 */
class EmulatorAppsScene(host: SceneHost) : ListScene(host, "Emulators", large = true) {
    override var aLabel: String? = "OPEN"
    override val showPreview = false

    override fun onEnter() = reload()
    override fun onResume() = reload()

    private fun reload() {
        host.io({ MainUI.apps.emulatorApps() }) { apps ->
            list.set(if (apps.isEmpty()) listOf(
                UiItem("No emulators yet", disabled = true),
                UiItem("App Store > Emulators gets them", disabled = true),
                UiItem("Apps: SELECT on an app > Move to Emulators", disabled = true),
            ) else apps.map { a -> UiItem(a.label, icon = { MainUI.apps.icon(a.pkg, 96) }, data = a) }, keepIndex = true)
        }
    }

    override fun activate() {
        val a = list.current?.data as? Apps.AppInfo ?: return
        MainUI.sessions.launchApp(a.pkg, a.label, asGame = false)?.let { host.toast(it) }
    }

    override fun onKey(b: io.github.marsinsanity.mainui.ui.Btn, repeat: Boolean): Boolean {
        val a = list.current?.data as? Apps.AppInfo
        if (b == io.github.marsinsanity.mainui.ui.Btn.SELECT) {
            if (!repeat && a != null) host.push(io.github.marsinsanity.mainui.ui.PopMenuScene(host, a.label, listOf(
                UiItem("Move back to Apps", onSelect = { MainUI.library.setEmulatorApp(a.pkg, false); host.toast("${a.label} moved back to Apps"); reload() }),
                UiItem("Hide", onSelect = { MainUI.library.setHidden(a.pkg, true); host.toast("${a.label} hidden"); reload() }),
                UiItem("App info", onSelect = {
                    runCatching { host.hostContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${a.pkg}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }),
                UiItem("Uninstall emulator", onSelect = { uninstallApp(host, a.pkg) }),
            )))
            return true
        }
        return super.onKey(b, repeat)
    }
}
