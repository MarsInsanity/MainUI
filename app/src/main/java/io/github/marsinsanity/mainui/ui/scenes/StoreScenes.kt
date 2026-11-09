package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.net.Uri
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.store.AppStore
import io.github.marsinsanity.mainui.store.ThemeStore
import io.github.marsinsanity.mainui.store.formatBytes
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.Glyphs
import io.github.marsinsanity.mainui.ui.Images
import io.github.marsinsanity.mainui.ui.ItemType
import io.github.marsinsanity.mainui.ui.KeyboardScene
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.PopMenuScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import java.text.DateFormat
import java.util.Date

/**
 * Theme downloader for the OnionOS theme repository: preview on the right,
 * A downloads + installs, Y downloads and applies right away.
 */
class ThemeStoreScene(host: SceneHost) : ListScene(host, "Get themes") {
    override var aLabel: String? = "GET"
    override val extraHints get() = listOf("Y" to "GET + APPLY")
    private var busy: String? = "Loading theme list..."

    override fun onEnter() = load(false)

    private fun load(refresh: Boolean) {
        busy = "Loading theme list..."
        host.io({ runCatching { ThemeStore.catalog(refresh) } }) { r ->
            busy = null
            r.onFailure { host.push(DialogScene(host, "Theme repository", "Could not load the theme list:\n${it.message}", "OK", null)) }
            r.onSuccess { list -> show(list) }
        }
    }

    private fun show(list: List<ThemeStore.Entry>) {
        this.list.set(list.map { e ->
            UiItem(e.title, sub = buildString {
                if (ThemeStore.isInstalled(e)) append("installed  ")
                append(e.author.ifEmpty { if (e.remixed) "remix" else "" })
            }.trim(), data = e,
                preview = { Images.get("themestore:${e.name}", host, 640) { ThemeStore.preview(e) } })
        })
        title = "Get themes (${list.size})"
    }

    override fun draw(g: Gfx) {
        super.draw(g)
        busy?.let { g.infoPanel(it) }
    }

    override fun activate() = get(applyAfter = false)

    private fun get(applyAfter: Boolean) {
        val e = list.current?.data as? ThemeStore.Entry ?: return
        if (busy != null) return
        val onSd = MainUI.storage.sdCard != null
        busy = "Downloading ${e.title}..."
        host.io({
            runCatching {
                ThemeStore.install(e, onSd) { done, total ->
                    busy = if (total > 0 && done >= total) "Installing ${e.title}..."
                    else "Downloading ${e.title}: ${done / 1024} KB" + if (total > 0) " / ${total / 1024} KB" else ""
                    host.invalidate()
                }
            }
        }) { r ->
            busy = null
            r.onFailure { host.toast("Download failed: ${it.message}", 3000) }
            r.onSuccess { dir ->
                if (applyAfter) {
                    val entry = MainUI.themes.list().firstOrNull { it.dir?.absolutePath == dir.absolutePath }
                    if (entry != null) host.io({ MainUI.themes.apply(entry) }) {
                        Glyphs.clear(); Images.clear(); host.toast("${e.title} applied")
                    }
                } else host.toast("${e.title} installed. Apps > Theme Switcher to use it", 3000)
                list.current?.sub = "installed  ${e.author}"
            }
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.Y && !repeat) { get(applyAfter = true); return true }
        if (b == Btn.START && !repeat) { load(true); return true }
        return super.onKey(b, repeat)
    }
}

/**
 * App store for F-Droid compatible repositories. Search (Y), categories
 * (SELECT), updates for installed apps, repository management.
 */
class AppStoreScene(host: SceneHost) : ListScene(host, "App Store", large = true) {
    override var aLabel: String? = "OPEN"
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "SEARCH")
    private var busy: String? = null
    private var query = ""
    private var category: String? = null
    private var updatesOnly = false

    override fun onEnter() {
        if (AppStore.repos().filter { it.enabled }.all { AppStore.lastRefresh(it) == 0L }) refreshAll() else rebuild()
    }

    override fun onResume() = rebuild()

    private fun refreshAll() {
        busy = "Updating repositories..."
        host.io({
            val errors = ArrayList<String>()
            val repos = AppStore.repos()
            for (r in repos.filter { it.enabled }) {
                runCatching { AppStore.refresh(r) { busy = it; host.invalidate() } }.onFailure { errors += "${r.name}: ${it.message}" }
            }
            AppStore.saveRepos(repos) // stores trusted fingerprints / names
            errors
        }) { errors ->
            busy = null
            if (errors.isNotEmpty()) host.push(DialogScene(host, "Repositories", errors.joinToString("\n").take(400), "OK", null))
            rebuild()
        }
    }

    private fun rebuild() {
        if (list.size == 0 && busy == null) busy = "Loading apps..."
        host.io({
            val all = AppStore.apps()
            val q = query.lowercase()
            all.filter { a ->
                (q.isEmpty() || a.name.lowercase().contains(q) || a.summary.lowercase().contains(q) || a.pkg.contains(q)) &&
                    (category == null || category in a.categories) &&
                    (!updatesOnly || (AppStore.installedVersion(a.pkg)?.let { v -> (a.apk?.versionCode ?: 0) > v } == true))
            } to all.size
        }) { (apps, total) ->
            if (busy == "Loading apps...") busy = null
            val items = mutableListOf(
                UiItem("Search...", icon = { Glyphs.get("search") }, sub = query.ifEmpty { null }, onSelect = { search() }),
                UiItem("Category", ItemType.ACTION, icon = { Glyphs.get("app") }, sub = category ?: "All", onSelect = { pickCategory() }),
                UiItem(if (updatesOnly) "Showing updates" else "Updates", icon = { Glyphs.get("switcher") },
                    sub = if (updatesOnly) "show all" else null, onSelect = { updatesOnly = !updatesOnly; rebuild() }),
                UiItem("Repositories", icon = { Glyphs.get("setting") }, sub = "refresh / add", onSelect = { host.push(RepoScene(host)) }),
            )
            for (a in apps.take(1500)) {
                val installed = AppStore.installedVersion(a.pkg)
                val status = when {
                    installed == null -> a.apk?.versionName ?: "incompatible"
                    (a.apk?.versionCode ?: 0) > installed -> "update"
                    else -> "installed"
                }
                items += UiItem(a.name, icon = { Images.get("appicon:${a.pkg}", host, 96) { AppStore.icon(a) } },
                    sub = status, data = a, onSelect = { host.push(AppDetailScene(host, a)) })
            }
            list.set(items)
            title = if (total == 0) "App Store" else "App Store (${apps.size})"
        }
    }

    private fun search() {
        host.push(KeyboardScene(host, "Search apps", query) { q -> query = q.trim(); rebuild() })
    }

    private fun pickCategory() {
        host.io({ AppStore.categories() }) { cats ->
            host.push(PopMenuScene(host, "Category",
                listOf(UiItem("All", onSelect = { category = null; rebuild() })) +
                    cats.map { c -> UiItem(c, onSelect = { category = c; rebuild() }) }))
        }
    }

    override fun draw(g: Gfx) {
        super.draw(g)
        busy?.let { g.infoPanel(it) }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (busy != null) return true
        when (b) {
            Btn.Y -> { if (!repeat) search(); return true }
            Btn.SELECT -> { if (!repeat) pickCategory(); return true }
            Btn.START -> { if (!repeat) refreshAll(); return true }
            else -> return super.onKey(b, repeat)
        }
    }
}

/** One app: description, version, install / update / open / uninstall. */
class AppDetailScene(host: SceneHost, private val app: AppStore.App) : ListScene(host, app.name) {
    private var busy: String? = null
    override val showPreview = true

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        val installed = AppStore.installedVersion(app.pkg)
        val apk = app.apk
        val items = mutableListOf<UiItem>()
        val preview = { Images.get("appicon:${app.pkg}", host, 256) { AppStore.icon(app) } }
        when {
            apk == null -> items += UiItem("No version for this device", disabled = true)
            installed == null -> items += UiItem("Install ${apk.versionName}", sub = sizeLabel(apk.size), preview = preview, onSelect = { install() })
            apk.versionCode > installed -> items += UiItem("Update to ${apk.versionName}", sub = sizeLabel(apk.size), preview = preview, onSelect = { install() })
            else -> items += UiItem("Installed (${apk.versionName})", disabled = true, preview = preview)
        }
        if (installed != null) {
            items += UiItem("Open", preview = preview, onSelect = {
                val err = MainUI.sessions.launchApp(app.pkg, app.name, asGame = MainUI.library.isAndroidGame(app.pkg))
                if (err != null) host.toast(err)
            })
            items += UiItem("Uninstall", preview = preview, onSelect = {
                host.hostContext.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
        }
        items += UiItem("About", sub = app.license.ifEmpty { null }, preview = preview, onSelect = {
            host.push(DialogScene(host, app.name, (app.summary + "\n\n" + app.description).trim().take(420), "OK", null))
        })
        items += UiItem(app.summary.ifEmpty { app.pkg }, disabled = true, preview = preview)
        if (app.lastUpdated > 0) items += UiItem("Updated " + DateFormat.getDateInstance().format(Date(app.lastUpdated)), disabled = true, preview = preview)
        items += UiItem(app.repo.removePrefix("https://"), disabled = true, preview = preview)
        list.set(items)
    }

    private fun sizeLabel(b: Long) = if (b > 0) formatBytes(b) else null

    private fun install() {
        if (busy != null) return
        busy = "Downloading..."
        host.io({ runCatching { AppStore.install(app) { busy = it; host.invalidate() } }.getOrElse { "Failed: ${it.message}" } }) { msg ->
            busy = null
            host.toast(msg, 3000)
            MainUI.apps.invalidate()
            build()
        }
    }

    override fun draw(g: Gfx) {
        super.draw(g)
        busy?.let { g.infoPanel(it) }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean = if (busy != null) true else super.onKey(b, repeat)
}

/** F-Droid compatible repositories: toggle, refresh, add (URL with optional ?fingerprint=), remove (Y). */
class RepoScene(host: SceneHost) : ListScene(host, "Repositories") {
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "REMOVE")
    private var busy: String? = null

    override fun onEnter() = build()

    private fun build() {
        val repos = AppStore.repos()
        val items = mutableListOf(
            UiItem("Refresh all", onSelect = { refresh() }),
            UiItem("Add repository...", onSelect = {
                host.push(KeyboardScene(host, "Repository address", "https://") { input ->
                    if (input.isBlank() || input == "https://") return@KeyboardScene
                    val r = AppStore.parseRepoInput(input)
                    val all = AppStore.repos()
                    if (all.none { it.address == r.address }) { all += r; AppStore.saveRepos(all) }
                    build(); refresh()
                })
            }),
        )
        for (r in repos) {
            val last = AppStore.lastRefresh(r)
            items += UiItem(r.name, ItemType.TOGGLE, value = if (r.enabled) 1 else 0, data = r,
                sub = if (last == 0L) "not loaded" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(last)),
                onChange = { item ->
                    val all = AppStore.repos()
                    all.firstOrNull { it.address == r.address }?.enabled = item.value == 1
                    AppStore.saveRepos(all)
                })
        }
        list.set(items)
    }

    private fun refresh() {
        busy = "Updating..."
        host.io({
            val errors = ArrayList<String>()
            val repos = AppStore.repos()
            for (r in repos.filter { it.enabled }) {
                runCatching { AppStore.refresh(r) { busy = it; host.invalidate() } }.onFailure { errors += "${r.name}: ${it.message}" }
            }
            AppStore.saveRepos(repos)
            errors
        }) { errors ->
            busy = null
            host.toast(if (errors.isEmpty()) "Repositories updated" else errors.first().take(120), 3000)
            build()
        }
    }

    override fun draw(g: Gfx) {
        super.draw(g)
        busy?.let { g.infoPanel(it) }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (busy != null) return true
        val r = list.current?.data as? AppStore.Repo
        if (b == Btn.Y && !repeat && r != null) {
            host.push(DialogScene(host, "Remove repository", "Remove ${r.name}?") {
                AppStore.saveRepos(AppStore.repos().filter { it.address != r.address }); build()
            })
            return true
        }
        return super.onKey(b, repeat)
    }
}
