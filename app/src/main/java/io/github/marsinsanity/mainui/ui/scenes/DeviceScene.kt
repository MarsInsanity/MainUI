package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

/**
 * Settings > Device: the handheld's own settings without leaving MainUI. Brightness, auto
 * brightness and screen timeout are Android system settings (MainUI's "modify system settings"
 * permission, or root); volume is the media volume; Wi-Fi, Bluetooth and battery saver can only be
 * switched by apps with root, so without it those rows open Android's own panel.
 */
class DeviceScene(host: SceneHost) : ListScene(host, "Device") {
    override val showPreview = false
    private val main = Handler(Looper.getMainLooper())

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun canWrite() = AndroidSettings.System.canWrite(ctx)
    private fun root() = Root.knownAvailable()

    private fun build() {
        val keep = list.active
        val items = mutableListOf<UiItem>()
        val cr = ctx.contentResolver

        // Brightness, 10-100% in tens (Onion's steps; no 0%: a black screen can't be undone by looking).
        val levels = (1..10).map { it.toString() to "${it * 10}%" }
        items += multi("Brightness", levels, { Brightness.get(ctx).coerceAtLeast(1).toString() }) { Brightness.set(ctx, it.toInt()) }
        val auto = runCatching { AndroidSettings.System.getInt(cr, AndroidSettings.System.SCREEN_BRIGHTNESS_MODE) == 1 }.getOrDefault(false)
        items += toggle("Auto brightness", { auto }) { on -> putSystem(AndroidSettings.System.SCREEN_BRIGHTNESS_MODE, if (on) 1 else 0) }

        // Media volume, in the device's own steps.
        val am = ctx.getSystemService(AudioManager::class.java)
        if (am != null) {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            val vols = (0..max).map { it.toString() to if (it == 0) "Muted" else "${it * 100 / max}%" }
            items += multi("Volume", vols, { am.getStreamVolume(AudioManager.STREAM_MUSIC).toString() }) {
                runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0) }
            }
        }

        // Screen timeout.
        val timeouts = listOf(15_000, 30_000, 60_000, 120_000, 300_000, 600_000, 1_800_000)
        val cur = runCatching { AndroidSettings.System.getInt(cr, AndroidSettings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(60_000)
        val nearest = timeouts.minByOrNull { kotlin.math.abs(it - cur) } ?: 60_000
        items += multi("Screen timeout", timeouts.map { it.toString() to timeoutLabel(it) }, { nearest.toString() }) {
            putSystem(AndroidSettings.System.SCREEN_OFF_TIMEOUT, it.toInt())
        }

        // Radios and battery saver: switched with root, else Android's panel.
        val wifi = runCatching { ctx.applicationContext.getSystemService(WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
        items += radio("Wi-Fi", wifi, { on -> "svc wifi ${if (on) "enable" else "disable"}" },
            if (Build.VERSION.SDK_INT >= 29) AndroidSettings.Panel.ACTION_WIFI else AndroidSettings.ACTION_WIFI_SETTINGS)
        val bt = runCatching { AndroidSettings.Global.getInt(cr, AndroidSettings.Global.BLUETOOTH_ON) == 1 }.getOrDefault(false)
        items += radio("Bluetooth", bt, { on -> val v = if (on) "enable" else "disable"; "cmd bluetooth_manager $v || svc bluetooth $v" },
            AndroidSettings.ACTION_BLUETOOTH_SETTINGS)
        val saver = runCatching { ctx.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true }.getOrDefault(false)
        items += radio("Battery saver", saver, { on -> val v = if (on) 1 else 0; "cmd power set-mode $v || settings put global low_power $v" },
            AndroidSettings.ACTION_BATTERY_SAVER_SETTINGS)

        if (!canWrite() && !root()) {
            items += action("Allow changing system settings", "needed for brightness & timeout") {
                open(Intent(AndroidSettings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }
        }
        items += action("Display settings", "Android") { open(Intent(AndroidSettings.ACTION_DISPLAY_SETTINGS)) }
        items += action("Sound settings", "Android") { open(Intent(AndroidSettings.ACTION_SOUND_SETTINGS)) }

        list.set(items, false)
        list.active = keep.coerceIn(0, (list.size - 1).coerceAtLeast(0))
    }

    /** A switch that needs root ([cmd] for on / off); without root it opens [panel] instead. */
    private fun radio(label: String, on: Boolean, cmd: (Boolean) -> String, panel: String): UiItem =
        if (root()) toggle(label, { on }) { want ->
            Thread { Root.exec(cmd(want)); main.postDelayed({ build(); host.invalidate() }, 600) }.start()
        } else action(label, if (on) "on" else "off") { open(Intent(panel)) }

    /** An Android system setting: directly with the permission, else with root. */
    private fun putSystem(key: String, value: Int) {
        if (canWrite()) runCatching { AndroidSettings.System.putInt(ctx.contentResolver, key, value) }
        else if (root()) Thread { Root.exec("settings put system $key $value") }.start()
        else host.toast("Allow MainUI to change system settings first (last row)")
    }

    private fun open(i: Intent) = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { host.toast("Not available on this device") }

    private fun timeoutLabel(ms: Int) = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min"
}
