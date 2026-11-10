package io.github.marsinsanity.mainui.ui.scenes

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import io.github.marsinsanity.mainui.BuildConfig
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import java.io.File
import java.util.Locale

/**
 * Settings > About: the device, its memory and storage, the software versions
 * and MainUI's own. "Build number" opens Android's developer options (turning
 * them on first with root, like tapping it seven times would).
 */
class AboutScene(host: SceneHost) : ListScene(host, "About") {
    override val showPreview = false
    /** Only rows that do something show the A hint; the others are plain information. */
    override var aLabel: String?
        get() = if (list.current?.onSelect != null) "OPEN" else null
        set(_) {}
    override val extraHints get() = listOf("Y" to "REFRESH")

    override fun onEnter() {
        list.set(listOf(UiItem("Reading device information...", disabled = true)))
        refresh()
    }

    override fun onResume() = refresh()

    private fun refresh() {
        host.io({ collect(host.hostContext) }) { rows -> list.set(rows, false) }
    }

    private fun row(label: String, value: String?, onSelect: ((UiItem) -> Unit)? = null) =
        UiItem(label, sub = value ?: "-", onSelect = onSelect)

    private fun collect(ctx: Context): List<UiItem> {
        val rows = ArrayList<UiItem>()
        // ---- device
        // "Retroid" + "Retroid Pocket Mini V2" -> "Retroid Pocket Mini V2"
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL else "$maker ${Build.MODEL}"
        rows += row("Device", model)
        val soc = if (Build.VERSION.SDK_INT >= 31) listOf(Build.SOC_MANUFACTURER, Build.SOC_MODEL)
            .filter { it.isNotBlank() && it != Build.UNKNOWN }.joinToString(" ").ifEmpty { null } else null
        rows += row("Chipset", soc ?: Build.HARDWARE)
        rows += row("CPU", "${Runtime.getRuntime().availableProcessors()} cores" + (maxCpuGhz()?.let { ", up to $it GHz" } ?: "") +
            "  ${Build.SUPPORTED_ABIS.firstOrNull() ?: ""}")
        val dm = android.util.DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (ctx.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(it)
        }
        val hz = runCatching {
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY).refreshRate
        }.getOrNull()
        rows += row("Display", "${maxOf(dm.widthPixels, dm.heightPixels)} x ${minOf(dm.widthPixels, dm.heightPixels)}" +
            (hz?.let { " @ ${Math.round(it)} Hz" } ?: ""))

        // ---- memory and storage
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        rows += row("RAM", "${gb(mi.totalMem - mi.availMem)} / ${gb(mi.totalMem)}")
        for (v in MainUI.storage.volumes()) {
            val st = runCatching { StatFs(v.dir.path) }.getOrNull() ?: continue
            val total = st.totalBytes
            val free = st.availableBytes
            rows += row(if (v.removable) "SD card" else "Internal storage", "${gb(total - free)} / ${gb(total)}")
        }
        battery(ctx)?.let { rows += row("Battery", it) }

        // ---- software
        rows += row("MainUI", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})" + if (BuildConfig.DEBUG) " debug" else "") {
            host.push(UpdateScene(host))
        }
        rows += row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SECURITY_PATCH.isNotBlank()) rows += row("Security patch", Build.VERSION.SECURITY_PATCH)
        rows += row("Build number", Build.DISPLAY) { openDeveloperOptions() }
        rows += row("Kernel", System.getProperty("os.version"))
        val root = if (Root.knownAvailable() || Root.available()) {
            val v = Root.exec("magisk -v 2>/dev/null || su -v 2>/dev/null").out.lines().firstOrNull()?.trim().orEmpty()
            "granted" + if (v.isNotEmpty()) " ($v)" else ""
        } else "not available"
        rows += row("Root", root)
        val ra = MainUI.emulators.raPackage
        val raVersion = ra?.let { runCatching { ctx.packageManager.getPackageInfo(it, 0).versionName }.getOrNull() }
        rows += row("RetroArch", if (ra == null) "not installed" else "${raVersion ?: "?"}  (${MainUI.emulators.raCores?.size ?: "?"} cores)") {
            host.push(CoresScene(host))
        }
        rows += row("Uptime", duration(SystemClock.elapsedRealtime() / 1000))
        rows += row("MainUI data", RomFoldersScene.pretty(MainUI.storage.dataRoot()))
        rows += row("Licence", "GPL-3.0") {
            host.push(DialogScene(host, "MainUI for Android",
                "An OnionOS style frontend for Android handhelds.\nGPL-3.0. Fonts under the SIL Open Font License.\ngithub.com/MarsInsanity/MainUI", "OK", null))
        }
        return rows
    }

    private fun openDeveloperOptions() {
        val ctx = host.hostContext
        val dev = Intent(AndroidSettings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        // While developer options are off, Settings routes this intent to a "not available" stub.
        fun available() = ctx.packageManager.resolveActivity(dev, 0)?.activityInfo?.name?.contains("Disabled") == false &&
            AndroidSettings.Global.getInt(ctx.contentResolver, AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        host.io({
            when {
                available() -> true
                // What tapping "Build number" seven times does: the setting plus Settings' own screen.
                Root.knownAvailable() || Root.available() -> {
                    Root.exec("settings put global development_settings_enabled 1; " +
                        "pm enable 'com.android.settings/.Settings\$DevelopmentSettingsDashboardActivity' >/dev/null 2>&1; true")
                    available()
                }
                else -> false
            }
        }) { enabled ->
            val intent = if (enabled) dev else Intent(AndroidSettings.ACTION_DEVICE_INFO_SETTINGS)
            if (!enabled) host.toast("Tap \"Build number\" 7 times to turn on developer options", 4000)
            runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { host.toast("Developer options are not available") }
        }
    }

    override fun onKey(b: Btn, repeat: Boolean): Boolean {
        if (b == Btn.Y && !repeat) { refresh(); return true }
        return super.onKey(b, repeat)
    }

    companion object {
        fun gb(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / 1073741824.0)
            else -> String.format(Locale.US, "%.0f MB", bytes / 1048576.0)
        }

        fun duration(secs: Long): String {
            val d = secs / 86400; val h = secs % 86400 / 3600; val m = secs % 3600 / 60
            return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
        }

        private fun maxCpuGhz(): String? {
            val max = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { i ->
                runCatching { File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }.maxOrNull()?.takeIf { it >= 100_000 } ?: return null // kHz; emulators report 0
            return String.format(Locale.US, "%.2f", max / 1_000_000.0)
        }

        private fun battery(ctx: Context): String? {
            val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level < 0) return null
            val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            val status = when (i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                else -> null
            }
            return listOfNotNull("${level * 100 / scale.coerceAtLeast(1)}%", status,
                if (temp != Int.MIN_VALUE) String.format(Locale.US, "%.1f °C", temp / 10.0) else null).joinToString(", ")
        }
    }
}
