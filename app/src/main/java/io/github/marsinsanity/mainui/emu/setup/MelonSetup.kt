package io.github.marsinsanity.mainui.emu.setup

import android.view.MotionEvent
import io.github.marsinsanity.mainui.core.FolderGrants
import io.github.marsinsanity.mainui.core.Root

/**
 * melonDS Android's controls: files/controller_config.json, every input with an assignment (a key
 * code or an axis direction, any device) and an optional second one. Inputs it leaves out are
 * unbound, so all of them are written. It has no button combos: fast forward and swap screens go
 * on L2 / R2, which a DS doesn't have.
 */
internal object MelonMapping {
    private sealed class Assign {
        data class Key(val code: Int) : Assign()
        data class Axis(val axis: Int, val positive: Boolean) : Assign()
        object None : Assign()
    }

    private fun json(a: Assign): String = when (a) {
        is Assign.Key -> "{\"type\":\"key\",\"keyCode\":${a.code}}"
        is Assign.Axis -> "{\"type\":\"axis\",\"axisCode\":${a.axis},\"direction\":\"${if (a.positive) "POSITIVE" else "NEGATIVE"}\"}"
        Assign.None -> "{\"type\":\"none\"}"
    }

    /** The handheld button [r], and its trigger axis for L2 / R2. */
    private fun button(p: ControlsProfile, pad: PadInfo, r: Role): Pair<Assign, Assign> {
        val code = p.key(r)
        val axis = if (code != r.defaultKey) null else when (r) {
            Role.L2 -> pad.leftTrigger
            Role.R2 -> pad.rightTrigger
            else -> null
        }
        return Assign.Key(code) to (axis?.let { Assign.Axis(it, true) } ?: Assign.None)
    }

    /**
     * A d-pad direction: the hat when the handheld has one (melonDS then takes every stick and hat
     * event, so Android never turns the hat into d-pad keys), else the key; the left stick second.
     */
    private fun direction(p: ControlsProfile, pad: PadInfo, r: Role, hatAxis: Int, vertical: Boolean, positive: Boolean): Pair<Assign, Assign> {
        val first = if (pad.hasHat && p.key(r) == r.defaultKey) Assign.Axis(hatAxis, positive) else Assign.Key(p.key(r))
        val second = pad.leftStick?.let { (x, y) -> Assign.Axis(if (vertical) y else x, positive) } ?: Assign.None
        return first to second
    }

    fun json(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): String {
        fun key(spec: String?): Pair<Assign, Assign> =
            (KeySpec.codes(spec)?.lastOrNull()?.let { Assign.Key(it) } ?: Assign.None) to Assign.None
        val none = Assign.None to Assign.None
        val inputs = listOf(
            // A DS is laid out like a Nintendo pad: A on the right, B at the bottom.
            "A" to button(p, pad, Role.EAST),
            "B" to button(p, pad, Role.SOUTH),
            "X" to button(p, pad, Role.NORTH),
            "Y" to button(p, pad, Role.WEST),
            "LEFT" to direction(p, pad, Role.LEFT, MotionEvent.AXIS_HAT_X, vertical = false, positive = false),
            "RIGHT" to direction(p, pad, Role.RIGHT, MotionEvent.AXIS_HAT_X, vertical = false, positive = true),
            "UP" to direction(p, pad, Role.UP, MotionEvent.AXIS_HAT_Y, vertical = true, positive = false),
            "DOWN" to direction(p, pad, Role.DOWN, MotionEvent.AXIS_HAT_Y, vertical = true, positive = true),
            "L" to button(p, pad, Role.L1),
            "R" to button(p, pad, Role.R1),
            "START" to button(p, pad, Role.START),
            "SELECT" to button(p, pad, Role.SELECT),
            "HINGE" to none,
            "PAUSE" to (Assign.Key(android.view.KeyEvent.KEYCODE_BUTTON_MODE) to Assign.None),
            "FAST_FORWARD" to button(p, pad, Role.R2),
            "MICROPHONE" to none,
            "RESET" to none,
            "SWAP_SCREENS" to button(p, pad, Role.L2),
            // MainUI's save / load keys (F1 / F2), for its in-game menu without the State API.
            "QUICK_SAVE" to key(saveSpec),
            "QUICK_LOAD" to key(loadSpec),
            "REWIND" to none,
        )
        return inputs.joinToString(",", "{\"inputMapper\":[", "]}") { (name, a) ->
            "{\"input\":\"$name\",\"assignment\":${json(a.first)}" +
                (if (a.second != Assign.None) ",\"altAssignment\":${json(a.second)}" else "") + "}"
        }
    }
}

object MelonSetup : EmulatorSetup {
    override val name = "melonDS"
    private val PACKAGES = listOf(
        "me.magnum.melonds.mainui" to "melonDS (MainUI build)", "me.magnum.melonds" to "melonDS",
        "me.magnum.melonds.nightly" to "melonDS Nightly",
    )

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val data = SetupFiles.appData(pkg)
        Root.forceStop(pkg)
        // Its first start runs a migration that deletes a controller_config.json it can't read as
        // its old format: written only once it has started (its version is in its settings).
        val prefs = Root.readText("$data/shared_prefs/${pkg}_preferences.xml")
        if (prefs == null || !prefs.contains("\"last_version\""))
            throw SetupProblem("Start $label once, then set up again.")
        val (save, load) = SetupFiles.saveLoadKeys("melonds")
        SetupFiles.editText("$data/files/controller_config.json") { MelonMapping.json(p, pad, save, load) }
        val wanted = RomFolders.dirs(listOf("melonds", "melonds_nightly"))
        val folders = RomFolders.grant(pkg, wanted)
        SetupFiles.editPrefs("$data/shared_prefs/${pkg}_preferences.xml") {
            if (p.hideTouchControls) TouchControls.melon(it)
            if (folders.isNotEmpty()) RomFolders.melon(it, folders.map(FolderGrants::treeUri))
        }
        return "controller, fast forward on R2, swap screens on L2, save / load keys" + (if (p.hideTouchControls) ", touch controls off" else "") +
            RomFolders.message(folders, wanted)
    }
}
