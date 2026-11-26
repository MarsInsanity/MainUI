package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.core.FolderGrants
import io.github.marsinsanity.mainui.core.Root

/**
 * ARMSX2's controls: one SharedPreferences file, shared_prefs/ARMSX2.xml, whose `pad.map.*` ints
 * are Android key codes from any device (pad 1, all games) and whose `pad.<hotkey>.keycode` /
 * `.mod` ints are a hotkey's button and the button held with it. Its sticks and the hat d-pad
 * are fixed: the d-pad keys stay on the d-pad (unbinding them turns the hat off), and L2 / R2
 * stay on L2 / R2 (the analog triggers only drive those).
 */
internal object Armsx2Mapping {
    /** pad.map.<name> to the handheld button (a DualShock 2 laid out by position). */
    private val PAD = listOf(
        "dpad_up" to Role.UP, "dpad_down" to Role.DOWN, "dpad_left" to Role.LEFT, "dpad_right" to Role.RIGHT,
        "cross" to Role.SOUTH, "circle" to Role.EAST, "square" to Role.WEST, "triangle" to Role.NORTH,
        "l1" to Role.L1, "r1" to Role.R1, "l2" to Role.L2, "r2" to Role.R2, "l3" to Role.L3, "r3" to Role.R3,
        "select" to Role.SELECT, "start" to Role.START,
    )

    /** The ints and booleans to write: (key, value). */
    fun entries(p: ControlsProfile, saveSpec: String?, loadSpec: String?): List<Pair<String, Any>> {
        val e = mutableListOf<Pair<String, Any>>()
        for ((name, role) in PAD) e += "pad.map.$name" to p.key(role)
        fun hotkey(pref: String, key: Int, mod: Int) { e += "$pref.keycode" to key; e += "$pref.keycode.mod" to mod }
        hotkey("pad.fastforwardtoggle", p.hotkey(Hotkey.FAST_FORWARD), p.hotkeyButton)
        hotkey("pad.screenshot", p.hotkey(Hotkey.SCREENSHOT), p.hotkeyButton)
        // Slot 0's save / load on MainUI's keyboard keys (F1 / F3): pressed for Save / Load in its
        // in-game menu when ARMSX2 has no State API. A two-key spec is a combo.
        fun keys(pref: String, spec: String?) {
            val codes = KeySpec.codes(spec) ?: return
            hotkey(pref, codes.last(), if (codes.size > 1) codes.first() else 0)
        }
        keys("pad.saveslot0", saveSpec)
        keys("pad.loadslot0", loadSpec)
        // Holding Back quit ARMSX2 after 0.7 s: holding Back opens MainUI's process manager.
        e += "ui.holdBackToExit" to false
        return e
    }
}

object Armsx2Setup : EmulatorSetup {
    override val name = "ARMSX2"
    private val PACKAGES = listOf(
        "com.armsx2.mainui" to "ARMSX2 (MainUI build)", "com.armsx2" to "ARMSX2",
        "com.armsx2.nightly" to "ARMSX2 Nightly", "come.nanodata.armsx2" to "ARMSX2 (nanodata)",
    )

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val path = "${SetupFiles.appData(pkg)}/shared_prefs/ARMSX2.xml"
        Root.forceStop(pkg)
        if (Root.fileExists(path) != true && Root.fileExists("$path.bak") != true)
            throw SetupProblem("Start $label once (it makes its settings), then set up again. Older ARMSX2 builds keep their controls elsewhere and can't be set up.")
        val (save, load) = SetupFiles.saveLoadKeys("armsx2")
        // (Granted first: it goes back to its setup wizard when none of its folders opens.)
        val wanted = RomFolders.dirs(listOf("armsx2"))
        val folders = RomFolders.grant(pkg, wanted)
        SetupFiles.editPrefs(path) { prefs ->
            if (folders.isNotEmpty()) RomFolders.armsx2(prefs, folders.map(FolderGrants::treeUri))
            for ((k, v) in Armsx2Mapping.entries(p, save, load)) when (v) {
                is Int -> prefs.putInt(k, v)
                is Boolean -> prefs.putBoolean(k, v)
            }
            if (p.hideTouchControls) TouchControls.armsx2(prefs)
        }
        return "controller, hotkeys, save / load keys" + (if (p.hideTouchControls) ", touch controls off" else "") + RomFolders.message(folders, wanted)
    }
}
