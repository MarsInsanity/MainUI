package io.github.marsinsanity.mainui.core

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import io.github.marsinsanity.mainui.MainUI

/**
 * Haptic feedback in MainUI's menus (Settings > User interface > Haptic feedback): a short tick
 * when the selection moves, a firmer pulse for A / B and the other buttons. Handheld rumble
 * motors need a few milliseconds to be felt at all, so these are plain timed pulses rather than
 * Android's touch-screen "ticks", and they follow MainUI's setting, not Android's touch feedback
 * switch (often off on handhelds). Pulses are spaced so a held direction doesn't turn into a buzz.
 */
object Haptics {
    /** Off, light, medium, strong: (move, press) pulse lengths in ms and amplitude 1-255. */
    private val LEVELS = mapOf(
        "light" to Triple(8L, 16L, 90),
        "medium" to Triple(14L, 26L, 170),
        "strong" to Triple(22L, 40L, 255),
    )

    @Volatile private var last = 0L

    private val vibrator: Vibrator? by lazy {
        val ctx: Context = MainUI.app
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
        }.getOrNull()?.takeIf { it.hasVibrator() }
    }

    /** The selection moved (d-pad, scrolling, paging). */
    fun move() = pulse(press = false)

    /** A button acted (A, B, X, Y, Start...) or a tap. */
    fun press() = pulse(press = true)

    private fun pulse(press: Boolean) {
        val (moveMs, pressMs, amp) = LEVELS[MainUI.settings.haptics] ?: return
        val now = SystemClock.uptimeMillis()
        if (!press && now - last < 45) return
        last = now
        val v = vibrator ?: return
        val ms = if (press) pressMs else moveMs
        runCatching {
            if (v.hasAmplitudeControl()) v.vibrate(VibrationEffect.createOneShot(ms, amp))
            else v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }
}
