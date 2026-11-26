package io.github.marsinsanity.mainui.emu.setup

import io.github.marsinsanity.mainui.MainUI
import org.json.JSONObject

/** The [ControlsProfile] and the per-game Wii controller choices, kept in the settings. */
object Controls {
    private const val KEY = "controls_profile"
    private const val APPLIED = "controls_applied"
    private const val WII = "wii_controllers"

    private val prefs get() = MainUI.settings.prefs

    var profile: ControlsProfile
        get() = ControlsProfile.fromJson(runCatching { prefs.getString(KEY, null)?.let(::JSONObject) }.getOrNull())
        set(v) { prefs.edit().putString(KEY, v.toJson().toString()).apply() }

    /** The profile has changed since emulators were last set up with it (or they never were). */
    val changedSinceSetup: Boolean get() = prefs.getString(APPLIED, null) != profile.toJson().toString()
    val everSetUp: Boolean get() = prefs.getString(APPLIED, null) != null

    fun markSetUp(p: ControlsProfile) { prefs.edit().putString(APPLIED, p.toJson().toString()).apply() }

    private fun wiiChoices(): JSONObject = runCatching { JSONObject(prefs.getString(WII, "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** The controller the user picked for the Wii game [discId] (its options), null = not picked. */
    fun wiiOwn(discId: String): WiiController? =
        wiiChoices().optString(discId).takeIf { it.isNotEmpty() }?.let { runCatching { WiiController.valueOf(it) }.getOrNull() }

    fun setWiiOwn(discId: String, c: WiiController?) {
        val o = wiiChoices()
        if (c == null) o.remove(discId) else o.put(discId, c.name)
        prefs.edit().putString(WII, o.toString()).apply()
    }

    /** How [discId] is played: the user's pick, else MainUI's suggestion for it, else the default. */
    fun wiiFor(discId: String, p: ControlsProfile = profile): WiiController =
        wiiOwn(discId) ?: ControlsProfile.suggestion(discId) ?: p.wiiDefault
}
