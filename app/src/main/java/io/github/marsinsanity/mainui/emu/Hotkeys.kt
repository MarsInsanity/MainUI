package io.github.marsinsanity.mainui.emu

import io.github.marsinsanity.mainui.MainUI
import org.json.JSONObject

/**
 * Save / load hotkeys the user picked for a standalone emulator (in-game menu > Save / load
 * keys), overriding the defaults in emulators.json. Kept as JSON in the settings:
 * {"ppsspp": {"save": "keys:KEYCODE_F2", "load": "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4"}}.
 */
object Hotkeys {
    private const val KEY = "hotkey_overrides"

    /** The keys offered: F1-F12 alone, then with Shift, Ctrl and Alt. */
    val CHOICES: List<String> = listOf("", "KEYCODE_SHIFT_LEFT+", "KEYCODE_CTRL_LEFT+", "KEYCODE_ALT_LEFT+").flatMap { mod ->
        (1..12).map { "keys:${mod}KEYCODE_F$it" }
    }

    private fun all(): JSONObject = runCatching { JSONObject(MainUI.settings.prefs.getString(KEY, "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** The user's (save, load) keys for [emuId]; null where not overridden. */
    fun get(emuId: String): Pair<String?, String?> {
        val o = all().optJSONObject(emuId) ?: return null to null
        return o.optString("save").ifEmpty { null } to o.optString("load").ifEmpty { null }
    }

    fun set(emuId: String, save: String?, load: String?) {
        val a = all()
        if (save == null && load == null) a.remove(emuId)
        else a.put(emuId, JSONObject().apply { save?.let { put("save", it) }; load?.let { put("load", it) } })
        MainUI.settings.prefs.edit().putString(KEY, a.toString()).apply()
    }

    // ---- auto load wait ------------------------------------------------------------------

    /** Seconds to wait before the auto load key, offered in the in-game menu (0 = no auto load). */
    val DELAYS = listOf(0, 3, 5, 8, 10, 15, 20, 30, 45, 60)

    /** The user's wait for [emuId] in seconds, null = the emulator's default. */
    fun loadDelay(emuId: String): Int? = MainUI.settings.prefs.getInt("autoload_delay:$emuId", -1).takeIf { it >= 0 }

    fun setLoadDelay(emuId: String, seconds: Int?) {
        val e = MainUI.settings.prefs.edit()
        if (seconds == null) e.remove("autoload_delay:$emuId") else e.putInt("autoload_delay:$emuId", seconds)
        e.apply()
    }

    /** The next / previous wait after [seconds]. */
    fun stepDelay(seconds: Int, forward: Boolean): Int {
        val i = DELAYS.indexOfFirst { it >= seconds }.let { if (it < 0) DELAYS.size - 1 else it }
        return DELAYS[(if (forward) i + 1 else i - 1).coerceIn(0, DELAYS.size - 1)]
    }

    /** The next / previous choice after [spec] (from "not set", the first one). */
    fun step(spec: String?, forward: Boolean): String {
        val i = CHOICES.indexOf(spec)
        if (i < 0) return if (forward) CHOICES.first() else CHOICES.last()
        val next = if (forward) i + 1 else i - 1 + CHOICES.size
        return CHOICES[next % CHOICES.size]
    }

    /** "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F7" -> "Shift+F7". */
    fun label(spec: String?): String = spec?.removePrefix("keys:")?.removePrefix("key:")?.split('+')?.joinToString("+") {
        when (val k = it.trim().removePrefix("KEYCODE_")) {
            "SHIFT_LEFT", "SHIFT_RIGHT" -> "Shift"
            "CTRL_LEFT", "CTRL_RIGHT" -> "Ctrl"
            "ALT_LEFT", "ALT_RIGHT" -> "Alt"
            else -> k
        }
    } ?: "not set"

    /** The names MainUI's virtual keyboard takes ("SHIFT+F7"), or null when it doesn't have a key. */
    fun virtualKeys(spec: String): String? {
        val names = spec.removePrefix("keys:").removePrefix("key:").split('+').map { k ->
            when (val n = k.trim().removePrefix("KEYCODE_")) {
                "SHIFT_LEFT", "SHIFT_RIGHT" -> "SHIFT"
                "CTRL_LEFT", "CTRL_RIGHT" -> "CTRL"
                "ALT_LEFT", "ALT_RIGHT" -> "ALT"
                else -> n
            }
        }
        return if (names.all { it in VIRTUAL }) names.joinToString("+") else null
    }

    private val VIRTUAL = (1..12).map { "F$it" }.toSet() + setOf("SHIFT", "CTRL", "ALT")
}
