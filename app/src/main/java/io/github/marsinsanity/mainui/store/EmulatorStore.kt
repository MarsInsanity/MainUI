package io.github.marsinsanity.mainui.store

import android.os.Build
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.emu.EmuDef
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Apps > App Store > Emulators: every emulator MainUI can launch, and where
 * to get it. Sources, best first:
 *  - an F-Droid compatible repository that has the package (signed index),
 *  - the latest GitHub release of the project ("get.github" in emulators.json),
 *  - RetroArch's own builds from retroarch.com ("get.buildbot"),
 *  - the Google Play page or the project's website (opened, not installed).
 */
object EmulatorStore {
    private const val GITHUB_API = "https://api.github.com"
    private const val BUILDBOT = "https://buildbot.libretro.com/stable"

    /** Debug builds can use local mirrors: `setprop debug.mainui.githubapi <url>` / `debug.mainui.rabase <url>`. */
    private fun debugProp(name: String, default: String): String =
        if (!BuildConfig.DEBUG) default
        else runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", name)).inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: default

    private val githubApi by lazy { debugProp("debug.mainui.githubapi", GITHUB_API) }
    private val buildbot by lazy { debugProp("debug.mainui.rabase", BUILDBOT) }

    data class Entry(
        val def: EmuDef,
        /** Short names of the systems MainUI uses it for ("PS2", "GC / Wii"...). */
        val systems: String,
        /** Installed package, if any. */
        val installed: String?,
        /** The same app in an F-Droid compatible repository, if one has it. */
        val fdroid: AppStore.App?,
    ) {
        val github get() = def.get["github"]?.takeIf { it.contains('/') }
        val page get() = def.get["page"]?.takeIf { it.startsWith("http") }
        val play get() = def.get["play"]?.takeIf { it.isNotBlank() }
        val note get() = def.get["note"]?.takeIf { it.isNotBlank() }
        val buildbot get() = def.get["buildbot"] == "true"
        val url get() = def.get["url"]?.takeIf { it.startsWith("http") }
        val canDownload get() = fdroid?.apk != null || github != null || buildbot || url != null

        /** One word for the list: where it comes from. */
        val source: String get() = when {
            installed != null -> "installed"
            fdroid?.apk != null -> "F-Droid"
            buildbot -> "retroarch.com"
            github != null -> "GitHub"
            url != null -> "download"
            play != null -> "Play Store"
            page != null -> "website"
            else -> "no download"
        }
    }

    /** All emulators: RetroArch first, then the standalone ones by name. */
    fun entries(): List<Entry> {
        val em = MainUI.emulators
        val apps = runCatching { AppStore.apps() }.getOrDefault(emptyList()).associateBy { it.pkg }
        val bySystem = HashMap<String, MutableList<String>>()
        for (s in MainUI.systems.defs) for (ref in s.emulators) if (ref.startsWith("sa:")) {
            bySystem.getOrPut(ref.removePrefix("sa:")) { mutableListOf() } += s.short
        }
        val defs = listOf(em.retroarch) + em.standaloneDefs().sortedBy { it.name.lowercase() }
        return defs.map { d ->
            val installed = d.packages.firstOrNull { AppStore.installedVersion(it) != null }
            val fdroid = d.packages.firstNotNullOfOrNull { apps[it] }
            val systems = if (d.id == "retroarch") "Multi-system" else bySystem[d.id].orEmpty().distinct().joinToString(" / ")
            Entry(d, systems, installed, fdroid)
        }
    }

    data class Release(val version: String, val apkName: String, val url: String, val size: Long, val sha256: String?,
                       /** When it was published (ms since 1970), 0 when unknown. */
                       val published: Long = 0)

    /** The APK of the newest GitHub release of [repo] ("owner/name") that has one. */
    fun latestGithub(repo: String): Release {
        val latest = runCatching { JSONObject(Http.text("$githubApi/repos/$repo/releases/latest")) }.getOrNull()
        val candidates = mutableListOf<JSONObject>()
        if (latest != null) candidates += latest
        if (latest == null || pickApk(latest.optJSONArray("assets")) == null) {
            // Projects that only publish pre-releases, or whose latest release has no APK.
            val all = JSONArray(Http.text("$githubApi/repos/$repo/releases?per_page=10"))
            for (i in 0 until all.length()) candidates += all.getJSONObject(i)
        }
        for (rel in candidates) {
            val a = pickApk(rel.optJSONArray("assets")) ?: continue
            val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
            val published = runCatching { java.time.Instant.parse(rel.optString("published_at")).toEpochMilli() }.getOrDefault(0L)
            return Release(rel.optString("tag_name").ifEmpty { rel.optString("name") }, a.getString("name"),
                a.getString("browser_download_url"), a.optLong("size"), digest, published)
        }
        throw IOException("No APK in the releases of $repo")
    }

    /** Best APK for this device among release assets, or null. */
    fun pickApk(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        val names = (0 until assets.length()).map { assets.getJSONObject(it) }
        val best = names.filter { it.optString("name").endsWith(".apk", true) }
            .maxByOrNull { scoreApk(it.optString("name"), Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a") }
        return best?.takeIf { scoreApk(it.optString("name"), Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a") > -50 }
    }

    /** Ranks an APK file name for a device whose main ABI is [abi]. */
    fun scoreApk(name: String, abi: String, sdk: Int = Build.VERSION.SDK_INT, armv82: Boolean = cpuHasArmv82): Int {
        val n = name.lowercase()
        var s = 0
        val is64 = abi == "arm64-v8a"
        when {
            n.contains("arm64") || n.contains("aarch64") || n.contains("v8a") -> s += if (is64) 30 else -100
            n.contains("x86_64") || n.contains("x64") -> s += if (abi == "x86_64") 30 else -100
            n.contains("x86") -> s += if (abi == "x86") 30 else -100
            n.contains("armeabi") || n.contains("v7a") || n.contains("arm32") || n.contains("armv7") -> s += if (abi == "armeabi-v7a") 30 else -40
            Regex("""armv8\.?\d?""").containsMatchIn(n) -> s += if (is64) 30 else -100   // ARMSX3: armv8.2 / armv8.1
            n.contains("universal") -> s += 20
        }
        // Builds per Android version ("...-sdk33.apk"): never one this Android can't install,
        // otherwise the newest that fits (it uses what this Android version offers).
        Regex("""(?:^|[^a-z])sdk(\d{2})(?:[^0-9]|$)""").find(n)?.groupValues?.get(1)?.toIntOrNull()?.let { min ->
            s += if (min > sdk) -200 else (min - 20).coerceIn(0, 19)
        }
        // CPU level builds: armv8.2 needs the ARMv8.2 instructions (every arm64 chip from 2019 on).
        if (n.contains("armv8.2") && !armv82) s -= 200
        // Store-specific flavours and test builds last.
        if (n.contains("googleplay") || n.contains("play-store") || n.contains("playstore")) s -= 15
        if (n.contains("debug") || n.contains("unsigned")) s -= 25
        if (n.contains("legacy")) s -= if (armv82) 25 else 5
        return s
    }

    /**
     * True when the CPU has ARMv8.2 (half-precision SIMD, "asimdhp" in /proc/cpuinfo).
     * True when it can't be read, since nearly every current arm64 handheld has it.
     */
    val cpuHasArmv82: Boolean by lazy {
        val features = runCatching { File("/proc/cpuinfo").readLines() }.getOrNull()
            ?.filter { it.startsWith("Features", true) }?.joinToString(" ")?.lowercase()
        if (features.isNullOrBlank()) true else features.split(Regex("\\s+")).contains("asimdhp")
    }

    /** RetroArch's latest stable build from retroarch.com (64-bit build on arm64 devices). */
    fun latestRetroArch(): Release {
        val index = Http.text("$buildbot/")
        val version = newestVersion(Regex("""href="(?:/stable/)?([0-9]+(?:\.[0-9]+)+)/"""").findAll(index).map { it.groupValues[1] }.toList())
            ?: throw IOException("Could not read the RetroArch download list")
        val name = if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") "RetroArch_aarch64.apk" else "RetroArch.apk"
        return Release(version, name, "$buildbot/$version/android/$name", -1, null)
    }

    /** "1.22.2" > "1.9.9" (numeric, not text, order). */
    fun newestVersion(versions: List<String>): String? = versions.maxWithOrNull { a, b ->
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        (0 until maxOf(x.size, y.size)).map { (x.getOrElse(it) { 0 }).compareTo(y.getOrElse(it) { 0 }) }.firstOrNull { it != 0 } ?: 0
    }

    /** Downloads [r] and installs it (root: silently; otherwise Android asks). Returns a message. */
    fun install(name: String, r: Release, progress: (String) -> Unit): String {
        val file = File(MainUI.app.cacheDir, "appstore/${r.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        file.parentFile?.mkdirs()
        Http.download(r.url, file) { done, total ->
            val t = if (total > 0) total else r.size
            progress("Downloading $name: ${formatBytes(done)}" + if (t > 0) " / ${formatBytes(t)}" else "")
        }
        return AppStore.installFile(file, name, r.sha256, progress)
    }
}
