package io.github.marsinsanity.mainui.store

import android.os.Build
import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import org.json.JSONArray
import org.json.JSONObject

/**
 * Updates for the installed emulators (RetroArch and the standalone ones), from the same
 * sources Apps > App Store > Emulators installs from:
 *  - an F-Droid compatible repository: its build number against the installed one, when the
 *    repository's build is signed like the installed app (another signature can't update it);
 *  - retroarch.com: the newest stable version against the installed one;
 *  - the project's GitHub releases: the release's version against the installed one; a tag
 *    that is a build number ("v12") against the installed build ("0.2.0-12" / version code 12);
 *    otherwise a release published after the app was installed.
 * Apps installed by Google Play are left to it.
 */
object EmulatorUpdates {
    private const val TAG = "MainUI.EmuUpdates"
    private const val CHECK_EVERY_MS = 24L * 60 * 60 * 1000

    enum class Source { FDROID, GITHUB, RETROARCH }

    data class Update(
        val id: String, val name: String, val pkg: String,
        val installed: String, val latest: String,
        val source: Source, val from: String,
    ) {
        fun json() = JSONObject().put("id", id).put("name", name).put("pkg", pkg).put("installed", installed)
            .put("latest", latest).put("source", source.name).put("from", from)

        companion object {
            fun of(o: JSONObject) = Update(o.getString("id"), o.getString("name"), o.getString("pkg"), o.optString("installed"),
                o.optString("latest"), Source.valueOf(o.getString("source")), o.optString("from"))
        }
    }

    // ---- version numbers -----------------------------------------------------------------------

    /**
     * The numbers in a version, without a git hash or build suffix: "v1.6.7" -> [1, 6, 7],
     * "0.2.0-3611-g1f2e3d4" -> [0, 2, 0, 3611], "2125.0 (abc1234)" -> [2125, 0].
     */
    fun numbers(v: String): List<Long> {
        var s = v.trim().removePrefix("v").removePrefix("V")
        s = s.replace(Regex("""[-+_.]g?(?=[0-9]*[a-f])[0-9a-f]{7,40}\b"""), "")      // git hashes (have a letter)
        s = s.replace(Regex("""\(.*?\)"""), "")
        return Regex("""\d+""").findAll(s).mapNotNull { it.value.toLongOrNull() }.toList()
    }

    /** True when [latest] is a higher version than [installed]; null when they can't be compared. */
    fun isNewer(latest: String, installed: String): Boolean? {
        val a = numbers(latest); val b = numbers(installed)
        if (a.isEmpty() || b.isEmpty()) return null
        // A single number (a build number "v12" or a date tag "v20250425") against a dotted
        // version ("0.2.0-12") can't be compared as versions; see [buildNumberNewer].
        if ((a.size == 1) != (b.size == 1)) return null
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * A tag that is just a build number ("v12") against the installed app: its version code, or
     * the build number its version name ends with ("0.2.0-12", Vita3K). Equal: not newer. When
     * the app's name ends with its version code (one counter on both sides), the higher number
     * wins; otherwise null (unknown, decided by release date).
     */
    fun buildNumberNewer(tag: String, installedName: String, code: Long): Boolean? {
        val t = numbers(tag).singleOrNull() ?: return null
        val last = numbers(installedName).lastOrNull()
        if (t == code || t == last) return false
        if (last == code) return t > code
        return null
    }

    // ---- checking ------------------------------------------------------------------------------

    /** Refreshes the repositories and checks every installed emulator. Call off the main thread. */
    fun check(progress: (String) -> Unit = {}): List<Update> {
        val pm = MainUI.app.packageManager
        progress("Refreshing app repositories...")
        runCatching { AppStore.refreshAll(AppStore.repos().filter { it.enabled }) {} }
        val out = ArrayList<Update>()
        val entries = EmulatorStore.entries().filter { it.installed != null }
        for ((i, e) in entries.withIndex()) {
            val pkg = e.installed ?: continue
            progress("Checking ${e.def.name} (${i + 1}/${entries.size})...")
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: continue
            val installedName = info.versionName ?: ""
            if (installer(pkg) == "com.android.vending") continue   // Google Play updates it
            runCatching { find(e, pkg, installedName, info.longVersionCode, info.lastUpdateTime) }
                .onFailure { Log.w(TAG, "${e.def.name}: ${it.message}") }
                .getOrNull()?.let { out += it }
        }
        save(out)
        return out
    }

    private fun find(e: EmulatorStore.Entry, pkg: String, installedName: String, code: Long, installedAt: Long): Update? {
        // 1. A repository with the same package, signed the same way: exact build numbers.
        e.fdroid?.takeIf { it.pkg == pkg }?.let { app ->
            val apk = app.apk ?: return@let
            val mine = AppStore.installedSigner(pkg)
            if (apk.signer != null && mine != null && !apk.signer.equals(mine, true)) return@let
            return if (apk.versionCode > code) Update(e.def.id, e.def.name, pkg, installedName, apk.versionName, Source.FDROID, app.repo) else null
        }
        // 2. RetroArch's own builds.
        if (e.buildbot && (pkg == "com.retroarch.aarch64" || pkg == "com.retroarch")) {
            val r = EmulatorStore.latestRetroArch()
            return if (isNewer(r.version, installedName) == true) Update(e.def.id, e.def.name, pkg, installedName, r.version, Source.RETROARCH, "retroarch.com") else null
        }
        // 3. GitHub releases.
        val repo = e.github ?: return null
        val r = EmulatorStore.latestGithub(repo)
        val newer = isNewer(r.version, installedName) ?: buildNumberNewer(r.version, installedName, code)
            ?: (r.published > installedAt + 3_600_000L)
        return if (newer) Update(e.def.id, e.def.name, pkg, installedName, r.version, Source.GITHUB, repo) else null
    }

    private fun installer(pkg: String): String? = runCatching {
        val pm = MainUI.app.packageManager
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(pkg).installingPackageName
        else @Suppress("DEPRECATION") pm.getInstallerPackageName(pkg)
    }.getOrNull()

    /** Downloads and installs [u] from where it was found. Returns a message for the UI. */
    fun install(u: Update, progress: (String) -> Unit): String {
        val e = EmulatorStore.entries().firstOrNull { it.def.id == u.id } ?: return "${u.name} is no longer known"
        val msg = when (u.source) {
            Source.FDROID -> e.fdroid?.let { AppStore.install(it, progress) } ?: "${u.name} is no longer in the repository"
            Source.RETROARCH -> EmulatorStore.latestRetroArch().let { EmulatorStore.install("${u.name} ${it.version}", it, progress) }
            Source.GITHUB -> EmulatorStore.latestGithub(u.from).let { EmulatorStore.install("${u.name} ${it.version}", it, progress) }
        }
        // Done (or asked Android to install it): drop it from the list unless it's still old.
        runCatching {
            val now = MainUI.app.packageManager.getPackageInfo(u.pkg, 0).versionName ?: ""
            if (now != u.installed) save(known().filter { it.id != u.id })
        }
        return msg
    }

    // ---- remembered results ------------------------------------------------------------------

    private fun save(list: List<Update>) {
        val s = MainUI.settings
        s.prefs.edit().putString("emu_updates", JSONArray().apply { list.forEach { put(it.json()) } }.toString())
            .putLong("emu_updates_checked", System.currentTimeMillis())
            .putInt("emu_updates_build", io.github.marsinsanity.mainui.BuildConfig.VERSION_CODE).apply()
    }

    /** Results from an older MainUI may have been judged by older rules: check again. */
    private fun sameBuild() = MainUI.settings.prefs.getInt("emu_updates_build", -1) == io.github.marsinsanity.mainui.BuildConfig.VERSION_CODE

    /** Updates the last check found (still installed and still at the old version). */
    fun known(): List<Update> = if (!sameBuild()) emptyList() else runCatching {
        val a = JSONArray(MainUI.settings.prefs.getString("emu_updates", "[]"))
        val pm = MainUI.app.packageManager
        (0 until a.length()).map { Update.of(a.getJSONObject(it)) }.filter { u ->
            runCatching { pm.getPackageInfo(u.pkg, 0).versionName ?: "" }.getOrNull() == u.installed
        }
    }.getOrDefault(emptyList())

    var autoCheck: Boolean
        get() = MainUI.settings.prefs.getBoolean("emu_updates_auto", true)
        set(v) { MainUI.settings.prefs.edit().putBoolean("emu_updates_auto", v).apply() }

    fun lastCheck(): Long = MainUI.settings.prefs.getLong("emu_updates_checked", 0L)

    /** Automatic checks are on and the last one was a day ago or more. */
    fun isDue(): Boolean = autoCheck && (!sameBuild() || System.currentTimeMillis() - lastCheck() >= CHECK_EVERY_MS)

}
