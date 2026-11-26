package io.github.marsinsanity.mainui.emu.setup

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Vibrator
import android.view.InputDevice
import android.view.MotionEvent

/** What emulator setup needs to know about the handheld's own controller and sensors. */
data class PadInfo(
    /** Android's name for the built-in controller ("Retroid Pocket Controller"). */
    val name: String,
    /** Android's controller number (1 for the first gamepad; 0 when it has none). */
    val controllerNumber: Int,
    /** The motion axes it reports (MotionEvent.AXIS_*). */
    val axes: Set<Int>,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasVibrator: Boolean,
) {
    fun has(axis: Int) = axis in axes

    /** The right stick's (x, y) axes, null without one. */
    val rightStick: Pair<Int, Int>? get() = when {
        has(MotionEvent.AXIS_Z) && has(MotionEvent.AXIS_RZ) -> MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ
        has(MotionEvent.AXIS_RX) && has(MotionEvent.AXIS_RY) -> MotionEvent.AXIS_RX to MotionEvent.AXIS_RY
        else -> null
    }

    val hasLeftStick get() = has(MotionEvent.AXIS_X) && has(MotionEvent.AXIS_Y)
    val hasHat get() = has(MotionEvent.AXIS_HAT_X) && has(MotionEvent.AXIS_HAT_Y)
    val leftTrigger: Int? get() = listOf(MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE).firstOrNull { has(it) }
    val rightTrigger: Int? get() = listOf(MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS).firstOrNull { has(it) }
    val hasMotion get() = hasAccelerometer && hasGyroscope

    companion object {
        /** MainUI's own virtual keyboard (VirtualKeys), never the handheld's controller. */
        const val MAINUI_KEYBOARD = "MainUI Keyboard"

        private val AXES = listOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y, MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER,
            MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
        )

        /**
         * The handheld's built-in controller: a gamepad that isn't virtual, the built-in one before a
         * plugged in one (Android 10+ tells them apart). Null when there is none.
         */
        fun detect(ctx: Context): PadInfo? {
            val pads = InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }.filter { d: InputDevice ->
                !d.isVirtual && d.name != MAINUI_KEYBOARD &&
                    (d.sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
                        d.sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK)
            }
            val pad = pads.sortedWith(compareBy<InputDevice>(
                { Build.VERSION.SDK_INT >= 29 && it.isExternal },
                { it.controllerNumber == 0 },
                { it.controllerNumber },
            )).firstOrNull() ?: return null
            val sm = ctx.getSystemService(SensorManager::class.java)
            @Suppress("DEPRECATION")
            val vib = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            return PadInfo(
                name = pad.name,
                controllerNumber = pad.controllerNumber,
                axes = AXES.filter { pad.getMotionRange(it, InputDevice.SOURCE_JOYSTICK) != null }.toSet(),
                hasAccelerometer = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null,
                hasGyroscope = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null,
                hasVibrator = vib?.hasVibrator() == true,
            )
        }
    }
}
