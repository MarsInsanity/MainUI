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
    /** Android's descriptor for it: the same across reboots and reconnects. */
    val descriptor: String = "",
    /** Its joystick axes that rest at 0 (triggers): MotionRange min 0. */
    val halfAxes: Set<Int> = emptySet(),
    /** The sticks as the controls profile has them ([withSticks]). */
    val leftStickChoice: Stick = Stick.AUTO,
    val rightStickChoice: Stick = Stick.AUTO,
) {
    fun has(axis: Int) = axis in axes

    /** The first of [candidates] it reports, not the [other] stick's. */
    private fun auto(candidates: List<Stick>, other: Stick): Pair<Int, Int>? =
        candidates.firstOrNull { s -> s != other && s.axes?.let { has(it.first) && has(it.second) } == true }?.axes

    /** The left stick's (x, y) axes, null without one. */
    val leftStick: Pair<Int, Int>? get() = leftStickChoice.axes ?: auto(listOf(Stick.XY), rightStickChoice)

    /** The right stick's (x, y) axes, null without one. */
    val rightStick: Pair<Int, Int>? get() = rightStickChoice.axes ?: auto(listOf(Stick.Z_RZ, Stick.RX_RY), leftStickChoice)

    val hasLeftStick get() = leftStick != null

    fun withSticks(p: ControlsProfile) = copy(leftStickChoice = p.leftStick, rightStickChoice = p.rightStick)
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
                descriptor = pad.descriptor.orEmpty(),
                halfAxes = pad.motionRanges.filter {
                    it.source and InputDevice.SOURCE_CLASS_MASK == InputDevice.SOURCE_CLASS_JOYSTICK && it.min == 0f
                }.map { it.axis }.toSet(),
            )
        }
    }
}
