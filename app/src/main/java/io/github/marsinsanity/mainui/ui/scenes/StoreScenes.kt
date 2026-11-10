package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.net.Uri
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.store.AppStore
import io.github.marsinsanity.mainui.store.ThemeStore
import io.github.marsinsanity.mainui.store.formatBytes
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.BusyScene
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
        // Which themes are installed is worked out here too: one listing of each Themes folder,
        // not a storage lookup per catalog entry on the UI thread.
        host.io({ runCatching { ThemeStore.catalog(refresh) to ThemeStore.installedNames() } }) { r ->
            busy = null
            r.onFailure { host.push(DialogScene(host, "Theme repository", "Could not load the theme list:\n${it.message}", "OK", null)) }
            r.onSuccess { (list, installed) -> show(list, installed) }
        }
    }

    private fun show(list: List<ThemeStore.Entry>, installed: Set<String>) {
        this.list.set(list.map { e ->
            UiItem(e.title, sub = buildString {
                if (e.name.lowercase() in installed) append("installed  ")
                else if (e.featured) append("featured  ")
                append(e.author.ifEmpty { if (e.remixed) "remix" else "" })
            }.trim(), data = e,
                preview = { Images.get("themestore:${e.name}", host, 640, network = true) { ThemeStore.preview(e) } })
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
            val repos = AppStore.repos()
            val errors = AppStore.refreshAll(repos.filter { it.enabled }) { busy = it; host.invalidate() }
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
            // Installed versions in one go (not a package lookup per app on the UI thread).
            val versions = AppStore.installedVersions()
            Triple(all.filter { a ->
                (q.isEmpty() || a.name.lowercase().contains(q) || a.summary.lowercase().contains(q) || a.pkg.contains(q)) &&
                    (category == null || category in a.categories) &&
                    (!updatesOnly || (versions[a.pkg]?.let { v -> (a.apk?.versionCode ?: 0) > v } == true))
            }, all.size, versions)
        }) { (apps, total, versions) ->
            if (busy == "Loading apps...") busy = null
            val items = mutableListOf(
                UiItem("Search...", icon = { Glyphs.get("search") }, sub = query.ifEmpty { null }, onSelect = { search() }),
                UiItem("Emulators", icon = { Glyphs.get("game") }, sub = "RetroArch, PPSSPP, Azahar...", onSelect = { host.push(EmulatorStoreScene(host)) }),
                UiItem("Category", ItemType.ACTION, icon = { Glyphs.get("app") }, sub = category ?: "All", onSelect = { pickCategory() }),
                UiItem(if (updatesOnly) "Showing updates" else "Updates", icon = { Glyphs.get("switcher") },
                    sub = if (updatesOnly) "show all" else null, onSelect = { updatesOnly = !updatesOnly; rebuild() }),
                UiItem("Repositories", icon = { Glyphs.get("setting") }, sub = "refresh / add", onSelect = { host.push(RepoScene(host)) }),
            )
            for (a in apps.take(1500)) {
                val installed = versions[a.pkg]
                val status = when {
                    installed == null -> a.apk?.versionName ?: "incompatible"
                    (a.apk?.versionCode ?: 0) > installed -> "update"
                    else -> "installed"
                }
                items += UiItem(a.name, icon = { Images.get("appicon:${a.pkg}", host, 96, network = true) { AppStore.icon(a) } },
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
        val preview = { Images.get("appicon:${app.pkg}", host, 256, network = true) { AppStore.icon(app) } }
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
            val repos = AppStore.repos()
            val errors = AppStore.refreshAll(repos.filter { it.enabled }) { busy = it; host.invalidate() }
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

/**
 * App Store > Emulators: every emulator MainUI can launch, with what it runs
 * and where it comes from (installed / F-Droid / GitHub / retroarch.com /
 * Play Store / website). A opens the emulator's page.
 */
class EmulatorStoreScene(host: SceneHost) : ListScene(host, "Emulators", large = true) {
    override var aLabel: String? = "OPEN"
    override val showPreview = false
    private var busy: String? = "Loading emulators..."

    override fun onEnter() = load()
    override fun onResume() = load()

    private fun load() {
        host.io({ io.github.marsinsanity.mainui.store.EmulatorStore.entries() }) { entries ->
            busy = null
            list.set(entries.map { e ->
                UiItem(e.def.name, icon = { emuIcon(host, e) },
                    sub = listOf(e.systems.takeIf { it.isNotBlank() }, e.source).filterNotNull().joinToString("  ·  "),
                    data = e, onSelect = { host.push(EmulatorDetailScene(host, e.def.id)) })
            }, false)
            title = "Emulators (${entries.count { it.installed != null }}/${entries.size} installed)"
        }
    }

    override fun draw(g: Gfx) {
        super.draw(g)
        busy?.let { g.infoPanel(it) }
    }

    companion object {
        fun emuIcon(host: SceneHost, e: io.github.marsinsanity.mainui.store.EmulatorStore.Entry) =
            e.installed?.let { MainUI.apps.icon(it, 96) }
                ?: e.fdroid?.let { a -> Images.get("appicon:${a.pkg}", host, 96, network = true) { AppStore.icon(a) } }
                ?: Glyphs.get("game")
    }
}

/** One emulator: install it from the best source, or open its store page / website. */
class EmulatorDetailScene(host: SceneHost, private val id: String) : ListScene(host, "Emulator", large = true) {
    private var busy: String? = null
    override val showPreview = false

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun build() {
        host.io({ io.github.marsinsanity.mainui.store.EmulatorStore.entries().firstOrNull { it.def.id == id } }) { e ->
            if (e == null) { close(); return@io }
            title = e.def.name
            val preview = { EmulatorStoreScene.emuIcon(host, e) }
            val items = mutableListOf<UiItem>()
            items += UiItem(e.def.name, icon = preview, sub = e.systems.ifBlank { null }, disabled = true)
            if (e.installed != null) {
                val v = runCatching { host.hostContext.packageManager.getPackageInfo(e.installed, 0).versionName }.getOrNull()
                items += UiItem("Installed" + (v?.let { " ($it)" } ?: ""), sub = e.installed, preview = preview, disabled = true)
                items += UiItem("Open", preview = preview, onSelect = {
                    val err = MainUI.sessions.launchApp(e.installed, e.def.name, asGame = false)
                    if (err != null) host.toast(err)
                })
            }
            e.fdroid?.let { app ->
                val apk = app.apk ?: return@let
                val repo = repoName(app.repo)
                val have = e.installed?.takeIf { it == app.pkg }?.let { AppStore.installedVersion(it) }
                val mySigner = e.installed?.takeIf { it == app.pkg }?.let { AppStore.installedSigner(it) }
                when {
                    have == null -> items += UiItem("Install from $repo", sub = apk.versionName, preview = preview,
                        onSelect = { run("Downloading...") { AppStore.install(app) { busy = it; host.invalidate() } } })
                    // Installed from somewhere else (retroarch.com, Play...): Android refuses to mix signatures.
                    apk.signer != null && mySigner != null && !apk.signer.equals(mySigner, true) ->
                        items += UiItem("$repo has ${apk.versionName}", sub = "other signature: uninstall first", preview = preview, disabled = true)
                    apk.versionCode > have -> items += UiItem("Update from $repo", sub = apk.versionName, preview = preview,
                        onSelect = { run("Downloading...") { AppStore.install(app) { busy = it; host.invalidate() } } })
                    else -> {}
                }
            }
            if (e.buildbot) items += UiItem((if (e.installed != null) "Update from" else "Download from") + " retroarch.com", sub = "latest stable",
                preview = preview, onSelect = {
                    run("Checking retroarch.com...") {
                        // The build of the installed package (RetroArch.apk for com.retroarch, ...), never a second RetroArch.
                        val r = io.github.marsinsanity.mainui.store.EmulatorStore.latestRetroArch(e.installed)
                        io.github.marsinsanity.mainui.store.EmulatorStore.install("RetroArch ${r.version}", r, e.installed) { busy = it; host.invalidate() }
                    }
                })
            e.github?.let { repo ->
                // An update only for the package the repository publishes; with a fork / other build
                // installed it is a download of the original beside it.
                val updates = e.installed != null && e.installed == e.def.packages.first()
                items += UiItem((if (updates) "Update from" else "Download from") + " GitHub", sub = repo, preview = preview, onSelect = {
                    run("Checking GitHub...") {
                        val r = io.github.marsinsanity.mainui.store.EmulatorStore.latestGithub(repo)
                        io.github.marsinsanity.mainui.store.EmulatorStore.install("${e.def.name} ${r.version}", r, if (updates) e.installed else null) { busy = it; host.invalidate() }
                    }
                })
            }
            e.url?.let { url ->
                items += UiItem("Download", sub = url.substringAfterLast('/'), preview = preview, onSelect = {
                    run("Downloading...") {
                        io.github.marsinsanity.mainui.store.EmulatorStore.install(e.def.name,
                            io.github.marsinsanity.mainui.store.EmulatorStore.Release("", url.substringAfterLast('/'), url, -1, null)) { busy = it; host.invalidate() }
                    }
                })
            }
            e.play?.let { pkg -> items += UiItem("Open in Google Play", sub = pkg, preview = preview, onSelect = { openPlay(pkg) }) }
            e.page?.let { url -> items += UiItem("Open website", sub = url.removePrefix("https://").removeSuffix("/"), preview = preview, onSelect = { openUrl(url) }) }
            if (e.installed != null) items += UiItem("Uninstall", preview = preview, onSelect = {
                host.hostContext.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${e.installed}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            })
            e.note?.let { items += UiItem(it, preview = preview, disabled = true) }
            if (items.none { it.onSelect != null && !it.disabled }) items += UiItem("No download source known", preview = preview, disabled = true)
            list.set(items, false)
            // Start on the first thing that can be done.
            list.active = items.indexOfFirst { !it.disabled && it.onSelect != null }.coerceAtLeast(0)
        }
    }

    private fun repoName(address: String) = AppStore.repos().firstOrNull { it.address == address }?.name?.ifBlank { null } ?: "F-Droid"

    private fun run(label: String, work: () -> String) {
        if (busy != null) return
        busy = label
        host.io({ runCatching(work).getOrElse { "Failed: ${it.message}" } }) { msg ->
            busy = null
            host.toast(msg, 3500)
            MainUI.apps.invalidate()
            MainUI.worker.execute { MainUI.emulators.refresh() }
            build()
        }
    }

    private fun openPlay(pkg: String) {
        val ctx = host.hostContext
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(market) }.onFailure { openUrl("https://play.google.com/store/apps/details?id=$pkg") }
    }

    private fun openUrl(url: String) {
        runCatching { host.hostContext.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { host.toast("No browser installed. Address: $url", 5000) }
    }

    override fun draw(g: Gfx) {
        super.draw(g)
        busy?.let { g.infoPanel(it) }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean = if (busy != null) true else super.onKey(b, repeat)
}

/** Settings > Emulators & cores > Emulator updates: newer builds of the installed emulators. */
class EmulatorUpdatesScene(host: SceneHost) : ListScene(host, "Emulator updates") {
    override val showPreview = false
    override val extraHints get() = listOf("Y" to "CHECK")
    private var status: String? = null

    override fun onEnter() {
        build()
        // Check right away when the last check is old or never happened.
        if (System.currentTimeMillis() - io.github.marsinsanity.mainui.store.EmulatorUpdates.lastCheck() > 3_600_000L) check()
    }
    override fun onResume() = build()

    private fun check() {
        if (status != null) return
        status = "Checking..."
        build()
        host.io({ runCatching { io.github.marsinsanity.mainui.store.EmulatorUpdates.check { status = it; host.invalidate() } } }) { r ->
            status = r.exceptionOrNull()?.let { "Could not check: ${it.message}" }
            build()
        }
    }

    private fun build() {
        val eu = io.github.marsinsanity.mainui.store.EmulatorUpdates
        val ups = eu.known()
        val items = mutableListOf<UiItem>()
        status?.let { items += UiItem(it.take(70), disabled = true) }
        if (status == null) {
            val last = eu.lastCheck()
            items += UiItem(when {
                last == 0L -> "Not checked yet"
                ups.isEmpty() && eu.lastFailures() > 0 -> "Could not check ${eu.lastFailures()} emulator${if (eu.lastFailures() > 1) "s" else ""}"
                ups.isEmpty() -> "All emulators are up to date"
                else -> "${ups.size} update${if (ups.size > 1) "s" else ""} available"
            }, sub = if (last > 0) android.text.format.DateUtils.getRelativeTimeSpanString(last).toString() else null,
                onSelect = { check() })
        }
        if (ups.size > 1 && status == null) items += UiItem("Update all", sub = ups.joinToString(", ") { it.name }.take(40),
            onSelect = { installAll(ups) })
        for (u in ups) items += UiItem("${u.name}: ${u.installed.ifEmpty { "?" }} > ${u.latest}", sub = when (u.source) {
            io.github.marsinsanity.mainui.store.EmulatorUpdates.Source.FDROID -> "F-Droid repo"
            io.github.marsinsanity.mainui.store.EmulatorUpdates.Source.GITHUB -> "GitHub"
            io.github.marsinsanity.mainui.store.EmulatorUpdates.Source.RETROARCH -> "retroarch.com"
        }, onSelect = { confirm(u) })
        items += UiItem("Check automatically", ItemType.TOGGLE, value = if (eu.autoCheck) 1 else 0, sub = "daily",
            onChange = { eu.autoCheck = it.value == 1 })
        list.set(items)
    }

    private fun confirm(u: io.github.marsinsanity.mainui.store.EmulatorUpdates.Update) {
        host.push(DialogScene(host, "Update ${u.name}", "${u.installed.ifEmpty { "?" }} > ${u.latest}\nfrom ${u.from}", "UPDATE", "CANCEL") {
            installAll(listOf(u))
        })
    }

    private fun installAll(ups: List<io.github.marsinsanity.mainui.store.EmulatorUpdates.Update>) {
        val busy = BusyScene(host, "Updating...")
        host.push(busy)
        host.io({
            ups.map { u ->
                u.name + ": " + runCatching {
                    io.github.marsinsanity.mainui.store.EmulatorUpdates.install(u) { busy.text = it; host.invalidate() }
                }.getOrElse { it.message ?: "failed" }
            }
        }) { results ->
            host.pop(busy)
            build()
            if (results.size == 1 && results[0].endsWith("Confirm the installation")) return@io
            host.push(DialogScene(host, "Emulator updates", results.joinToString("\n").take(360), "OK", null))
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.Y && !repeat) { check(); return true }
        return super.onKey(b, repeat)
    }
}
