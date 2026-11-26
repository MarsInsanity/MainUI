package io.github.marsinsanity.mainui.emu.setup

/**
 * How Set up turns each emulator's on-screen touch controls off (Emulator controls > Hide touch
 * controls). Each one keeps the switch somewhere of its own; where an emulator has none, its
 * controls are made invisible instead.
 */
internal object TouchControls {
    /** Dolphin.ini: every overlay (GameCube, Wii Remote, Nunchuk, Classic), both orientations. */
    fun dolphin(ini: IniText) = ini.set("Android", "ShowInputOverlay", "False")

    /** ppsspp.ini and each game's own <GAME ID>_ppsspp.ini, which has its own copy of the setting. */
    fun ppsspp(ini: IniText) = ini.set("Control", "ShowTouchControls", "False")

    const val RETROARCH_KEY = "input_overlay_enable"

    /** An override cfg turning the overlay back on: set to off, other lines kept. */
    fun retroarchOverride(cfg: String): String =
        cfg.replace(Regex("(?m)^(\\s*$RETROARCH_KEY\\s*=).*$"), "$1 \"false\"")

    /** ARMSX2.xml: Off (0) rather than its Auto; the pause button's glyph only shows on a tap. */
    fun armsx2(prefs: PrefsXml) {
        prefs.putInt("touch.visibilityMode", 0)
        prefs.putBoolean("touch.pauseTapToReveal", true)
    }

    /** Azahar's (and Citra's) in-game "Show overlay". */
    fun azahar(prefs: PrefsXml) = prefs.putBoolean("EmulationMenuSettings_ShowOverlay", false)

    /** melonDS: every button of the layout hidden; its two screens stay, the touchscreen with them. */
    fun melon(prefs: PrefsXml) = prefs.putString("soft_input_behaviour", "always_invisible")

    /** SeedlessDS's controls, by id (ControlLayout): each one in ctrl_layout.xml's "hidden" set. */
    private val SEEDLESS = listOf("dpad", "face", "lbtn", "rbtn", "startselect", "extra", "menu")

    /** (Back still opens its menu.) */
    fun seedless(prefs: PrefsXml) = prefs.putSet("hidden", ((prefs.stringSet("hidden") ?: emptyList()) + SEEDLESS).distinct())

    /**
     * Flycast's emu.cfg: no off switch, so the virtual gamepad is fully transparent (games with
     * settings of their own too) and on no Dreamcast port, so a touch can't press its buttons.
     */
    fun flycast(ini: IniText) {
        ini.set("input", "VirtualGamepadTransparency", "100")
        ini.set("input", "maple_virtual_gamepad_uid", "-1")
        for (s in ini.sectionNames) if (ini.get(s, "input.VirtualGamepadTransparency") != null) ini.set(s, "input.VirtualGamepadTransparency", "100")
    }

    /** Mupen64Plus: transparency 0 is its "hidden" (not drawn); there is no off switch. */
    fun mupen(prefs: PrefsXml) = prefs.putInt("touchscreenTransparencyV2", 0)

    /** MAME4droid: the touch controller in both orientations. */
    fun mame(prefs: PrefsXml) {
        prefs.putBoolean("PREF_LANDSCAPE_TOUCH_CONTROLLER", false)
        prefs.putBoolean("PREF_PORTRAIT_TOUCH_CONTROLLER", false)
    }
}
