package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.net.Uri
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.store.Updater
import io.github.marsinsanity.mainui.store.formatBytes
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.BusyScene
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

/** Settings > Updates: MainUI builds from the project's GitHub releases. */
class UpdateScene(host: SceneHost) : ListScene(host, "Updates") {
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "CHECK")

    private var newest: Updater.Update? = null
    private var status: String? = null      // null = checked; otherwise "Checking..." or an error
    private var checked = false

    override fun onEnter() { build(); check() }
    override fun onResume() = build()

    private fun check() {
        status = "Checking GitHub..."
        build()
        host.io({ runCatching { Updater.check() } }) { r ->
            r.onSuccess { newest = it; status = null; checked = true }
            r.onFailure { status = "Could not check: ${it.message ?: it.javaClass.simpleName}" }
            build()
        }
    }

    private fun build() {
        val items = mutableListOf<UiItem>()
        items += UiItem("Installed", sub = BuildConfig.VERSION_NAME + if (BuildConfig.DEBUG) " (debug build)" else "")
        val n = newest
        when {
            status != null -> items += UiItem(status!!.take(80), sub = null)
            n != null && n.versionCode > Updater.installed -> items += UiItem("Install ${n.versionName}",
                sub = listOf(formatBytes(n.size).takeIf { n.size > 0 }, n.published.ifEmpty { null }).filterNotNull().joinToString("  "),
                onSelect = { confirm(n) })
            checked && n != null -> items += UiItem("Up to date", sub = "newest: ${n.versionName}")
            checked -> items += UiItem("No builds published yet", sub = null)
        }
        items += toggle("Check automatically", { MainUI.settings.updateAutoCheck }) { MainUI.settings.updateAutoCheck = it }
        items += action("Releases page", "github.com/${Updater.repo}") {
            runCatching {
                host.hostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${Updater.repo}/releases"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { host.toast("No browser installed") }
        }
        list.set(items)
    }

    private fun confirm(u: Updater.Update) {
        // Release notes are the commit message: its title and first points (Markdown marks dropped).
        val notes = u.notes.lines().map { it.trim().replace("`", "").replace("**", "") }
            .filter { it.isNotEmpty() && !it.startsWith("Built from") && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
            .take(3).joinToString("\n").take(200)
        val how = if (Root.knownAvailable()) "MainUI restarts by itself after installing." else "Android will ask you to confirm the installation."
        host.push(DialogScene(host, "Update to ${u.versionName}", listOf(notes, how).filter { it.isNotBlank() }.joinToString("\n\n"),
            "INSTALL", "CANCEL") { install(u) })
    }

    private fun install(u: Updater.Update) {
        val busy = BusyScene(host, "Downloading MainUI ${u.versionName}...")
        host.push(busy)
        host.io({ runCatching { Updater.install(u) { msg -> busy.text = msg; host.invalidate() } } }) { r ->
            host.pop(busy)
            val msg = r.getOrElse { it.message ?: "Update failed" }
            if (msg != "Confirm the installation") host.push(DialogScene(host, "Update", msg, "OK", null))
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.Y && !repeat) { check(); return true }
        return super.onKey(b, repeat)
    }
}
