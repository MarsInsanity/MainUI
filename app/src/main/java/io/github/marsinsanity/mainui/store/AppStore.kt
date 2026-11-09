package io.github.marsinsanity.mainui.store

import android.content.Intent
import android.os.Build
import android.util.JsonReader
import android.util.JsonToken
import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.security.MessageDigest
import java.util.jar.JarFile

/**
 * A small F-Droid client: any F-Droid compatible repository (F-Droid itself,
 * IzzyOnDroid, self-hosted fdroidserver repos...).
 *
 * The signed index-v1.jar is downloaded, its signature checked against the
 * repository's certificate fingerprint (pinned for f-droid.org, trust on first
 * use for repositories you add), stream-parsed, and reduced to what the store
 * shows. APKs are checked against the index's SHA-256 before installing.
 */
object AppStore {
    private const val TAG = "MainUI.AppStore"

    data class Repo(val address: String, var name: String, var fingerprint: String?, var enabled: Boolean = true) {
        fun json() = JSONObject().put("address", address).put("name", name).put("fingerprint", fingerprint).put("enabled", enabled)
    }

    /** [signer]: SHA-256 of the signing certificate (index-v1 "signer"), to spot builds from another source. */
    data class Apk(val versionCode: Long, val versionName: String, val apkName: String, val size: Long, val sha256: String?,
                   val signer: String? = null)

    data class App(
        val pkg: String, val name: String, val summary: String, val description: String,
        val icon: String?, val categories: List<String>, val license: String, val lastUpdated: Long,
        val apk: Apk?, val repo: String,
    )

    /** Repositories enabled by default. f-droid.org's signing certificate is pinned. */
    val DEFAULT_REPOS = listOf(
        Repo("https://f-droid.org/repo", "F-Droid", "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab"),
        Repo("https://apt.izzysoft.de/fdroid/repo", "IzzyOnDroid", null),
    )

    private val configFile get() = File(MainUI.storage.configDir, "repos.json")
    private val cacheDir get() = File(MainUI.app.cacheDir, "appstore").apply { mkdirs() }
    private val dataDir get() = MainUI.storage.dataDir("cache/appstore")

    @Volatile private var appsCache: List<App>? = null
    @Volatile private var appsCacheKey = ""

    // ---- repositories --------------------------------------------------------------

    fun repos(): MutableList<Repo> {
        val arr = runCatching { JSONArray(configFile.readText()) }.getOrNull() ?: return DEFAULT_REPOS.map { it.copy() }.toMutableList()
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Repo(o.getString("address"), o.optString("name"), o.optString("fingerprint").ifEmpty { null }, o.optBoolean("enabled", true))
        }.toMutableList()
    }

    fun saveRepos(list: List<Repo>) {
        appsCache = null
        runCatching { configFile.writeText(JSONArray(list.map { it.json() }).toString(1)) }
            .onFailure { Log.w(TAG, "could not save ${configFile.path}", it) }
    }

    /** "f-droid.org/repo", "https://host/fdroid/repo?fingerprint=..." -> normalised Repo. */
    fun parseRepoInput(input: String): Repo {
        var s = input.trim()
        val fp = Regex("fingerprint=([0-9A-Fa-f]{64})").find(s)?.groupValues?.get(1)?.lowercase()
        s = s.substringBefore('?').trimEnd('/')
        if (!s.startsWith("http")) s = "https://$s"
        return Repo(s, s.removePrefix("https://").removePrefix("http://"), fp)
    }

    // ---- index -----------------------------------------------------------------------

    private fun repoKey(r: Repo) = sha256(r.address.toByteArray()).take(16)
    private fun compactFile(r: Repo) = File(dataDir, "${repoKey(r)}.json")

    /** Last refresh time of a repository's index, 0 = never. */
    fun lastRefresh(r: Repo): Long = compactFile(r).lastModified()

    /**
     * Downloads and verifies a repository's index-v1.jar and stores a compact
     * copy. Returns the number of apps. [progress] gets a human readable status.
     */
    fun refresh(r: Repo, progress: (String) -> Unit): Int {
        val jar = File(cacheDir, "${repoKey(r)}-index-v1.jar")
        progress("Downloading ${r.name}...")
        Http.download("${r.address}/index-v1.jar", jar) { done, total ->
            progress("Downloading ${r.name}: ${done / 1024 / 1024} MB" + if (total > 0) " / ${total / 1024 / 1024} MB" else "")
        }
        progress("Checking ${r.name}...")
        val apps = ArrayList<App>()
        var repoName = r.name
        JarFile(jar, true).use { jf ->
            val entry = jf.getJarEntry("index-v1.json") ?: throw IOException("index-v1.json missing")
            jf.getInputStream(entry).use { input ->
                parseIndex(JsonReader(InputStreamReader(input, Charsets.UTF_8)), r.address, apps) { repoName = it }
            }
            // Certificates are only known after the entry was read completely.
            val certs = entry.certificates ?: throw IOException("${r.name}: index is not signed")
            val fp = sha256(certs.first().encoded)
            when {
                r.fingerprint == null -> { r.fingerprint = fp; Log.i(TAG, "trusting ${r.address} with $fp") }
                !r.fingerprint.equals(fp, true) -> throw IOException("${r.name}: signing key changed! Expected ${r.fingerprint}, got $fp")
            }
        }
        jar.delete()
        r.name = repoName
        val arr = JSONArray()
        for (a in apps) arr.put(JSONObject().apply {
            put("p", a.pkg); put("n", a.name); put("s", a.summary); put("d", a.description); put("i", a.icon)
            put("c", JSONArray(a.categories)); put("l", a.license); put("u", a.lastUpdated)
            a.apk?.let { put("vc", it.versionCode); put("vn", it.versionName); put("an", it.apkName); put("sz", it.size); put("h", it.sha256); put("sg", it.signer) }
        })
        compactFile(r).writeText(JSONObject().put("repo", r.address).put("apps", arr).toString())
        appsCache = null
        return apps.size
    }

    /** All apps from every enabled, refreshed repository (first repository wins on duplicates). */
    fun apps(): List<App> {
        val enabled = repos().filter { it.enabled }
        // Rebuild when the enabled repositories or their downloaded catalogs change.
        val key = enabled.joinToString("|") { it.address + "@" + compactFile(it).lastModified() }
        appsCache?.let { if (key == appsCacheKey) return it }
        val out = LinkedHashMap<String, App>()
        for (r in enabled) {
            val o = runCatching { JSONObject(compactFile(r).readText()) }.getOrNull() ?: continue
            val arr = o.getJSONArray("apps")
            for (i in 0 until arr.length()) {
                val a = arr.getJSONObject(i)
                val pkg = a.getString("p")
                if (out.containsKey(pkg)) continue
                val apk = if (a.has("an")) Apk(a.getLong("vc"), a.optString("vn"), a.getString("an"), a.optLong("sz"), a.optString("h").ifEmpty { null },
                    a.optString("sg").ifEmpty { null }) else null
                val cats = a.optJSONArray("c")?.let { c -> (0 until c.length()).map { c.getString(it) } } ?: emptyList()
                out[pkg] = App(pkg, a.optString("n", pkg), a.optString("s"), a.optString("d"), a.optString("i").ifEmpty { null },
                    cats, a.optString("l"), a.optLong("u"), apk, r.address)
            }
        }
        val list = out.values.sortedBy { it.name.lowercase() }
        appsCache = list
        appsCacheKey = key
        return list
    }

    fun categories(): List<String> = apps().flatMap { it.categories }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }

    private fun parseIndex(r: JsonReader, address: String, out: MutableList<App>, onRepoName: (String) -> Unit) {
        val apps = HashMap<String, App>()
        val best = HashMap<String, Apk>()
        val suggested = HashMap<String, Long>()
        val abis = Build.SUPPORTED_ABIS.toSet()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "repo" -> {
                    r.beginObject()
                    while (r.hasNext()) if (r.nextName() == "name") onRepoName(r.nextString()) else r.skipValue()
                    r.endObject()
                }
                "apps" -> {
                    r.beginArray()
                    while (r.hasNext()) {
                        val a = readApp(r, address)
                        if (a != null) { apps[a.first.pkg] = a.first; a.second?.let { suggested[a.first.pkg] = it } }
                    }
                    r.endArray()
                }
                "packages" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val pkg = r.nextName()
                        r.beginArray()
                        while (r.hasNext()) {
                            val (apk, ok) = readApk(r, abis)
                            if (!ok) continue
                            val cap = suggested[pkg]
                            if (cap != null && apk.versionCode > cap) continue
                            val cur = best[pkg]
                            if (cur == null || apk.versionCode > cur.versionCode) best[pkg] = apk
                        }
                        r.endArray()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        for ((pkg, a) in apps) out += a.copy(apk = best[pkg])
    }

    private fun readApp(r: JsonReader, address: String): Pair<App, Long?>? {
        var pkg = ""; var name: String? = null; var summary: String? = null; var desc: String? = null
        var icon: String? = null; var license = ""; var updated = 0L; var suggested: Long? = null
        val cats = ArrayList<String>()
        var locName: String? = null; var locSummary: String? = null; var locDesc: String? = null; var locIcon: String? = null
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "packageName" -> pkg = r.nextString()
                "name" -> name = r.nextString()
                "summary" -> summary = r.nextString()
                "description" -> desc = r.nextString()
                "icon" -> icon = r.nextString()
                "license" -> license = r.nextString()
                "lastUpdated" -> updated = r.nextLong()
                "suggestedVersionCode" -> suggested = r.nextString().toLongOrNull()
                "categories" -> { r.beginArray(); while (r.hasNext()) cats += r.nextString(); r.endArray() }
                "localized" -> {
                    r.beginObject()
                    while (r.hasNext()) {
                        val lang = r.nextName()
                        val prefer = lang == "en-US" || (lang.startsWith("en") && locName == null)
                        r.beginObject()
                        while (r.hasNext()) {
                            val k = r.nextName()
                            if (r.peek() != JsonToken.STRING) { r.skipValue(); continue }
                            val v = r.nextString()
                            if (!prefer && locName != null) continue
                            when (k) {
                                "name" -> locName = v
                                "summary" -> locSummary = v
                                "description" -> locDesc = v
                                "icon" -> locIcon = "$address/$pkg/$lang/$v"
                            }
                        }
                        r.endObject()
                    }
                    r.endObject()
                }
                else -> r.skipValue()
            }
        }
        r.endObject()
        if (pkg.isEmpty()) return null
        val iconUrl = locIcon ?: icon?.let { "$address/icons-640/$it" }
        val d = (locDesc ?: desc ?: "").replace(Regex("<[^>]+>"), "").take(700)
        return App(pkg, locName ?: name ?: pkg, locSummary ?: summary ?: "", d, iconUrl, cats, license, updated, null, address) to suggested
    }

    private fun readApk(r: JsonReader, abis: Set<String>): Pair<Apk, Boolean> {
        var vc = 0L; var vn = ""; var an = ""; var size = 0L; var hash: String? = null; var minSdk = 1; var signer: String? = null
        val native = ArrayList<String>()
        r.beginObject()
        while (r.hasNext()) {
            when (r.nextName()) {
                "versionCode" -> vc = r.nextLong()
                "versionName" -> vn = r.nextString()
                "apkName" -> an = r.nextString()
                "size" -> size = r.nextLong()
                "hash" -> hash = r.nextString()
                "signer" -> signer = r.nextString()
                "minSdkVersion" -> minSdk = r.nextString().toIntOrNull() ?: runCatching { r.nextInt() }.getOrDefault(1)
                "nativecode" -> { r.beginArray(); while (r.hasNext()) native += r.nextString(); r.endArray() }
                else -> r.skipValue()
            }
        }
        r.endObject()
        val ok = an.isNotEmpty() && minSdk <= Build.VERSION.SDK_INT && (native.isEmpty() || native.any { it in abis })
        return Apk(vc, vn, an, size, hash, signer) to ok
    }

    // ---- install ---------------------------------------------------------------------

    /** SHA-256 of the installed app's signing certificate, lower case hex. */
    fun installedSigner(pkg: String): String? = runCatching {
        val pm = MainUI.app.packageManager
        @Suppress("DEPRECATION")
        val sig = if (Build.VERSION.SDK_INT >= 28) pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.firstOrNull()
        else pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        sig?.toByteArray()?.let { sha256(it) }
    }.getOrNull()

    fun installedVersion(pkg: String): Long? = runCatching {
        MainUI.app.packageManager.getPackageInfo(pkg, 0).longVersionCode
    }.getOrNull()

    /**
     * Downloads the APK, checks its SHA-256 and installs it: silently with
     * root, otherwise through Android's package installer (asks the user).
     * Returns a message for the UI.
     */
    fun install(app: App, progress: (String) -> Unit): String {
        val apk = app.apk ?: return "No compatible version for this device"
        val file = File(cacheDir, "${app.pkg}-${apk.versionCode}.apk")
        Http.download("${app.repo}/${apk.apkName}", file) { done, total ->
            progress("Downloading ${app.name}: ${formatBytes(done)}" + if (total > 0) " / ${formatBytes(total)}" else "")
        }
        return installFile(file, app.name, apk.sha256, progress)
    }

    /**
     * Checks [file] against [sha256] (when known) and installs it: silently with
     * root, otherwise through Android's package installer (asks the user).
     */
    fun installFile(file: File, name: String, sha256: String?, progress: (String) -> Unit = {}): String {
        sha256?.let { expected ->
            progress("Verifying...")
            val actual = file.inputStream().use { input ->
                val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(1 shl 16)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
                md.digest().joinToString("") { "%02x".format(it) }
            }
            if (!actual.equals(expected, true)) { file.delete(); return "Download corrupted (checksum mismatch)" }
        }
        progress("Installing $name...")
        if (Root.knownAvailable() || Root.available()) {
            file.setReadable(true, false)
            val r = Root.exec("pm install -r ${Root.q(file.absolutePath)}", timeoutSec = 300)
            file.delete()
            if (r.ok && r.out.contains("Success")) return "$name installed"
            val why = (r.out + r.err).let { o ->
                when {
                    o.contains("UPDATE_INCOMPATIBLE") -> "a version from another source (different signature) is installed; uninstall it first"
                    o.contains("NO_MATCHING_ABIS") -> "this build is not for this device's CPU"
                    o.contains("OLDER_SDK") -> "needs a newer Android version"
                    o.contains("VERSION_DOWNGRADE") -> "a newer version is already installed"
                    else -> o.ifBlank { "unknown error" }.trim().take(120)
                }
            }
            return "Install failed: $why"
        }
        val uri = MainUI.emulators.providerUri(file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        MainUI.app.startActivity(intent)
        return "Confirm the installation"
    }

    /** Icon file for an app, downloaded once. */
    fun icon(app: App): File? {
        val url = app.icon ?: return null
        val f = File(MainUI.storage.dataDir("cache/appstore/icons"), "${app.pkg}.png")
        if (f.isFile && f.length() > 0) return f
        return runCatching { Http.download(url, f); f }.getOrNull()
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
