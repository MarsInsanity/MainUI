package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.core.FolderGrants
import io.github.marsinsanity.mainui.core.Root

/**
 * Flycast's controls: one mapping file per Android device, <home>/mappings/Android_<name>.cfg
 * (_arcade for Naomi / Atomiswave games), "bindN = <code>:<name>" in [digital] (key codes) and
 * [analog] ("<axis>+" / "-"), "<code>,<code>:<name>:1" combos (2.6+). Android's hat reaches it as
 * d-pad keys. Each device has its own file, MainUI's keyboard too.
 */
internal object FlycastMapping {
    fun fileName(device: String, arcade: Boolean = false): String =
        ("Android_$device" + if (arcade) "_arcade" else "").map { if (it in "/\\:?*|\"<>") '-' else it }.joinToString("") + ".cfg"

    private fun file(
        digital: List<Pair<Int, String>>, analog: List<String>, combos: List<String>,
        old: String, name: String, triggers: Set<Int>,
    ): String {
        val was = IniText(old)
        fun keep(key: String, def: String) = was.get("emulator", key)?.takeIf { it.isNotEmpty() } ?: def
        val ini = IniText("")
        if (analog.isNotEmpty()) ini.replaceSection("analog", analog.mapIndexed { i, b -> "bind$i" to b })
        if (combos.isNotEmpty()) ini.replaceSection("combo", combos.mapIndexed { i, b -> "bind$i" to b })
        if (digital.isNotEmpty()) ini.replaceSection("digital", digital.mapIndexed { i, (code, n) -> "bind$i" to "$code:$n" })
        ini.replaceSection("emulator", listOf(
            "dead_zone" to keep("dead_zone", "10"),
            "mapping_name" to name,
            "rumble_power" to keep("rumble_power", "100"),
            "saturation" to keep("saturation", "100"),
            // Flycast's own list (axes resting at 0), so it has nothing to fix and write back.
            "triggers" to triggers.sorted().joinToString(","),
            "version" to "4",
        ))
        return ini.toString()
    }

    /** The handheld's file ([old] its current text: its dead zone, rumble and saturation stay). */
    fun padFile(old: String, p: ControlsProfile, pad: PadInfo, arcade: Boolean): String {
        val d = mutableListOf(
            p.key(Role.UP) to "btn_dpad1_up", p.key(Role.DOWN) to "btn_dpad1_down",
            p.key(Role.LEFT) to "btn_dpad1_left", p.key(Role.RIGHT) to "btn_dpad1_right",
        )
        // Flycast's own layouts: a Dreamcast pad by position (A at the bottom), C / Z on R1 / L1;
        // arcade buttons 1-6 along the face buttons then L1 / R1, coin on Select.
        d += if (arcade) listOf(
            p.key(Role.SOUTH) to "btn_a", p.key(Role.EAST) to "btn_b", p.key(Role.WEST) to "btn_c",
            p.key(Role.NORTH) to "btn_x", p.key(Role.L1) to "btn_y", p.key(Role.R1) to "btn_z",
            p.key(Role.SELECT) to "btn_d",
        ) else listOf(
            p.key(Role.SOUTH) to "btn_a", p.key(Role.EAST) to "btn_b", p.key(Role.WEST) to "btn_x",
            p.key(Role.NORTH) to "btn_y", p.key(Role.L1) to "btn_z", p.key(Role.R1) to "btn_c",
        )
        d += p.key(Role.START) to "btn_start"
        d += p.key(Role.L2) to "btn_trigger_left"
        d += p.key(Role.R2) to "btn_trigger_right"
        val a = mutableListOf<String>()
        pad.leftStick?.let { (x, y) -> a += listOf("$x-:btn_analog_left", "$x+:btn_analog_right", "$y-:btn_analog_up", "$y+:btn_analog_down") }
        pad.rightStick?.let { (x, y) -> a += listOf("$x-:axis2_left", "$x+:axis2_right", "$y-:axis2_up", "$y+:axis2_down") }
        if (p.key(Role.L2) == Role.L2.defaultKey) pad.leftTrigger?.let { a += "$it+:btn_trigger_left" }
        if (p.key(Role.R2) == Role.R2.defaultKey) pad.rightTrigger?.let { a += "$it+:btn_trigger_right" }
        val c = listOf(
            "${p.hotkeyButton},${p.hotkey(Hotkey.FAST_FORWARD)}:btn_fforward:1",
            "${p.hotkeyButton},${p.hotkey(Hotkey.SCREENSHOT)}:btn_screenshot:1",
        )
        return file(d, a, c, old, "MainUI", pad.halfAxes)
    }

    /** MainUI's keyboard's file: its save / load keys (a combo for one with a modifier). */
    fun keyboardFile(old: String, saveSpec: String?, loadSpec: String?): String? {
        val d = mutableListOf<Pair<Int, String>>()
        val c = mutableListOf<String>()
        for ((spec, n) in listOf(saveSpec to "btn_quick_save", loadSpec to "btn_jump_state")) {
            val codes = KeySpec.codes(spec) ?: continue
            if (codes.size == 1) d += codes[0] to n else c += codes.joinToString(",") + ":$n:1"
        }
        if (d.isEmpty() && c.isEmpty()) return null
        return file(d, emptyList(), c, old, PadInfo.MAINUI_KEYBOARD, emptySet())
    }

    /** The first folder of emu.cfg's Dreamcast.MappingsPath (";" separated, maybe quoted); null when unset. */
    fun mappingsPath(emuCfg: String): String? =
        IniText(emuCfg).get("config", "Dreamcast.MappingsPath")?.split(';')?.firstOrNull()?.trim()?.trim('"')?.trimEnd('/')?.ifEmpty { null }
}

object FlycastSetup : EmulatorSetup {
    override val name = "Flycast"
    private val PACKAGES = listOf("com.flycast.emulator.mainui" to "Flycast (MainUI build)", "com.flycast.emulator" to "Flycast")

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        // (It keeps the mappings it loaded until it exits, and saves them over the files.)
        Root.forceStop(pkg)
        val prefs = Root.readText("${SetupFiles.appData(pkg)}/shared_prefs/${pkg}_preferences.xml")
            ?: throw SetupProblem("Start $label once, then set up again.")
        val home = PrefsXml(prefs).string("home_directory")?.trim()?.trimEnd('/')?.ifEmpty { null } ?: SetupFiles.androidData(pkg)
        if (!home.startsWith("/")) throw SetupProblem("$label's home folder isn't on this device's storage; MainUI can't reach it.")
        val cfg = Root.readText("$home/emu.cfg") ?: throw SetupProblem("Start $label once, then set up again.")
        val dir = FlycastMapping.mappingsPath(cfg)?.also {
            if (!it.startsWith("/")) throw SetupProblem("$label's mappings folder isn't on this device's storage; MainUI can't reach it.")
        } ?: "$home/mappings"
        for (arcade in listOf(false, true))
            SetupFiles.editText("$dir/${FlycastMapping.fileName(pad.name, arcade)}") { FlycastMapping.padFile(it, p, pad, arcade) }
        val wanted = RomFolders.dirs(listOf("flycast"))
        val folders = RomFolders.grant(pkg, wanted)
        SetupFiles.editIni("$home/emu.cfg") {
            if (p.hideTouchControls) TouchControls.flycast(it)
            if (folders.isNotEmpty()) RomFolders.flycast(it, folders.map(FolderGrants::treeUri))
        }
        val (save, load) = SetupFiles.saveLoadKeys("flycast")
        SetupFiles.editText("$dir/${FlycastMapping.fileName(PadInfo.MAINUI_KEYBOARD)}") { old -> FlycastMapping.keyboardFile(old, save, load) ?: old }
        return "controller (Dreamcast and arcade), fast forward and screenshot on the hotkey button, save / load keys" + (if (p.hideTouchControls) ", touch controls off" else "") +
            RomFolders.message(folders, wanted)
    }
}
