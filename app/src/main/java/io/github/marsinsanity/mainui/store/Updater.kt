package io.github.marsinsanity.mainui.store

import android.content.pm.PackageManager
import android.os.Build
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Updates MainUI from the GitHub releases the Build workflow publishes
 * (`MainUI-0.1.<versionCode>.apk`). The version code is the commit count, so a
 * higher number is always a later build.
 */
object Updater {
    data class Update(
        val versionCode: Int,
        val versionName: String,
        val tag: String,
        val url: String,
        val size: Long,
        /** SHA-256 GitHub computed for the asset (null on old releases). */
        val sha256: String?,
        val notes: String,
        val published: String,
    )

    private val assetName = Regex("""^MainUI-(\d+\.\d+\.(\d+))\.apk$""")
    private const val CHECK_EVERY_MS = 6L * 60 * 60 * 1000

    val repo: String get() = BuildConfig.UPDATE_REPO

    /** GitHub API for [repo]; debug builds can use a test server: `setprop debug.mainui.updateapi <url>`. */
    private val api: String by lazy {
        val test = if (!BuildConfig.DEBUG) null else runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.mainui.updateapi")).inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.startsWith("http") }
        test ?: "https://api.github.com/repos/$repo"
    }
    val installed: Int get() = BuildConfig.VERSION_CODE

    /** Every MainUI build in a GitHub releases listing, newest version first (drafts skipped). */
    fun parseReleases(json: String): List<Update> {
        val arr = JSONArray(json)
        val out = ArrayList<Update>()
        for (i in 0 until arr.length()) {
            val rel = arr.getJSONObject(i)
            if (rel.optBoolean("draft")) continue
            val assets = rel.optJSONArray("assets") ?: continue
            for (j in 0 until assets.length()) {
                val a = assets.getJSONObject(j)
                val m = assetName.matchEntire(a.optString("name")) ?: continue
                out += Update(
                    versionCode = m.groupValues[2].toInt(),
                    versionName = m.groupValues[1],
                    tag = rel.optString("tag_name"),
                    url = a.optString("browser_download_url"),
                    size = a.optLong("size"),
                    sha256 = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
                    notes = rel.optString("body").trim(),
                    published = rel.optString("published_at").take(10),
                )
            }
        }
        return out.sortedWith(compareByDescending<Update> { it.versionCode }.thenByDescending { it.published })
    }

    /** The newest build on GitHub (whether or not it is newer than this one). */
    fun newest(): Update? = parseReleases(Http.text("$api/releases?per_page=30")).firstOrNull()

    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    /** The automatic check: skipped (null) while another one is running. */
    fun checkIfIdle(): Update? {
        if (!checking.compareAndSet(false, true)) return null
        try { return check() } finally { checking.set(false) }
    }

    /** Asks GitHub for the newest build and remembers whether it is newer than this one. */
    fun check(): Update? {
        val u = newest()
        val s = MainUI.settings
        s.updateLastCheck = System.currentTimeMillis()
        s.updateAvailable = u?.takeIf { it.versionCode > installed }?.versionName ?: ""
        return u
    }

    /** True when automatic checks are on and the last one was a while ago. */
    fun isDue(): Boolean = MainUI.settings.let { it.updateAutoCheck && System.currentTimeMillis() - it.updateLastCheck >= CHECK_EVERY_MS }

    /** Version available from the last check, if newer than this build ("" = none known). */
    fun knownAvailable(): String = MainUI.settings.updateAvailable.takeIf { avail ->
        avail.substringAfterLast('.').toIntOrNull()?.let { it > installed } == true
    } ?: ""

    /**
     * Downloads [u], checks its checksum and that it is signed like this build (Android
     * would refuse it otherwise, with a less helpful message), then installs it: silently
     * with root (MainUI restarts on its own), otherwise through Android's installer.
     */
    fun install(u: Update, progress: (String) -> Unit): String {
        val dir = File(MainUI.app.cacheDir, "update").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "MainUI-${u.versionName}.apk")
        Http.downloadFast(u.url, file) { done, total ->
            progress("Downloading MainUI ${u.versionName}: ${formatBytes(done)}" + if (total > 0) " / ${formatBytes(total)}" else "")
        }
        val mine = AppStore.installedSigner(MainUI.app.packageName)
        val theirs = archiveSigner(file)
        if (mine != null && theirs != null && mine != theirs) {
            file.delete()
            throw IOException("This build is signed with a different key than the installed MainUI, so Android can't " +
                "update it. Install it by hand once (uninstalling MainUI first); later updates then work.")
        }
        return AppStore.installFile(file, "MainUI ${u.versionName}", u.sha256, progress)
    }

    /** SHA-256 of an APK file's signing certificate, or null if it can't be read. */
    private fun archiveSigner(f: File): String? = runCatching {
        val pm = MainUI.app.packageManager
        @Suppress("DEPRECATION")
        val sig = if (Build.VERSION.SDK_INT >= 28) pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?.signingInfo?.apkContentsSigners?.firstOrNull()
        else pm.getPackageArchiveInfo(f.path, PackageManager.GET_SIGNATURES)?.signatures?.firstOrNull()
        sig?.toByteArray()?.let { b -> MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }
    }.getOrNull()
}
