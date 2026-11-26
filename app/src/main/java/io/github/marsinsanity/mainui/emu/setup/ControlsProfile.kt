package io.github.marsinsanity.mainui.emu.setup

import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONObject

/** A button of the handheld, by position: Settings > Emulator controls says which key it sends. */
enum class Role(val label: String, val defaultKey: Int) {
    SOUTH("Bottom face button", KeyEvent.KEYCODE_BUTTON_A),
    EAST("Right face button", KeyEvent.KEYCODE_BUTTON_B),
    WEST("Left face button", KeyEvent.KEYCODE_BUTTON_X),
    NORTH("Top face button", KeyEvent.KEYCODE_BUTTON_Y),
    L1("L1", KeyEvent.KEYCODE_BUTTON_L1),
    R1("R1", KeyEvent.KEYCODE_BUTTON_R1),
    L2("L2", KeyEvent.KEYCODE_BUTTON_L2),
    R2("R2", KeyEvent.KEYCODE_BUTTON_R2),
    L3("L3 (left stick click)", KeyEvent.KEYCODE_BUTTON_THUMBL),
    R3("R3 (right stick click)", KeyEvent.KEYCODE_BUTTON_THUMBR),
    START("Start", KeyEvent.KEYCODE_BUTTON_START),
    SELECT("Select", KeyEvent.KEYCODE_BUTTON_SELECT),
    UP("D-pad up", KeyEvent.KEYCODE_DPAD_UP),
    DOWN("D-pad down", KeyEvent.KEYCODE_DPAD_DOWN),
    LEFT("D-pad left", KeyEvent.KEYCODE_DPAD_LEFT),
    RIGHT("D-pad right", KeyEvent.KEYCODE_DPAD_RIGHT),
}

/** Emulator functions on the hotkey button held with another button. */
enum class Hotkey(val label: String, val defaultKey: Int, val wiiOnly: Boolean = false) {
    FAST_FORWARD("Fast forward", KeyEvent.KEYCODE_BUTTON_R1),
    SCREENSHOT("Screenshot", KeyEvent.KEYCODE_BUTTON_L1),
    WII_HOME("Wii: Home button", KeyEvent.KEYCODE_BUTTON_START, true),
    WII_SIDEWAYS("Wii: turn the Wii Remote sideways", KeyEvent.KEYCODE_BUTTON_Y, true),
    WII_RECENTER("Wii: recenter the motion pointer", KeyEvent.KEYCODE_BUTTON_THUMBR, true),
}

/** How a Wii game is played on the handheld; Dolphin gets one controller profile for each. */
enum class WiiController(val label: String, val short: String, val profile: String) {
    NUNCHUK("Wii Remote + Nunchuk", "Nunchuk", "MainUI Wii Remote and Nunchuk"),
    SIDEWAYS("Sideways Wii Remote", "Sideways", "MainUI Sideways Wii Remote"),
    CLASSIC("Classic Controller", "Classic", "MainUI Classic Controller"),
    GAMECUBE("GameCube controller", "GameCube", "MainUI GameCube Controller"),
}

/** Where the Wii Remote's pointer comes from. */
enum class WiiPointer(val label: String, val short: String) {
    STICK("Right stick", "Right stick"),
    MOTION("Tilting the handheld", "Tilt"),
    TOUCH("Touchscreen", "Touch"),
}

/** The axes an analog stick reports (Settings > Emulator controls > Handheld buttons). */
enum class Stick(val label: String, val axes: Pair<Int, Int>?) {
    /** Found by the usual axes: X / Y on the left, Z / RZ or RX / RY on the right. */
    AUTO("Automatic", null),
    XY("X / Y", MotionEvent.AXIS_X to MotionEvent.AXIS_Y),
    Z_RZ("Z / RZ", MotionEvent.AXIS_Z to MotionEvent.AXIS_RZ),
    RX_RY("RX / RY", MotionEvent.AXIS_RX to MotionEvent.AXIS_RY),
    ;

    companion object {
        /** The stick with [axis] among its axes. */
        fun of(axis: Int): Stick? = entries.firstOrNull { s -> s.axes?.let { axis == it.first || axis == it.second } == true }
    }
}

/**
 * The buttons and choices MainUI sets every emulator up with (Settings > Emulator controls >
 * Set up emulators). Kept as JSON in the settings.
 */
data class ControlsProfile(
    val keys: Map<Role, Int> = Role.entries.associateWith { it.defaultKey },
    /** Held with another button for the [hotkeys]. */
    val hotkeyButton: Int = KeyEvent.KEYCODE_BUTTON_SELECT,
    val hotkeys: Map<Hotkey, Int> = Hotkey.entries.associateWith { it.defaultKey },
    val wiiDefault: WiiController = WiiController.NUNCHUK,
    val wiiPointer: WiiPointer = WiiPointer.STICK,
    /** The Wii Remote's accelerometer and gyroscope follow the handheld's own (shake, tilt). */
    val wiiMotion: Boolean = true,
    val leftStick: Stick = Stick.AUTO,
    val rightStick: Stick = Stick.AUTO,
) {
    fun key(r: Role): Int = keys[r] ?: r.defaultKey
    fun hotkey(h: Hotkey): Int = hotkeys[h] ?: h.defaultKey

    /** The face buttons report A on the right (Nintendo layout) rather than at the bottom. */
    val nintendoFace: Boolean get() = key(Role.SOUTH) == KeyEvent.KEYCODE_BUTTON_B && key(Role.EAST) == KeyEvent.KEYCODE_BUTTON_A

    fun withNintendoFace(on: Boolean): ControlsProfile = copy(keys = keys + if (on) mapOf(
        Role.SOUTH to KeyEvent.KEYCODE_BUTTON_B, Role.EAST to KeyEvent.KEYCODE_BUTTON_A,
        Role.WEST to KeyEvent.KEYCODE_BUTTON_Y, Role.NORTH to KeyEvent.KEYCODE_BUTTON_X,
    ) else Role.entries.filter { it.ordinal <= Role.NORTH.ordinal }.associateWith { it.defaultKey })

    fun toJson(): JSONObject = JSONObject().apply {
        put("keys", JSONObject().apply { keys.forEach { (r, k) -> put(r.name, k) } })
        put("hotkeyButton", hotkeyButton)
        put("hotkeys", JSONObject().apply { hotkeys.forEach { (h, k) -> put(h.name, k) } })
        put("wiiDefault", wiiDefault.name)
        put("wiiPointer", wiiPointer.name)
        put("wiiMotion", wiiMotion)
        // (Left out when automatic: a profile saved before these existed stays the same JSON.)
        if (leftStick != Stick.AUTO) put("leftStick", leftStick.name)
        if (rightStick != Stick.AUTO) put("rightStick", rightStick.name)
    }

    companion object {
        fun fromJson(o: JSONObject?): ControlsProfile {
            if (o == null) return ControlsProfile()
            val d = ControlsProfile()
            fun <E : Enum<E>> map(key: String, values: Array<E>, defaults: Map<E, Int>): Map<E, Int> {
                val m = o.optJSONObject(key) ?: return defaults
                return values.associateWith { e -> m.optInt(e.name, defaults[e] ?: 0) }
            }
            return ControlsProfile(
                keys = map("keys", Role.entries.toTypedArray(), d.keys),
                hotkeyButton = o.optInt("hotkeyButton", d.hotkeyButton),
                hotkeys = map("hotkeys", Hotkey.entries.toTypedArray(), d.hotkeys),
                wiiDefault = runCatching { WiiController.valueOf(o.optString("wiiDefault")) }.getOrDefault(d.wiiDefault),
                wiiPointer = runCatching { WiiPointer.valueOf(o.optString("wiiPointer")) }.getOrDefault(d.wiiPointer),
                wiiMotion = o.optBoolean("wiiMotion", d.wiiMotion),
                leftStick = runCatching { Stick.valueOf(o.optString("leftStick")) }.getOrDefault(d.leftStick),
                rightStick = runCatching { Stick.valueOf(o.optString("rightStick")) }.getOrDefault(d.rightStick),
            )
        }

        /**
         * Wii games that play better another way than with the Nunchuk, by their 3-letter game code
         * (the disc id without region and publisher). The user's own choice in the game's options wins.
         */
        val WII_SUGGESTIONS: Map<String, WiiController> = mapOf(
            "SMN" to WiiController.SIDEWAYS, // New Super Mario Bros. Wii
            "SUK" to WiiController.SIDEWAYS, // Kirby's Return to Dream Land
            "RWL" to WiiController.SIDEWAYS, // Wario Land: Shake It!
            "SF8" to WiiController.SIDEWAYS, // Donkey Kong Country Returns
            "R8P" to WiiController.SIDEWAYS, // Super Paper Mario
            "SX4" to WiiController.CLASSIC, // Xenoblade Chronicles
            "RMH" to WiiController.CLASSIC, // Monster Hunter Tri
            "RFE" to WiiController.CLASSIC, // Fire Emblem: Radiant Dawn
            "STK" to WiiController.CLASSIC, // Tatsunoko vs. Capcom
            "SLS" to WiiController.CLASSIC, // The Last Story
            "SX3" to WiiController.CLASSIC, // Pandora's Tower
            "RSB" to WiiController.GAMECUBE, // Super Smash Bros. Brawl
            "RMC" to WiiController.GAMECUBE, // Mario Kart Wii
        )

        fun suggestion(discId: String): WiiController? = WII_SUGGESTIONS[discId.take(3)]
    }
}
