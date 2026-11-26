package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.core.FolderGrants
import io.github.marsinsanity.mainui.core.Root

/**
 * SeedlessDS's controls: SharedPreferences "keymap", one int (a key code, any device) per DS
 * button and hotkey, in one of three profiles (the active one is `_KeyMapId`). Without
 * `_KeyMapVer` = 2 it wipes the button keys on its next start. Its sticks, hat and analog
 * triggers are fixed (d-pad, L, R). No combos: fast forward and swap screens go on R3 / L3 (L2 /
 * R2 would also press L / R through the analog triggers).
 */
internal object SeedlessMapping {
    /** DS button index to handheld button: 0 Up ... 11 Select, A on the right as on a DS. */
    private val BUTTONS = listOf(
        Role.UP, Role.DOWN, Role.LEFT, Role.RIGHT, Role.EAST, Role.SOUTH, Role.NORTH, Role.WEST,
        Role.L1, Role.R1, Role.START, Role.SELECT,
    )

    /** (key, value) ints for profile [profile]. */
    fun entries(p: ControlsProfile, profile: Int, saveSpec: String?, loadSpec: String?): List<Pair<String, Int>> {
        val pre = if (profile == 0) "" else "p${profile}_"
        val e = mutableListOf("_KeyMapVer" to 2)
        BUTTONS.forEachIndexed { i, r -> e += "${pre}k_$i" to p.key(r) }
        e += "${pre}s_1" to p.key(Role.R3) // fast forward (toggle)
        e += "${pre}s_4" to p.key(Role.L3) // swap screens
        KeySpec.codes(saveSpec)?.lastOrNull()?.let { e += "${pre}s_2" to it } // quick save
        KeySpec.codes(loadSpec)?.lastOrNull()?.let { e += "${pre}s_3" to it } // quick load
        return e
    }
}

object SeedlessSetup : EmulatorSetup {
    override val name = "SeedlessDS"
    private val PACKAGES = listOf("com.seedlessds.app.mainui" to "SeedlessDS (MainUI build)", "com.seedlessds.app" to "SeedlessDS")

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val prefsDir = "${SetupFiles.appData(pkg)}/shared_prefs"
        Root.forceStop(pkg)
        if (!Root.dirExists(prefsDir)) throw SetupProblem("Start $label once, then set up again.")
        val (save, load) = SetupFiles.saveLoadKeys("seedlessds")
        SetupFiles.editPrefs("$prefsDir/keymap.xml") { prefs ->
            val profile = prefs.value("_KeyMapId")?.toIntOrNull()?.takeIf { it in 0..2 } ?: 0
            for ((k, v) in SeedlessMapping.entries(p, profile, save, load)) prefs.putInt(k, v)
        }
        if (p.hideTouchControls) SetupFiles.editPrefs("$prefsDir/ctrl_layout.xml") { TouchControls.seedless(it) }
        val wanted = RomFolders.dirs(listOf("seedlessds"))
        val folders = RomFolders.grant(pkg, wanted)
        if (folders.isNotEmpty()) SetupFiles.editPrefs("$prefsDir/rom_library.xml") { RomFolders.seedless(it, folders.map(FolderGrants::treeUri)) }
        return "controller, fast forward on R3, swap screens on L3" + (if (p.hideTouchControls) ", touch controls off" else "") +
            RomFolders.message(folders, wanted)
    }
}
