package io.github.marsinsanity.mainui.emu.setup

import android.view.MotionEvent

/**
 * Dolphin's controller settings for a [ControlsProfile] on the handheld's [PadInfo], as INI
 * entries ("Buttons/A" to "`Button A`"). Names follow Dolphin's Android input backend: a controller
 * is "Android/<id>/<name>", its keys have the names in [DolphinKeyNames], its axes are "Axis 0+"
 * and "Axis 0-", and the phone's own sensors and vibrator are the device "Android/0/Device Sensors".
 */
internal object DolphinMapping {
    const val SENSORS = "Android/0/Device Sensors"
    /** MainUI's virtual keyboard, which presses the save / load hotkeys (a keyboard: Dolphin id 0). */
    const val KEYBOARD = "Android/0/${PadInfo.MAINUI_KEYBOARD}"

    /** Dolphin numbers a controller by Android's controller number, others from 0. */
    fun device(pad: PadInfo) = "Android/${pad.controllerNumber.coerceAtLeast(0)}/${pad.name}"

    private fun key(code: Int): String? = DolphinKeyNames.of(code)?.let { "`$it`" }
    private fun axis(n: Int, positive: Boolean) = "`Axis $n${if (positive) '+' else '-'}`"
    private fun sensor(name: String) = "`$SENSORS:$name`"
    private fun any(vararg e: String?) = e.filterNotNull().filter { it.isNotEmpty() }.joinToString(" | ")

    /** The handheld button [r] (the profile's key, and the matching trigger or hat axis it also has). */
    fun btn(p: ControlsProfile, pad: PadInfo, r: Role): String {
        val code = p.key(r)
        val extra = if (code != r.defaultKey) null else when (r) {
            Role.L2 -> pad.leftTrigger?.let { axis(it, true) }
            Role.R2 -> pad.rightTrigger?.let { axis(it, true) }
            Role.UP -> if (pad.hasHat) axis(MotionEvent.AXIS_HAT_Y, false) else null
            Role.DOWN -> if (pad.hasHat) axis(MotionEvent.AXIS_HAT_Y, true) else null
            Role.LEFT -> if (pad.hasHat) axis(MotionEvent.AXIS_HAT_X, false) else null
            Role.RIGHT -> if (pad.hasHat) axis(MotionEvent.AXIS_HAT_X, true) else null
            else -> null
        }
        return any(key(code), extra)
    }

    /** The hotkey button held with [h]'s button; empty when either has no Dolphin name. */
    fun combo(p: ControlsProfile, h: Hotkey): String {
        val a = key(p.hotkeyButton) ?: return ""
        val b = key(p.hotkey(h)) ?: return ""
        return "$a & $b"
    }

    /** A key spec of MainUI's ("keys:KEYCODE_F1", "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4") on its virtual keyboard. */
    fun keyboard(spec: String?): String? {
        val keyNames = KeySpec.codes(spec)?.map { DolphinKeyNames.of(it) ?: return null } ?: return null
        return keyNames.joinToString(" & ") { "`$KEYBOARD:$it`" }
    }

    private fun stick(prefix: String, x: Int, y: Int, extra: (Role) -> String? = { null }) = listOf(
        "$prefix/Up" to any(axis(y, false), extra(Role.UP)),
        "$prefix/Down" to any(axis(y, true), extra(Role.DOWN)),
        "$prefix/Left" to any(axis(x, false), extra(Role.LEFT)),
        "$prefix/Right" to any(axis(x, true), extra(Role.RIGHT)),
    )

    private fun dpad(p: ControlsProfile, pad: PadInfo, prefix: String, alsoStick: Boolean = false) = listOf(
        Role.UP to "Up", Role.DOWN to "Down", Role.LEFT to "Left", Role.RIGHT to "Right",
    ).map { (r, n) ->
        val stick = if (!alsoStick || !pad.hasLeftStick) null else when (r) {
            Role.UP -> axis(MotionEvent.AXIS_Y, false)
            Role.DOWN -> axis(MotionEvent.AXIS_Y, true)
            Role.LEFT -> axis(MotionEvent.AXIS_X, false)
            else -> axis(MotionEvent.AXIS_X, true)
        }
        "$prefix/$n" to any(btn(p, pad, r), stick)
    }

    private fun motor(pad: PadInfo) = if (pad.hasVibrator) listOf("Rumble/Motor" to sensor("Motor 0")) else emptyList()

    /** GameCube controller 1 (GCPadNew.ini [GCPad1]): Dolphin's own gamepad layout. */
    fun gcPad(p: ControlsProfile, pad: PadInfo): List<Pair<String, String>> {
        val e = mutableListOf("Device" to device(pad))
        e += "Buttons/A" to btn(p, pad, Role.SOUTH)
        e += "Buttons/B" to btn(p, pad, Role.EAST)
        e += "Buttons/X" to btn(p, pad, Role.WEST)
        e += "Buttons/Y" to btn(p, pad, Role.NORTH)
        e += "Buttons/Z" to btn(p, pad, Role.R1)
        e += "Buttons/Start" to btn(p, pad, Role.START)
        if (pad.hasLeftStick) e += stick("Main Stick", MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
        pad.rightStick?.let { (x, y) -> e += stick("C-Stick", x, y) }
        e += "Triggers/L" to btn(p, pad, Role.L2)
        e += "Triggers/R" to btn(p, pad, Role.R2)
        e += "Triggers/L-Analog" to (pad.leftTrigger?.takeIf { p.key(Role.L2) == Role.L2.defaultKey }?.let { axis(it, true) } ?: btn(p, pad, Role.L2))
        e += "Triggers/R-Analog" to (pad.rightTrigger?.takeIf { p.key(Role.R2) == Role.R2.defaultKey }?.let { axis(it, true) } ?: btn(p, pad, Role.R2))
        e += dpad(p, pad, "D-Pad")
        e += motor(pad)
        return e.filter { it.second.isNotEmpty() }
    }

    private val ACCEL = listOf("Up", "Down", "Left", "Right", "Forward", "Backward")
    private val GYRO = listOf("Pitch Up", "Pitch Down", "Roll Left", "Roll Right", "Yaw Left", "Yaw Right")

    /**
     * Wii Remote 1 played as [type] (WiimoteNew.ini [Wiimote1], and each MainUI profile's [Profile]).
     * The handheld's accelerometer and gyroscope become the Wii Remote's (Dolphin's own Android
     * mapping), so shaking and tilting the handheld shake and tilt the Wii Remote.
     */
    fun wiimote(type: WiiController, p: ControlsProfile, pad: PadInfo): List<Pair<String, String>> {
        val e = mutableListOf("Device" to device(pad))
        val remote = type == WiiController.NUNCHUK || type == WiiController.SIDEWAYS
        when (type) {
            WiiController.NUNCHUK -> {
                e += "Buttons/A" to btn(p, pad, Role.SOUTH)
                e += "Buttons/B" to any(btn(p, pad, Role.R2), btn(p, pad, Role.EAST))
                e += "Buttons/1" to btn(p, pad, Role.WEST)
                e += "Buttons/2" to btn(p, pad, Role.NORTH)
            }
            WiiController.SIDEWAYS -> {
                // Held like an NES pad: 2 on the right, 1 on the left.
                e += "Buttons/A" to btn(p, pad, Role.NORTH)
                e += "Buttons/B" to any(btn(p, pad, Role.WEST), btn(p, pad, Role.R2))
                e += "Buttons/1" to btn(p, pad, Role.SOUTH)
                e += "Buttons/2" to btn(p, pad, Role.EAST)
            }
            else -> {}
        }
        if (remote) {
            e += "Buttons/-" to btn(p, pad, Role.SELECT)
            e += "Buttons/+" to btn(p, pad, Role.START)
        }
        e += "Buttons/Home" to combo(p, Hotkey.WII_HOME)
        if (remote) {
            e += dpad(p, pad, "D-Pad", alsoStick = type == WiiController.SIDEWAYS)
            val shake = if (type == WiiController.SIDEWAYS) any(btn(p, pad, Role.R1), btn(p, pad, Role.L1)) else btn(p, pad, Role.R1)
            for (a in listOf("X", "Y", "Z")) e += "Shake/$a" to shake
            if (p.wiiPointer == WiiPointer.STICK) pad.rightStick?.let { (x, y) -> e += stick("IR", x, y) }
        }
        val motionPointer = remote && p.wiiPointer == WiiPointer.MOTION && pad.hasMotion
        if (remote && pad.hasMotion && (p.wiiMotion || motionPointer)) {
            ACCEL.forEach { e += "IMUAccelerometer/$it" to sensor("Accel $it") }
            GYRO.forEach { e += "IMUGyroscope/$it" to sensor("Gyro $it") }
        }
        e += "IMUIR/Enabled" to if (motionPointer) "True" else "False"
        if (motionPointer) e += "IMUIR/Recenter" to combo(p, Hotkey.WII_RECENTER)
        if (remote) e += "Hotkeys/Sideways Toggle" to combo(p, Hotkey.WII_SIDEWAYS)
        e += "Extension" to when (type) {
            WiiController.NUNCHUK -> "Nunchuk"
            WiiController.CLASSIC -> "Classic"
            else -> "None"
        }
        when (type) {
            WiiController.NUNCHUK -> {
                e += "Nunchuk/Buttons/C" to btn(p, pad, Role.L1)
                e += "Nunchuk/Buttons/Z" to btn(p, pad, Role.L2)
                if (pad.hasLeftStick) e += stick("Nunchuk/Stick", MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
                for (a in listOf("X", "Y", "Z")) e += "Nunchuk/Shake/$a" to btn(p, pad, Role.L3)
            }
            WiiController.CLASSIC -> {
                // Laid out like the Classic Controller Pro: B at the bottom, A on the right.
                e += "Classic/Buttons/A" to btn(p, pad, Role.EAST)
                e += "Classic/Buttons/B" to btn(p, pad, Role.SOUTH)
                e += "Classic/Buttons/X" to btn(p, pad, Role.NORTH)
                e += "Classic/Buttons/Y" to btn(p, pad, Role.WEST)
                e += "Classic/Buttons/ZL" to btn(p, pad, Role.L2)
                e += "Classic/Buttons/ZR" to btn(p, pad, Role.R2)
                e += "Classic/Buttons/-" to btn(p, pad, Role.SELECT)
                e += "Classic/Buttons/+" to btn(p, pad, Role.START)
                e += "Classic/Buttons/Home" to combo(p, Hotkey.WII_HOME)
                if (pad.hasLeftStick) e += stick("Classic/Left Stick", MotionEvent.AXIS_X, MotionEvent.AXIS_Y)
                pad.rightStick?.let { (x, y) -> e += stick("Classic/Right Stick", x, y) }
                e += "Classic/Triggers/L" to btn(p, pad, Role.L1)
                e += "Classic/Triggers/R" to btn(p, pad, Role.R1)
                e += "Classic/Triggers/L-Analog" to btn(p, pad, Role.L1)
                e += "Classic/Triggers/R-Analog" to btn(p, pad, Role.R1)
                e += dpad(p, pad, "Classic/D-Pad")
            }
            else -> {}
        }
        if (type == WiiController.SIDEWAYS) e += "Options/Sideways Wiimote" to "True"
        e += motor(pad)
        return e.filter { it.second.isNotEmpty() }
    }

    /**
     * Hotkeys.ini [Hotkeys] (Dolphin 2026-09 and newer read it on Android): fast forward and
     * screenshot on the hotkey combos, and slot 1's save / load on MainUI's keyboard keys, which
     * MainUI presses for Save / Load in its in-game menu when Dolphin has no State API.
     */
    fun hotkeys(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): List<Pair<String, String>> = listOf(
        "Device" to device(pad),
        "General/Take Screenshot" to combo(p, Hotkey.SCREENSHOT),
        "Emulation Speed/Disable Emulation Speed Limit" to combo(p, Hotkey.FAST_FORWARD),
        "Save State/Save State Slot 1" to (keyboard(saveSpec) ?: ""),
        "Load State/Load State Slot 1" to (keyboard(loadSpec) ?: ""),
    ).filter { it.second.isNotEmpty() }
}
