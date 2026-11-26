package io.github.marsinsanity.mainui.emu.setup

import android.view.MotionEvent
import io.github.marsinsanity.mainui.core.Root
import java.net.URLDecoder

/**
 * Azahar's (and Lime3DS's) controls, in one of two forms:
 *  - up to 2126.2: the default SharedPreferences, keyed by the handheld's input
 *    (InputMapping_HostAxis_<key code> = the 3DS button's code; an axis has _GuestButton,
 *    _GuestOrientation and _Inverted), with the reverse and display entries its settings show;
 *  - later (InputMapping_Migrated in those preferences): [Controls] in <user folder>/config/config.ini,
 *    "<3DS input> = engine:gamepad,code:<key code>" or "engine:gamepad,axis:<n>,threshold:0.5,direction:<±1>".
 * Writing the newer form into an older one's config.ini kills eleven buttons, so the form is checked.
 * Hotkeys are single buttons there (a hotkey Enable button would stop MainUI's keyboard keys):
 * fast forward (turbo) on R3, swap screens on L3, which a 3DS doesn't have.
 */
internal object AzaharMapping {
    /** The 3DS laid out like itself: A on the right, B at the bottom. */
    private val FACE = listOf("a" to Role.EAST, "b" to Role.SOUTH, "x" to Role.NORTH, "y" to Role.WEST)

    // ---- newer: config.ini [Controls] ---------------------------------------------------------------

    private fun code(c: Int) = "engine:gamepad,code:$c"
    private fun axis(n: Int, positive: Boolean, button: Boolean) =
        "engine:gamepad,axis:$n,${if (button) "threshold:0.5," else ""}direction:${if (positive) 1 else -1}"

    fun ini(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): List<Pair<String, String>> {
        val e = mutableListOf<Pair<String, String>>()
        for ((n, r) in FACE) e += "button_$n" to code(p.key(r))
        e += "button_l" to code(p.key(Role.L1))
        e += "button_r" to code(p.key(Role.R1))
        fun trigger(r: Role, axisN: Int?) = if (axisN != null && p.key(r) == r.defaultKey) axis(axisN, true, true) else code(p.key(r))
        e += "button_zl" to trigger(Role.L2, pad.leftTrigger)
        e += "button_zr" to trigger(Role.R2, pad.rightTrigger)
        e += "button_start" to code(p.key(Role.START))
        e += "button_select" to code(p.key(Role.SELECT))
        for ((n, r, hat, positive) in listOf(
            Quad("up", Role.UP, MotionEvent.AXIS_HAT_Y, false), Quad("down", Role.DOWN, MotionEvent.AXIS_HAT_Y, true),
            Quad("left", Role.LEFT, MotionEvent.AXIS_HAT_X, false), Quad("right", Role.RIGHT, MotionEvent.AXIS_HAT_X, true),
        )) e += "dpad_$n" to if (pad.hasHat && p.key(r) == r.defaultKey) axis(hat, positive, true) else code(p.key(r))
        pad.leftStick?.let { (x, y) ->
            e += "circlepad_up" to axis(y, false, false)
            e += "circlepad_down" to axis(y, true, false)
            e += "circlepad_left" to axis(x, false, false)
            e += "circlepad_right" to axis(x, true, false)
        }
        pad.rightStick?.let { (x, y) ->
            e += "cstick_up" to axis(y, false, false)
            e += "cstick_down" to axis(y, true, false)
            e += "cstick_left" to axis(x, false, false)
            e += "cstick_right" to axis(x, true, false)
        }
        e += "hotkey_enable" to ""
        e += "hotkey_turbo_toggle" to code(p.key(Role.R3))
        e += "hotkey_swap" to code(p.key(Role.L3))
        KeySpec.codes(saveSpec)?.lastOrNull()?.let { e += "hotkey_quicksave" to code(it) }
        KeySpec.codes(loadSpec)?.lastOrNull()?.let { e += "hotkey_quickload" to code(it) }
        return e
    }

    private data class Quad(val name: String, val role: Role, val hat: Int, val positive: Boolean)

    // ---- older: SharedPreferences keyed by the handheld's input -------------------------------------

    /** One preference to write. */
    sealed class Pref(val key: String) {
        class IntP(key: String, val v: Int) : Pref(key)
        class Str(key: String, val v: String) : Pref(key)
        class Bool(key: String, val v: Boolean) : Pref(key)
    }

    private const val HOST = "InputMapping_HostAxis_"
    private const val REVERSE = "InputMapping_ReverseMapping_"

    /** True for the input entries the older form keeps, all replaced by [prefs]. */
    fun isInputPref(key: String) = (key.startsWith("InputMapping_") && key != "InputMapping_Migrated") || key in SETTING_KEYS

    private val SETTING_KEYS = setOf(
        "button_a", "button_b", "button_x", "button_y", "button_l", "button_r", "button_zl", "button_zr",
        "button_start", "button_select", "button_home", "button_up", "button_down", "button_left", "button_right",
        "dpad_axis_horizontal", "dpad_axis_vertical", "circlepad_axis_horizontal", "circlepad_axis_vertical",
        "cstick_axis_horizontal", "cstick_axis_vertical", "hotkey_enable", "hotkey_screen_swap", "hotkey_toggle_layout",
        "hotkey_close_game", "hotkey_pause_or_resume_game", "hotkey_quickload", "hotkey_quickpause", "hotkey_turbo_limit",
        "hotkey_button_combo",
    )

    fun prefs(p: ControlsProfile, pad: PadInfo, saveSpec: String?, loadSpec: String?): List<Pref> {
        val e = mutableListOf<Pref>()
        val used = HashSet<Int>()
        fun key(setting: String, keyCode: Int, target: Int, label: String) {
            if (!used.add(keyCode)) return // one 3DS input per key of the handheld
            e += Pref.IntP("$HOST$keyCode", target)
            e += Pref.Str("$REVERSE$setting", "$HOST$keyCode")
            e += Pref.Str(setting, "MainUI: $label")
        }
        fun stick(setting: String, axisN: Int, target: Int, vertical: Boolean, reverseSuffix: String?) {
            e += Pref.IntP("$HOST${axisN}_GuestButton", target)
            e += Pref.IntP("$HOST${axisN}_GuestOrientation", if (vertical) 1 else 0)
            e += Pref.Bool("$HOST${axisN}_Inverted", false)
            e += Pref.Str("$REVERSE$setting${reverseSuffix ?: ""}", "$HOST$axisN")
            e += Pref.Str(setting, "MainUI: Axis $axisN")
        }
        val names = mapOf(Role.EAST to "right button", Role.SOUTH to "bottom button", Role.NORTH to "top button", Role.WEST to "left button")
        val codes = mapOf("a" to 700, "b" to 701, "x" to 702, "y" to 703)
        for ((n, r) in FACE) key("button_$n", p.key(r), codes.getValue(n), names.getValue(r))
        key("button_l", p.key(Role.L1), 773, "L1")
        key("button_r", p.key(Role.R1), 774, "R1")
        for ((setting, r, target, axisN) in listOf(Quad2("button_zl", Role.L2, 707, pad.leftTrigger), Quad2("button_zr", Role.R2, 708, pad.rightTrigger))) {
            if (axisN != null && p.key(r) == r.defaultKey) stick(setting, axisN, target, vertical = true, reverseSuffix = null)
            else key(setting, p.key(r), target, r.label)
        }
        key("button_start", p.key(Role.START), 704, "Start")
        key("button_select", p.key(Role.SELECT), 705, "Select")
        // The d-pad: the hat when there is one (its keys too would be migrated as axes of the same
        // numbers by later versions), else its keys.
        if (pad.hasHat && listOf(Role.UP, Role.DOWN, Role.LEFT, Role.RIGHT).all { p.key(it) == it.defaultKey }) {
            stick("dpad_axis_horizontal", MotionEvent.AXIS_HAT_X, 780, vertical = false, reverseSuffix = "_0")
            stick("dpad_axis_vertical", MotionEvent.AXIS_HAT_Y, 780, vertical = true, reverseSuffix = "_1")
        } else {
            key("button_up", p.key(Role.UP), 709, "Up"); key("button_down", p.key(Role.DOWN), 710, "Down")
            key("button_left", p.key(Role.LEFT), 711, "Left"); key("button_right", p.key(Role.RIGHT), 712, "Right")
        }
        pad.leftStick?.let { (x, y) ->
            stick("circlepad_axis_horizontal", x, 713, vertical = false, reverseSuffix = "_0")
            stick("circlepad_axis_vertical", y, 713, vertical = true, reverseSuffix = "_1")
        }
        pad.rightStick?.let { (x, y) ->
            stick("cstick_axis_horizontal", x, 718, vertical = false, reverseSuffix = "_0")
            stick("cstick_axis_vertical", y, 718, vertical = true, reverseSuffix = "_1")
        }
        key("hotkey_turbo_limit", p.key(Role.R3), 10007, "R3")
        key("hotkey_screen_swap", p.key(Role.L3), 10001, "L3")
        // (These two names are swapped in Azahar itself: "quickload" saves, "quickpause" loads.)
        KeySpec.codes(saveSpec)?.lastOrNull()?.let { key("hotkey_quickload", it, 10005, "MainUI Keyboard") }
        KeySpec.codes(loadSpec)?.lastOrNull()?.let { key("hotkey_quickpause", it, 10006, "MainUI Keyboard") }
        return e
    }

    private data class Quad2(val setting: String, val role: Role, val target: Int, val axis: Int?)

    /** The user folder a CITRA_DIRECTORY tree URI names ("primary:Azahar" is /storage/emulated/0/Azahar). */
    fun userDir(uri: String): String? {
        val last = uri.trim().trimEnd('/').substringAfterLast('/').ifEmpty { return null }
        val id = URLDecoder.decode(last, "UTF-8")
        if (!id.contains(':')) return null
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':').trim('/')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (path.isEmpty()) root else "$root/$path"
    }
}

object AzaharSetup : EmulatorSetup {
    override val name = "Azahar"
    private val PACKAGES = listOf(
        "org.azahar_emu.azahar.mainui" to "Azahar (MainUI build)", "org.azahar_emu.azahar" to "Azahar",
        "io.github.lime3ds.android" to "Azahar / Lime3DS",
    )

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val prefsPath = "${SetupFiles.appData(pkg)}/shared_prefs/${pkg}_preferences.xml"
        Root.forceStop(pkg)
        val current = Root.readText(prefsPath)?.let { PrefsXml(it) }
            ?: throw SetupProblem("Start $label once and pick its folder, then set up again.")
        val (save, load) = SetupFiles.saveLoadKeys(if (pkg.startsWith("io.github.lime3ds")) "lime3ds" else "azahar")
        if (current.value("InputMapping_Migrated") == "true") {
            val dir = current.string("CITRA_DIRECTORY")?.let { AzaharMapping.userDir(it) }
                ?: throw SetupProblem("Start $label once and pick its folder, then set up again.")
            val ini = "$dir/config/config.ini"
            if (Root.fileExists(ini) != true) throw SetupProblem("Start $label once, then set up again.")
            SetupFiles.editIni(ini) { cfg -> for ((k, v) in AzaharMapping.ini(p, pad, save, load)) cfg.set("Controls", k, v) }
        } else {
            SetupFiles.editPrefs(prefsPath) { prefs ->
                prefs.names.filter { AzaharMapping.isInputPref(it) }.forEach { prefs.remove(it) }
                for (pref in AzaharMapping.prefs(p, pad, save, load)) when (pref) {
                    is AzaharMapping.Pref.IntP -> prefs.putInt(pref.key, pref.v)
                    is AzaharMapping.Pref.Str -> prefs.putString(pref.key, pref.v)
                    is AzaharMapping.Pref.Bool -> prefs.putBoolean(pref.key, pref.v)
                }
            }
        }
        return "controller, fast forward on R3, swap screens on L3, save / load keys"
    }
}
