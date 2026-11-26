package io.github.marsinsanity.mainui.emu.setup

import android.view.MotionEvent
import io.github.marsinsanity.mainui.core.Root

/**
 * Mupen64Plus AE's controls: a controller profile, a section of files/Profiles/controller.cfg
 * whose "map" lists "<command>:<input>" pairs (a key code, or -(axis * 2 + 1) / -(axis * 2 + 2)
 * for an axis direction; one command per input), picked for player 1 by controllerProfile1.
 * Every controller (keyboards too) is player 1 while no player map is set. No combos: fast
 * forward goes on R2, which an N64 doesn't have.
 */
internal object MupenMapping {
    const val PROFILE = "MainUI"

    private const val DPAD_RIGHT = 0
    private const val C_RIGHT = 8
    private const val STICK_RIGHT = 16

    private fun axis(axis: Int, positive: Boolean) = -(axis * 2 + if (positive) 1 else 2)

    fun map(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): String {
        val m = mutableListOf<Pair<Int, Int>>()
        fun key(cmd: Int, r: Role) { m += cmd to p.key(r) }
        fun axes(right: Int, x: Int, y: Int) {
            // (Its commands go right, left, down, up.)
            m += right to axis(x, true); m += right + 1 to axis(x, false)
            m += right + 2 to axis(y, true); m += right + 3 to axis(y, false)
        }
        /** The d-pad (and the hat: AE doesn't turn it into d-pad keys) as [right]'s group. */
        fun dpad(right: Int) {
            key(right, Role.RIGHT); key(right + 1, Role.LEFT); key(right + 2, Role.DOWN); key(right + 3, Role.UP)
            if (pad.hasHat && listOf(Role.UP, Role.DOWN, Role.LEFT, Role.RIGHT).all { p.key(it) == it.defaultKey })
                axes(right, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y)
        }
        val rs = pad.rightStick
        pad.leftStick?.let { (x, y) -> axes(STICK_RIGHT, x, y) }
        rs?.let { (x, y) -> axes(C_RIGHT, x, y) }
        // Without a stick the d-pad is the N64's stick; without a right stick, its C buttons.
        dpad(if (!pad.hasLeftStick) STICK_RIGHT else if (rs == null) C_RIGHT else DPAD_RIGHT)
        key(7, Role.SOUTH) // A
        key(6, Role.WEST) // B
        key(10, Role.EAST) // C-down
        key(9, Role.NORTH) // C-left
        key(4, Role.START)
        key(13, Role.L1)
        key(12, Role.R1)
        key(5, Role.L2) // Z
        if (p.key(Role.L2) == Role.L2.defaultKey) pad.leftTrigger?.let { m += 5 to axis(it, true) }
        key(26, Role.R2) // fast forward, while held
        if (p.key(Role.R2) == Role.R2.defaultKey) pad.rightTrigger?.let { m += 26 to axis(it, true) }
        // MainUI's save / load keys, for its in-game menu without the State API.
        KeySpec.codes(saveSpec)?.lastOrNull()?.let { m += 21 to it }
        KeySpec.codes(loadSpec)?.lastOrNull()?.let { m += 22 to it }
        return m.joinToString(",") { "${it.first}:${it.second}" }
    }

    fun section(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): List<Pair<String, String>> = listOf(
        "comment" to "Set up by MainUI",
        "map" to map(p, pad, saveSpec, loadSpec),
        "auto_deadzone" to "true",
        "deadzone" to "15",
        "sensitivity_x" to "100",
        "sensitivity_y" to "100",
    )
}

object MupenSetup : EmulatorSetup {
    override val name = "Mupen64Plus AE"
    private val PACKAGES = listOf(
        "org.mupen64plusae.v3.alpha.mainui" to "Mupen64Plus AE (MainUI build)", "org.mupen64plusae.v3.fzurita.pro" to "Mupen64Plus FZ Pro",
        "org.mupen64plusae.v3.fzurita" to "Mupen64Plus FZ", "org.mupen64plusae.v3.fzurita.amazon" to "Mupen64Plus FZ",
        "org.mupen64plusae.v3.alpha" to "Mupen64Plus AE",
    )

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val data = SetupFiles.appData(pkg)
        val prefsPath = "$data/shared_prefs/${pkg}_preferences.xml"
        // (The game runs in a process of its own, with its own copy of the settings: both stop.)
        Root.forceStop(pkg)
        if (Root.fileExists(prefsPath) != true && Root.fileExists("$prefsPath.bak") != true) throw SetupProblem("Start $label once, then set up again.")
        val (save, load) = SetupFiles.saveLoadKeys("m64plusfz")
        SetupFiles.editIni("$data/files/Profiles/controller.cfg") { it.replaceSection(MupenMapping.PROFILE, MupenMapping.section(p, pad, save, load)) }
        var mapped = false
        SetupFiles.editPrefs(prefsPath) { prefs ->
            prefs.putString("controllerProfile1", MupenMapping.PROFILE)
            // Otherwise a load key only works held for a second, and repeats.
            prefs.putBoolean("holdButtonForMenu", false)
            mapped = prefs.value("inputShareController") != "true" &&
                (prefs.value("autoPlayerMapping") == "true" || !prefs.string("playerMap").isNullOrEmpty())
        }
        val c = if (pad.rightStick != null) "C buttons on the right stick" else if (pad.hasLeftStick) "C buttons on the d-pad" else "stick on the d-pad"
        return "controller ($c), fast forward on R2, " +
            if (mapped) "no save / load keys while its player map is set" else "save / load keys"
    }
}
