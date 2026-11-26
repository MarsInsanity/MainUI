package io.github.marsinsanity.mainui.emu.setup

import android.view.MotionEvent
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.emu.RetroArchCores

/**
 * RetroArch's player 1 binds in retroarch.cfg: Android key codes, "h0up" for a hat, "+N" / "-N"
 * for an axis by RetroArch's own numbering (0 X, 1 Y, 2 Z, 3 RZ, 6 LTRIGGER, 7 RTRIGGER, 8 BRAKE,
 * 9 GAS; it reads no RX / RY). A bind set here wins over the controller's autoconfig profile.
 */
internal object RetroArchMapping {
    private fun q(v: Any) = "\"$v\""

    private fun axis(androidAxis: Int?): Int? = when (androidAxis) {
        MotionEvent.AXIS_LTRIGGER -> 6
        MotionEvent.AXIS_RTRIGGER -> 7
        MotionEvent.AXIS_BRAKE -> 8
        MotionEvent.AXIS_GAS -> 9
        else -> null
    }

    fun binds(p: ControlsProfile, pad: PadInfo): Map<String, String> {
        val m = linkedMapOf<String, String>()
        fun btn(name: String, r: Role) { m["input_player1_${name}_btn"] = q(p.key(r)) }
        // RetroPad: B at the bottom, A on the right.
        btn("b", Role.SOUTH); btn("a", Role.EAST); btn("y", Role.WEST); btn("x", Role.NORTH)
        btn("start", Role.START); btn("select", Role.SELECT)
        btn("l", Role.L1); btn("r", Role.R1); btn("l2", Role.L2); btn("r2", Role.R2); btn("l3", Role.L3); btn("r3", Role.R3)
        for ((name, r) in listOf("up" to Role.UP, "down" to Role.DOWN, "left" to Role.LEFT, "right" to Role.RIGHT))
            m["input_player1_${name}_btn"] = q(if (pad.hasHat && p.key(r) == r.defaultKey) "h0$name" else p.key(r))
        for ((name, r, a) in listOf(Triple("l2", Role.L2, pad.leftTrigger), Triple("r2", Role.R2, pad.rightTrigger)))
            m["input_player1_${name}_axis"] = axis(a)?.takeIf { p.key(r) == r.defaultKey }?.let { q("+$it") } ?: q("nul")
        if (pad.hasLeftStick) {
            m["input_player1_l_x_plus_axis"] = q("+0"); m["input_player1_l_x_minus_axis"] = q("-0")
            m["input_player1_l_y_plus_axis"] = q("+1"); m["input_player1_l_y_minus_axis"] = q("-1")
        }
        if (pad.rightStick == MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ) {
            m["input_player1_r_x_plus_axis"] = q("+2"); m["input_player1_r_x_minus_axis"] = q("-2")
            m["input_player1_r_y_plus_axis"] = q("+3"); m["input_player1_r_y_minus_axis"] = q("-3")
        }
        m["input_enable_hotkey_btn"] = q(p.hotkeyButton)
        m["input_toggle_fast_forward_btn"] = q(p.hotkey(Hotkey.FAST_FORWARD))
        m["input_screenshot_btn"] = q(p.hotkey(Hotkey.SCREENSHOT))
        // As Configure RetroArch sets them: MainUI's keyboard hotkeys must not wait for an enable key.
        m["input_enable_hotkey"] = q("nul")
        m["input_hotkey_device_merge"] = q("false")
        return m
    }
}

object RetroArchSetup : EmulatorSetup {
    override val name = "RetroArch"

    override fun installed(): List<Pair<String, String>> = listOfNotNull(MainUI.emulators.raPackage?.let { it to "RetroArch" })

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        // (It writes its config when it quits.)
        Root.forceStop(pkg)
        val binds = RetroArchMapping.binds(p, pad)
        SetupFiles.editText(MainUI.emulators.raConfigPath(pkg)) { old ->
            RetroArchCores.mergeConfig(old, binds)
                ?: throw SetupProblem("Open $label from its icon, quit it from its menu (Quit RetroArch), then set up again.")
        }
        return "player 1 controller, fast forward and screenshot on the hotkey button"
    }
}
