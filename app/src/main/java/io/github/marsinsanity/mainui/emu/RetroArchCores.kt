package io.github.marsinsanity.mainui.emu

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.store.Http
import io.github.marsinsanity.mainui.store.formatBytes
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Finds the cores RetroArch can load and downloads missing ones.
 *
 * Where cores live depends on the build and on the user's settings:
 *  - `libretro_directory` in retroarch.cfg (empty = default),
 *  - the default `/data/data/<pkg>/cores` (website / F-Droid builds, Online Updater),
 *  - the app's native library folder and its split APKs (Play Store builds
 *    deliver cores as Play Feature Delivery modules),
 *  - anywhere else under the app's data folder (split-compat extraction).
 * The data folders need root; the APK / native library checks don't.
 */
object RetroArchCores {
    private const val TAG = "MainUI.Cores"
    const val SUFFIX = "_libretro_android.so"
    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest"

    /** Debug builds can point at a local mirror with `setprop debug.mainui.corebase <url>`. */
    private val buildbot: String by lazy {
        if (!BuildConfig.DEBUG) BUILDBOT
        else runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.mainui.corebase")).inputStream.bufferedReader().readText().trim()
        }.getOrNull()?.takeIf { it.startsWith("http") } ?: BUILDBOT
    }

    data class Scan(
        /** core basename -> absolute path RetroArch should load. */
        val cores: Map<String, String>,
        /** True when every location was readable, so a core not in [cores] is really missing. */
        val complete: Boolean,
        /** The folder RetroArch loads cores from (and where downloads go). */
        val coreDir: String,
        val infoDir: String,
    )

    fun coreName(file: String) = file.substringAfterLast('/').removeSuffix(SUFFIX)

    /** `key = "value"` from retroarch.cfg; null for missing, empty or "default". */
    fun cfgValue(cfg: String?, key: String): String? = cfg?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("$key ") || it.startsWith("$key=") }
        ?.substringAfter('=')?.trim()?.trim('"')
        ?.takeIf { it.isNotEmpty() && it != "default" }

    /**
     * Sets [wanted] keys in a retroarch.cfg text (replacing existing lines, appending
     * missing ones) and returns the new text, or null when the input doesn't look
     * like a complete RetroArch config, so a partial read is never written back.
     */
    fun mergeConfig(cfg: String, wanted: Map<String, String>): String? {
        val lines = cfg.lines().dropLastWhile { it.isBlank() }.toMutableList()
        val settings = lines.count { Regex("^\\s*[A-Za-z0-9_]+\\s*=").containsMatchIn(it) }
        // A real config has hundreds of settings (thousands on current versions).
        if (settings < 100) return null
        for ((k, v) in wanted) {
            val i = lines.indexOfFirst { it.trimStart().startsWith("$k ") || it.trimStart().startsWith("$k=") }
            if (i >= 0) lines[i] = "$k = $v" else lines += "$k = $v"
        }
        return lines.joinToString("\n") + "\n"
    }

    fun scan(ctx: Context, pkg: String, cfg: String?): Scan {
        val dataDir = "/data/data/$pkg"
        // Cores can't be loaded from shared storage (noexec), so only a /data folder counts.
        val coreDir = cfgValue(cfg, "libretro_directory")?.takeIf { it.startsWith("/data/") }?.trimEnd('/') ?: "$dataDir/cores"
        val infoDir = cfgValue(cfg, "libretro_info_path")?.trimEnd('/') ?: "$dataDir/info"
        val found = LinkedHashMap<String, String>()
        var complete = false

        // 1. Root: the configured folder first (that is what RetroArch uses), then the default
        //    one, then anything under the app's files (Play Feature Delivery split-compat).
        if (Root.knownAvailable() || Root.available()) {
            val q = Root::q
            val r = Root.exec(
                "for d in ${q(coreDir)} ${q("$dataDir/cores")}; do ls -1d \"\$d\"/*$SUFFIX 2>/dev/null; done; " +
                    "find ${q("$dataDir/files")} -maxdepth 7 -name '*$SUFFIX' 2>/dev/null; echo __scan_ok__")
            if (r.out.contains("__scan_ok__")) complete = true
            for (l in r.lines) {
                val p = l.trim()
                if (p.endsWith(SUFFIX)) found.putIfAbsent(coreName(p), p)
            }
        }

        // 2. No root needed: extracted native libraries and cores packed in the APK splits.
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()
        val libDir = ai?.nativeLibraryDir
        if (libDir != null) {
            File(libDir).listFiles()?.forEach { f -> if (f.name.endsWith(SUFFIX)) found.putIfAbsent(coreName(f.name), f.absolutePath) }
            val apks = listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
            for (apk in apks) runCatching {
                ZipFile(apk).use { z ->
                    for (e in z.entries()) {
                        val n = e.name
                        if (n.startsWith("lib/") && n.endsWith(SUFFIX)) found.putIfAbsent(coreName(n), "$libDir/${n.substringAfterLast('/')}")
                    }
                }
            }
        }
        Log.d(TAG, "scan $pkg: ${found.size} cores, complete=$complete, dir=$coreDir")
        return Scan(found, complete, coreDir, infoDir)
    }

    /** The ABI RetroArch runs as (its native library folder tells: lib/arm64, lib/arm, ...). */
    fun abiOf(ctx: Context, pkg: String): String {
        val dir = runCatching { ctx.packageManager.getApplicationInfo(pkg, 0).nativeLibraryDir }.getOrNull().orEmpty()
        return when {
            dir.endsWith("/arm64") -> "arm64-v8a"
            dir.endsWith("/arm") -> "armeabi-v7a"
            dir.endsWith("/x86_64") -> "x86_64"
            dir.endsWith("/x86") -> "x86"
            pkg.endsWith(".ra32") -> "armeabi-v7a"
            else -> Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        }
    }

    fun downloadUrl(abi: String, core: String) = "$buildbot/$abi/$core$SUFFIX.zip"

    /**
     * Downloads [core] from the libretro buildbot (the same files RetroArch's
     * Online Updater installs) and installs it. Needs root. Returns the installed path.
     */
    fun install(ctx: Context, pkg: String, coreDir: String, core: String, progress: (String) -> Unit): String {
        requireRoot()
        val tmpDir = File(ctx.cacheDir, "cores").apply { mkdirs() }
        val zip = File(tmpDir, "$core$SUFFIX.zip")
        try {
            Http.download(downloadUrl(abiOf(ctx, pkg), core), zip) { done, total ->
                progress("Downloading $core: ${formatBytes(done)}" + if (total > 0) " / ${formatBytes(total)}" else "")
            }
            return installFile(ctx, pkg, coreDir, zip, core, progress)
        } finally {
            zip.delete()
        }
    }

    /**
     * Installs a core from a local `<core>_libretro_android.so` or a `.zip` holding
     * one (cores built elsewhere, e.g. from MainUI/cores). A `<core>_libretro.info`
     * next to it (or inside the zip) is installed too, so RetroArch shows its name.
     */
    fun installFile(ctx: Context, pkg: String, coreDir: String, file: File, core: String = coreName(file.name.removeSuffix(".zip")),
                    progress: (String) -> Unit = {}): String {
        requireRoot()
        progress("Installing $core...")
        val tmpDir = File(ctx.cacheDir, "cores").apply { mkdirs() }
        val so = File(tmpDir, "$core$SUFFIX.tmp")
        val info = File(tmpDir, "${core}_libretro.info.tmp")
        try {
            if (file.name.endsWith(".zip", true)) {
                ZipFile(file).use { z ->
                    val e = z.entries().asSequence().firstOrNull { it.name.endsWith(SUFFIX) } ?: throw IOException("No core inside ${file.name}")
                    z.getInputStream(e).use { input -> so.outputStream().use { input.copyTo(it) } }
                    z.entries().asSequence().firstOrNull { it.name.endsWith("_libretro.info") }?.let { ie ->
                        z.getInputStream(ie).use { input -> info.outputStream().use { input.copyTo(it) } }
                    }
                }
            } else {
                file.copyTo(so, overwrite = true)
                File(file.parentFile, "${core}_libretro.info").takeIf { it.isFile }?.copyTo(info, overwrite = true)
            }
            // An ELF for the wrong system (e.g. a Linux / Miyoo build) would only fail inside RetroArch.
            val head = so.inputStream().use { val b = ByteArray(20); it.read(b); b }
            if (head[0] != 0x7f.toByte() || head[1] != 'E'.code.toByte()) throw IOException("${file.name} is not a core (.so) file")
            so.setReadable(true, false); info.setReadable(true, false)
            val dest = place(pkg, coreDir, so, "$core$SUFFIX")
            if (info.isFile && info.length() > 0) {
                val infoDir = "/data/data/$pkg/info"
                runCatching { place(pkg, infoDir, info, "${core}_libretro.info") }
            }
            Log.d(TAG, "installed $dest")
            return dest
        } finally {
            so.delete(); info.delete()
        }
    }

    private fun requireRoot() {
        if (!Root.knownAvailable() && !Root.available()) throw IOException("Root access is needed to add cores to RetroArch")
    }

    /** Copies [src] into [dir]/[name] with the app's owner and SELinux label, so RetroArch can load it. */
    private fun place(pkg: String, dir: String, src: File, name: String): String {
        val q = Root::q
        val data = "/data/data/$pkg"
        val dest = "$dir/$name"
        val r = Root.exec(
            "set -e; o=\$(stat -c %u:%g ${q(data)}); c=\$(stat -c %C ${q(data)}); " +
                "if [ ! -d ${q(dir)} ]; then mkdir -p ${q(dir)}; chown \$o ${q(dir)}; chmod 0700 ${q(dir)}; " +
                "chcon \$c ${q(dir)} 2>/dev/null || true; fi; " +
                "cat ${q(src.absolutePath)} > ${q("$dest.part")}; mv -f ${q("$dest.part")} ${q(dest)}; " +
                "chown \$o ${q(dest)}; chmod 0700 ${q(dest)}; chcon \$c ${q(dest)} 2>/dev/null || true; ls -l ${q(dest)}",
            timeoutSec = 60)
        if (!r.ok || !r.out.contains(name)) throw IOException("Could not copy $name: ${r.err.ifBlank { r.out }.take(120)}")
        return dest
    }
}
