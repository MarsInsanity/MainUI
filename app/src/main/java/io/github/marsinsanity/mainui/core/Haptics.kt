package io.github.marsinsanity.mainui.core

import android.content.Context
import android.os.Build
import android.os.CombinedVibration
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.InputDevice
import io.github.marsinsanity.mainui.MainUI

/**
 * Haptic feedback in MainUI's menus (Settings > User interface > Haptic feedback): a short tick
 * when the selection moves, a firmer pulse for A / B and the other buttons.
 *
 * Handhelds differ in where their rumble motor is: some expose it as Android's vibrator, others
 * only through the built-in controller (the input device's vibrator, the way games rumble), and
 * some report a system vibrator that isn't connected to anything. So every motor found is pulsed
 * (on one body they are the same motor). Rumble motors need ~20 ms to spin up, so pulses are
 * timed, not Android's touch "ticks", and they are tagged as media vibration (game rumble), not
 * touch feedback, which handhelds often have switched off. Pulses are spaced so a held direction
 * doesn't turn into a buzz. MainUI's own setting decides whether they happen.
 */
object Haptics {
    private const val TAG = "MainUI.Haptics"

    /** Off, light, medium, strong: (move, press) pulse lengths in ms and amplitude 1-255. */
    private val LEVELS = mapOf(
        "light" to Triple(18L, 30L, 120),
        "medium" to Triple(26L, 45L, 190),
        "strong" to Triple(38L, 65L, 255),
    )

    @Volatile private var last = 0L

    /** Something that can vibrate: Android's vibrator or a controller's motors. */
    private class Motor(val name: String, val amplitude: Boolean, val pulse: (VibrationEffect) -> Unit)

    @Volatile private var motors: List<Motor>? = null
    @Volatile private var foundAt = 0L

    /** The motors, looked up again now and then (a controller can come and go). */
    private fun motors(): List<Motor> {
        val now = SystemClock.uptimeMillis()
        motors?.let { if (now - foundAt < 30_000) return it }
        return find().also { motors = it; foundAt = now; Log.i(TAG, "motors: ${it.joinToString { m -> m.name }.ifEmpty { "none" }}") }
    }

    private fun attrs(): Any? = if (Build.VERSION.SDK_INT >= 33) VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA) else null

    private fun find(): List<Motor> {
        val ctx: Context = MainUI.app
        val out = ArrayList<Motor>()
        runCatching {
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            if (v != null && v.hasVibrator()) out += Motor("system", v.hasAmplitudeControl()) { e -> vibrate(v, e) }
        }
        // The built-in controller's motors (the way games rumble).
        for (id in InputDevice.getDeviceIds()) runCatching {
            val d = InputDevice.getDevice(id) ?: return@runCatching
            val pad = d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK)
            if (!pad || (Build.VERSION.SDK_INT >= 29 && d.isExternal)) return@runCatching
            if (Build.VERSION.SDK_INT >= 31) {
                val vm = d.vibratorManager
                if (vm.vibratorIds.isNotEmpty()) out += Motor("controller ${d.name}",
                    vm.vibratorIds.all { vm.getVibrator(it).hasAmplitudeControl() }) { e ->
                    val c = CombinedVibration.createParallel(e)
                    if (Build.VERSION.SDK_INT >= 33) vm.vibrate(c, attrs() as VibrationAttributes) else vm.vibrate(c)
                }
            } else {
                @Suppress("DEPRECATION") val v = d.vibrator
                if (v.hasVibrator()) out += Motor("controller ${d.name}", v.hasAmplitudeControl()) { e -> vibrate(v, e) }
            }
        }
        return out
    }

    private fun vibrate(v: Vibrator, e: VibrationEffect) {
        if (Build.VERSION.SDK_INT >= 33) v.vibrate(e, attrs() as VibrationAttributes) else v.vibrate(e)
    }

    /** True when there is something to vibrate with (for the setting's hint). */
    fun available(): Boolean = motors().isNotEmpty()

    /** The selection moved (d-pad, scrolling, paging). */
    fun move() = pulse(press = false)

    /** A button acted (A, B, X, Y, Start...) or a tap. */
    fun press() = pulse(press = true)

    private fun pulse(press: Boolean) {
        val (moveMs, pressMs, amp) = LEVELS[MainUI.settings.haptics] ?: return
        val now = SystemClock.uptimeMillis()
        if (!press && now - last < 45) return
        last = now
        val ms = if (press) pressMs else moveMs
        for (m in motors()) runCatching {
            // Motors without amplitude control run at their own strength.
            m.pulse(VibrationEffect.createOneShot(ms, if (m.amplitude) amp else VibrationEffect.DEFAULT_AMPLITUDE))
        }.onFailure { Log.w(TAG, "${m.name}: ${it.message}") }
    }
}
