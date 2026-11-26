package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.core.Root
import org.json.JSONObject

/**
 * MAME4droid's controls: PREF_DEFINED_KEYS_2 in its default SharedPreferences, 4 blocks of 18
 * ints joined by ':', each "(controller id << 16) | key code" or -1. A controller with entries
 * of its own uses only those (its player is the order controllers were first used in, not the
 * block). The id is 9999 for every controller with PREF_INPUT_FAKE_ID, else the one MAME4droid
 * keeps per descriptor in mame4droid_prefs "persistents_ids" (from 1000).
 */
internal object Mame4droidMapping {
    const val INPUTS = 18
    private const val BLOCKS = 4
    const val FAKE_ID = 9999
    private const val FIRST_ID = 1000

    private fun deviceOf(v: Int) = v shr 16

    /** Up, Down, Left, Right, BTN1-10, Coin, Start, Exit, Option: MAME4droid's own layout (BTN1 on the right). */
    fun block(p: ControlsProfile): List<Int> = listOf(
        p.key(Role.UP), p.key(Role.DOWN), p.key(Role.LEFT), p.key(Role.RIGHT),
        p.key(Role.EAST), p.key(Role.SOUTH), p.key(Role.WEST), p.key(Role.NORTH),
        p.key(Role.R1), p.key(Role.L1), p.key(Role.R2), p.key(Role.L2), -1, -1,
        p.key(Role.SELECT), p.key(Role.START),
        -1, -1, // (Back and Menu still exit / open its options without an entry)
    )

    /**
     * PREF_DEFINED_KEYS_2 ([defined], null when unset) with controller [device]'s entries
     * replaced: in the block it had, else in one no controller has, else the first.
     */
    fun definedKeys(defined: String?, device: Int, p: ControlsProfile): String {
        val keys = IntArray(INPUTS * BLOCKS) { -1 }
        defined?.split(':')?.forEachIndexed { i, s -> if (i < keys.size) s.trim().toIntOrNull()?.let { keys[i] = it } }
        fun block(b: Int) = (0 until INPUTS).map { keys[b * INPUTS + it] }
        val own = (0 until BLOCKS).firstOrNull { b -> block(b).any { it != -1 && deviceOf(it) == device } }
        // (Its defaults are device 0's, which no controller is.)
        val free = (0 until BLOCKS).firstOrNull { b -> block(b).all { it == -1 || deviceOf(it) == 0 } }
        val target = own ?: free ?: 0
        for (i in keys.indices) if (keys[i] != -1 && deviceOf(keys[i]) == device) keys[i] = -1
        block(p).forEachIndexed { i, k -> keys[target * INPUTS + i] = if (k < 0) -1 else (device shl 16) or k }
        return keys.joinToString(":", postfix = ":")
    }

    /** [descriptor]'s id in "persistents_ids" ([json]), and the json to save when it is new (else null). */
    fun deviceId(json: String?, descriptor: String): Pair<Int, String?> {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrElse { JSONObject() }
        if (o.has(descriptor)) o.optInt(descriptor, -1).takeIf { it > 0 }?.let { return it to null }
        val ids = o.keys().asSequence().filter { it != "nextPersistentID" }.map { o.optInt(it) }.toList()
        val id = maxOf(o.optInt("nextPersistentID", FIRST_ID), (ids.maxOrNull() ?: 0) + 1)
        o.put(descriptor, id)
        o.put("nextPersistentID", id + 1)
        return id to o.toString()
    }
}

object Mame4droidSetup : EmulatorSetup {
    override val name = "MAME4droid"
    private val PACKAGES = listOf("com.seleuco.mame4d2024.mainui" to "MAME4droid (MainUI build)", "com.seleuco.mame4d2024" to "MAME4droid")

    override fun installed(): List<Pair<String, String>> = SetupFiles.installed(PACKAGES)

    override fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String {
        val prefsDir = "${SetupFiles.appData(pkg)}/shared_prefs"
        val main = "$prefsDir/${pkg}_preferences.xml"
        Root.forceStop(pkg)
        if (Root.fileExists(main) != true && Root.fileExists("$main.bak") != true) throw SetupProblem("Start $label once, then set up again.")
        SetupFiles.editPrefs(main) { prefs ->
            val device = if (prefs.value("PREF_INPUT_FAKE_ID") == "true") Mame4droidMapping.FAKE_ID else handheldId(prefsDir, pad)
            prefs.putString("PREF_DEFINED_KEYS_2", Mame4droidMapping.definedKeys(prefs.string("PREF_DEFINED_KEYS_2"), device, p))
            if (p.hideTouchControls) TouchControls.mame(prefs)
        }
        return "controller (button 1 on the right button, coin on Select)" + (if (p.hideTouchControls) ", touch controls off" else "")
    }

    /** The handheld's MAME4droid id: the one it keeps for its descriptor, given one when new. */
    private fun handheldId(prefsDir: String, pad: PadInfo): Int {
        val d = pad.descriptor
        // (Without a usable descriptor MAME4droid goes by the controller number.)
        if (d.length <= 4 || d.equals("unknown", ignoreCase = true))
            return pad.controllerNumber.takeIf { it > 0 } ?: throw SetupProblem("Android gives the controller no lasting id.")
        var id = 0
        SetupFiles.editPrefs("$prefsDir/mame4droid_prefs.xml") { prefs ->
            val (i, json) = Mame4droidMapping.deviceId(prefs.string("persistents_ids"), d)
            id = i
            json?.let { prefs.putString("persistents_ids", it) }
        }
        return id
    }
}
