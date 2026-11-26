package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.emu.setup.Armsx2Mapping
import io.github.marsinsanity.mainui.emu.setup.ControlsProfile
import io.github.marsinsanity.mainui.emu.setup.DolphinKeyNames
import io.github.marsinsanity.mainui.emu.setup.DolphinMapping
import io.github.marsinsanity.mainui.emu.setup.Hotkey
import io.github.marsinsanity.mainui.emu.setup.IniText
import io.github.marsinsanity.mainui.emu.setup.KeySpec
import io.github.marsinsanity.mainui.emu.setup.MelonMapping
import io.github.marsinsanity.mainui.emu.setup.SeedlessMapping
import io.github.marsinsanity.mainui.emu.setup.PadInfo
import io.github.marsinsanity.mainui.emu.setup.PrefsXml
import io.github.marsinsanity.mainui.emu.setup.Role
import io.github.marsinsanity.mainui.emu.setup.WiiController
import io.github.marsinsanity.mainui.emu.setup.WiiPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorSetupTest {
    // A Retroid-like built-in pad: two sticks (Z / RZ on the right), hat d-pad, analog triggers.
    private val retroid = PadInfo(
        name = "Retroid Pocket Controller", controllerNumber = 1,
        axes = setOf(0, 1, 11, 14, 15, 16, 17, 18),
        hasAccelerometer = true, hasGyroscope = true, hasVibrator = true,
    )
    private val bare = PadInfo("Simple Pad", 0, setOf(0, 1), hasAccelerometer = false, hasGyroscope = false, hasVibrator = false)
    private val p = ControlsProfile()

    private fun List<Pair<String, String>>.v(key: String): String? = lastOrNull { it.first == key }?.second

    // ---- INI editing ------------------------------------------------------------------------

    @Test fun replacingASectionKeepsTheRest() {
        val ini = IniText("[GCPad1]\nDevice = Android/0/Old\nButtons/A = `Button B`\n\n[GCPad2]\nDevice = Keep\n")
        ini.replaceSection("GCPad1", listOf("Device" to "Android/1/New", "Buttons/A" to "`Button A`"))
        assertEquals("[GCPad1]\nDevice = Android/1/New\nButtons/A = `Button A`\n[GCPad2]\nDevice = Keep\n", ini.toString())
    }

    @Test fun aMissingSectionIsAddedAtTheEnd() {
        val ini = IniText("[Core]\nCPUCore = 4\n")
        ini.replaceSection("Hotkeys", listOf("Device" to "x"))
        assertEquals("[Core]\nCPUCore = 4\n[Hotkeys]\nDevice = x\n", ini.toString())
    }

    @Test fun settingAKeyReplacesItWhateverItsCase() {
        val ini = IniText("[Video_Settings]\nEFBScale = 2\n[Controls]\nwiimoteprofile1 = Mine\nPadProfile1 = Pad\n\n[Other]\n")
        assertEquals("Mine", ini.get("Controls", "WiimoteProfile1"))
        ini.set("Controls", "WiimoteProfile1", "MainUI Classic Controller")
        assertEquals("[Video_Settings]\nEFBScale = 2\n[Controls]\nWiimoteProfile1 = MainUI Classic Controller\nPadProfile1 = Pad\n\n[Other]\n", ini.toString())
        ini.set("Controls", "WiimoteProfile1", null)
        assertNull(ini.get("Controls", "WiimoteProfile1"))
    }

    @Test fun aNewKeyGoesBeforeTheSectionsBlankLines() {
        val ini = IniText("[Controls]\nPadProfile1 = Pad\n\n[Next]\nA = 1\n")
        ini.set("Controls", "WiimoteProfile1", "P")
        assertEquals("[Controls]\nPadProfile1 = Pad\nWiimoteProfile1 = P\n\n[Next]\nA = 1\n", ini.toString())
    }

    @Test fun emptyAndWindowsTextRoundTrip() {
        assertEquals("", IniText("").toString())
        val crlf = IniText("[A]\r\nB = 1\r\n")
        assertEquals("1", crlf.get("A", "B"))
        assertEquals("[A]\nB = 1\n", crlf.toString())
        // Removing from a file that has no such key changes nothing (no file is made for it).
        val none = IniText("")
        none.set("Controls", "WiimoteProfile1", null)
        assertEquals("", none.toString())
    }

    // ---- SharedPreferences -------------------------------------------------------------------

    @org.junit.Before fun parser() { io.github.marsinsanity.mainui.data.GameListXml.newParser = { org.kxml2.io.KXmlParser() } }

    @Test fun prefsKeepWhatTheyDontChangeAndEscapeWhatTheyWrite() {
        val p = PrefsXml("""<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
            |<map>
            |    <boolean name="first_run" value="false" />
            |    <string name="layout">{&quot;a&quot;:1}</string>
            |    <int name="version" value="7" />
            |    <set name="roots">
            |        <string>/sdcard/roms</string>
            |    </set>
            |    <set name="empty" />
            |</map>
            |""".trimMargin())
        assertEquals("{\"a\":1}", p.string("layout"))
        assertEquals("7", p.value("version"))
        p.putString("input", "{\"A\":96} & <ok>")
        p.putInt("version", 8)
        val out = p.toString()
        assertTrue(out.contains("<boolean name=\"first_run\" value=\"false\" />"))
        assertTrue(out.contains("<string>/sdcard/roms</string>"))
        assertTrue(out.contains("<set name=\"empty\">\n    </set>"))
        assertTrue(out.contains("<string name=\"input\">{&quot;A&quot;:96} &amp; &lt;ok&gt;</string>"))
        assertEquals("{\"A\":96} & <ok>", PrefsXml(out).string("input"))
        assertEquals("8", PrefsXml(out).value("version"))
        assertEquals(listOf("first_run", "layout", "version", "roots", "empty", "input"), PrefsXml(out).names.toList())
        assertTrue(PrefsXml("").names.isEmpty())
    }

    @Test fun keySpecsBecomeKeyCodes() {
        assertEquals(listOf(131), KeySpec.codes("keys:KEYCODE_F1"))
        assertEquals(listOf(59, 134), KeySpec.codes("keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4"))
        assertEquals(listOf(113, 142), KeySpec.codes("keys:KEYCODE_CTRL_LEFT+KEYCODE_F12"))
        assertNull(KeySpec.codes("keys:KEYCODE_F13"))
        assertNull(KeySpec.codes(null))
        assertNull(KeySpec.codes("keys:"))
    }

    // ---- Dolphin ------------------------------------------------------------------------------

    @Test fun gameCubeControllerUsesTheHandheldsButtonsSticksAndTriggers() {
        val e = DolphinMapping.gcPad(p, retroid)
        assertEquals("Android/1/Retroid Pocket Controller", e.v("Device"))
        assertEquals("`Button A`", e.v("Buttons/A"))
        assertEquals("`Button B`", e.v("Buttons/B"))
        assertEquals("`Button R1`", e.v("Buttons/Z"))
        assertEquals("`Start`", e.v("Buttons/Start"))
        assertEquals("`Axis 1-`", e.v("Main Stick/Up"))
        assertEquals("`Axis 0+`", e.v("Main Stick/Right"))
        assertEquals("`Axis 14+`", e.v("C-Stick/Down"))
        assertEquals("`Axis 11-`", e.v("C-Stick/Left"))
        assertEquals("`Button L2` | `Axis 17+`", e.v("Triggers/L"))
        assertEquals("`Axis 18+`", e.v("Triggers/R-Analog"))
        assertEquals("`Up` | `Axis 16-`", e.v("D-Pad/Up"))
        assertEquals("`Right` | `Axis 15+`", e.v("D-Pad/Right"))
        assertEquals("`Android/0/Device Sensors:Motor 0`", e.v("Rumble/Motor"))
    }

    @Test fun aPadWithoutRightStickHatOrTriggersGetsOnlyWhatItHas() {
        val e = DolphinMapping.gcPad(p, bare)
        assertEquals("Android/0/Simple Pad", e.v("Device"))
        assertNull(e.v("C-Stick/Up"))
        assertEquals("`Up`", e.v("D-Pad/Up"))
        assertEquals("`Button L2`", e.v("Triggers/L"))
        assertEquals("`Button L2`", e.v("Triggers/L-Analog"))
        assertNull(e.v("Rumble/Motor"))
    }

    @Test fun nintendoFaceButtonsMoveWithTheirPositions() {
        val e = DolphinMapping.gcPad(p.withNintendoFace(true), retroid)
        assertEquals("`Button B`", e.v("Buttons/A"))
        assertEquals("`Button A`", e.v("Buttons/B"))
        assertTrue(p.withNintendoFace(true).nintendoFace)
        assertEquals(p.keys, p.withNintendoFace(true).withNintendoFace(false).keys)
    }

    @Test fun nunchukProfileHasStickPointerMotionAndCombos() {
        val e = DolphinMapping.wiimote(WiiController.NUNCHUK, p, retroid)
        assertEquals("Nunchuk", e.v("Extension"))
        assertEquals("`Button A`", e.v("Buttons/A"))
        assertEquals("`Button R2` | `Axis 18+` | `Button B`", e.v("Buttons/B"))
        assertEquals("`Select` & `Start`", e.v("Buttons/Home"))
        assertEquals("`Axis 14-`", e.v("IR/Up"))
        assertEquals("False", e.v("IMUIR/Enabled"))
        assertEquals("`Android/0/Device Sensors:Accel Forward`", e.v("IMUAccelerometer/Forward"))
        assertEquals("`Android/0/Device Sensors:Gyro Yaw Right`", e.v("IMUGyroscope/Yaw Right"))
        assertEquals("`Button L1`", e.v("Nunchuk/Buttons/C"))
        assertEquals("`Axis 1-`", e.v("Nunchuk/Stick/Up"))
        assertEquals("`Button L3`", e.v("Nunchuk/Shake/X"))
        assertEquals("`Select` & `Button Y`", e.v("Hotkeys/Sideways Toggle"))
        assertNull(e.v("Options/Sideways Wiimote"))
    }

    @Test fun motionPointerTurnsOnDolphinsImuPointerInsteadOfTheStick() {
        val e = DolphinMapping.wiimote(WiiController.NUNCHUK, p.copy(wiiPointer = WiiPointer.MOTION, wiiMotion = false), retroid)
        assertEquals("True", e.v("IMUIR/Enabled"))
        assertEquals("`Select` & `Button R3`", e.v("IMUIR/Recenter"))
        assertNull(e.v("IR/Up"))
        // The pointer needs the sensors even with motion controls off.
        assertEquals("`Android/0/Device Sensors:Gyro Pitch Up`", e.v("IMUGyroscope/Pitch Up"))
    }

    @Test fun noSensorsMeansNoMotionMappingAndNoImuPointer() {
        val e = DolphinMapping.wiimote(WiiController.NUNCHUK, p.copy(wiiPointer = WiiPointer.MOTION), bare)
        assertEquals("False", e.v("IMUIR/Enabled"))
        assertNull(e.v("IMUAccelerometer/Up"))
    }

    @Test fun sidewaysProfileTurnsTheRemoteAndUsesTheStickAsDpad() {
        val e = DolphinMapping.wiimote(WiiController.SIDEWAYS, p, retroid)
        assertEquals("True", e.v("Options/Sideways Wiimote"))
        assertEquals("None", e.v("Extension"))
        assertEquals("`Button A`", e.v("Buttons/1"))
        assertEquals("`Button B`", e.v("Buttons/2"))
        assertEquals("`Up` | `Axis 16-` | `Axis 1-`", e.v("D-Pad/Up"))
        assertEquals("`Button R1` | `Button L1`", e.v("Shake/Z"))
    }

    @Test fun classicProfileIsLaidOutLikeTheProControllerWithoutMotion() {
        val e = DolphinMapping.wiimote(WiiController.CLASSIC, p, retroid)
        assertEquals("Classic", e.v("Extension"))
        assertEquals("`Button B`", e.v("Classic/Buttons/A"))
        assertEquals("`Button A`", e.v("Classic/Buttons/B"))
        assertEquals("`Button R2` | `Axis 18+`", e.v("Classic/Buttons/ZR"))
        assertEquals("`Axis 11+`", e.v("Classic/Right Stick/Right"))
        assertNull(e.v("Buttons/A"))
        assertNull(e.v("IMUAccelerometer/Up"))
        assertNull(e.v("IR/Up"))
    }

    @Test fun gameCubeProfileLeavesTheRemoteAlone() {
        val e = DolphinMapping.wiimote(WiiController.GAMECUBE, p, retroid)
        assertEquals("None", e.v("Extension"))
        assertNull(e.v("Buttons/A"))
        assertNull(e.v("Shake/X"))
    }

    @Test fun hotkeysUseCombosAndMainUisKeyboard() {
        val e = DolphinMapping.hotkeys(p, retroid, "keys:KEYCODE_F1", "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4")
        assertEquals("`Select` & `Button R1`", e.v("Emulation Speed/Disable Emulation Speed Limit"))
        assertEquals("`Select` & `Button L1`", e.v("General/Take Screenshot"))
        assertEquals("`Android/0/MainUI Keyboard:F1`", e.v("Save State/Save State Slot 1"))
        assertEquals("`Android/0/MainUI Keyboard:Left Shift` & `Android/0/MainUI Keyboard:F4`", e.v("Load State/Load State Slot 1"))
        assertNull(DolphinMapping.keyboard("keys:KEYCODE_META_LEFT"))
        val custom = p.copy(hotkeyButton = Role.L3.defaultKey, hotkeys = p.hotkeys + (Hotkey.FAST_FORWARD to Role.R2.defaultKey))
        assertEquals("`Button L3` & `Button R2`", DolphinMapping.combo(custom, Hotkey.FAST_FORWARD))
    }

    /** Every input named is one Dolphin's Android backend makes: a typo would silently map nothing. */
    @Test fun everyNameWrittenIsOneDolphinKnows() {
        val keys = (0..288).mapNotNull { DolphinKeyNames.of(it) }.toSet()
        val sensors = listOf("Up", "Down", "Left", "Right", "Forward", "Backward").map { "Accel $it" } +
            listOf("Pitch Up", "Pitch Down", "Roll Left", "Roll Right", "Yaw Left", "Yaw Right").map { "Gyro $it" } + "Motor 0"
        val all = buildList {
            for (pad in listOf(retroid, bare)) for (prof in listOf(p, p.withNintendoFace(true), p.copy(wiiPointer = WiiPointer.MOTION))) {
                addAll(DolphinMapping.gcPad(prof, pad))
                WiiController.entries.forEach { addAll(DolphinMapping.wiimote(it, prof, pad)) }
                addAll(DolphinMapping.hotkeys(prof, pad, "keys:KEYCODE_F1", "keys:KEYCODE_CTRL_LEFT+KEYCODE_F12"))
            }
        }
        val token = Regex("`([^`]*)`")
        for ((k, v) in all) for (m in token.findAll(v)) {
            val name = m.groupValues[1]
            val ok = when {
                name.startsWith(DolphinMapping.SENSORS + ":") -> name.substringAfter(':') in sensors
                name.startsWith(DolphinMapping.KEYBOARD + ":") -> name.substringAfter(':') in keys
                else -> name in keys || Regex("Axis \\d+[+-]").matches(name)
            }
            assertTrue("$k = $v: unknown input '$name'", ok)
        }
    }

    // ---- ARMSX2, melonDS, SeedlessDS ------------------------------------------------------------

    @Test fun armsx2GetsKeyCodesHotkeyCombosAndNoHoldBackExit() {
        val e = Armsx2Mapping.entries(p, "keys:KEYCODE_F1", "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F3").toMap()
        assertEquals(96, e["pad.map.cross"]); assertEquals(97, e["pad.map.circle"])
        assertEquals(99, e["pad.map.square"]); assertEquals(100, e["pad.map.triangle"])
        assertEquals(19, e["pad.map.dpad_up"]); assertEquals(104, e["pad.map.l2"]); assertEquals(109, e["pad.map.select"])
        assertEquals(103, e["pad.fastforwardtoggle.keycode"]); assertEquals(109, e["pad.fastforwardtoggle.keycode.mod"])
        assertEquals(102, e["pad.screenshot.keycode"])
        assertEquals(131, e["pad.saveslot0.keycode"]); assertEquals(0, e["pad.saveslot0.keycode.mod"])
        assertEquals(133, e["pad.loadslot0.keycode"]); assertEquals(59, e["pad.loadslot0.keycode.mod"])
        assertEquals(false, e["ui.holdBackToExit"])
        // Every value is an Int or a Boolean: ARMSX2 reads them with getInt / getBoolean.
        assertTrue(e.values.all { it is Int || it is Boolean })
        assertEquals(99, Armsx2Mapping.entries(p.withNintendoFace(true), null, null).toMap()["pad.map.triangle"])
    }

    @Test fun melonDsGetsEveryInputWithHatAndStickOnTheDpad() {
        val o = org.json.JSONObject(MelonMapping.json(p, retroid, "keys:KEYCODE_F1", "keys:KEYCODE_F2"))
        val inputs = o.getJSONArray("inputMapper")
        val byName = (0 until inputs.length()).associate { inputs.getJSONObject(it).let { j -> j.getString("input") to j } }
        assertEquals(listOf("A", "B", "X", "Y", "LEFT", "RIGHT", "UP", "DOWN", "L", "R", "START", "SELECT", "HINGE", "PAUSE",
            "FAST_FORWARD", "MICROPHONE", "RESET", "SWAP_SCREENS", "QUICK_SAVE", "QUICK_LOAD", "REWIND"),
            (0 until inputs.length()).map { inputs.getJSONObject(it).getString("input") })
        assertEquals(97, byName["A"]!!.getJSONObject("assignment").getInt("keyCode"))
        assertEquals(96, byName["B"]!!.getJSONObject("assignment").getInt("keyCode"))
        val up = byName["UP"]!!
        assertEquals("axis", up.getJSONObject("assignment").getString("type"))
        assertEquals(16, up.getJSONObject("assignment").getInt("axisCode"))
        assertEquals("NEGATIVE", up.getJSONObject("assignment").getString("direction"))
        assertEquals(1, up.getJSONObject("altAssignment").getInt("axisCode"))
        assertEquals(18, byName["FAST_FORWARD"]!!.getJSONObject("altAssignment").getInt("axisCode"))
        assertEquals(131, byName["QUICK_SAVE"]!!.getJSONObject("assignment").getInt("keyCode"))
        assertEquals("none", byName["HINGE"]!!.getJSONObject("assignment").getString("type"))
        assertFalse(byName["HINGE"]!!.has("altAssignment"))
        // Without a hat the d-pad keys are used.
        val keysOnly = org.json.JSONObject(MelonMapping.json(p, bare, null, null)).getJSONArray("inputMapper").getJSONObject(6)
        assertEquals(19, keysOnly.getJSONObject("assignment").getInt("keyCode"))
        assertEquals("none", org.json.JSONObject(MelonMapping.json(p, bare, null, null)).getJSONArray("inputMapper")
            .getJSONObject(18).getJSONObject("assignment").getString("type"))
    }

    @Test fun seedlessDsWritesTheActiveProfileAndItsVersion() {
        val e0 = SeedlessMapping.entries(p, 0, "keys:KEYCODE_F1", "keys:KEYCODE_F2").toMap()
        assertEquals(2, e0["_KeyMapVer"])
        assertEquals(19, e0["k_0"]); assertEquals(97, e0["k_4"]); assertEquals(96, e0["k_5"]); assertEquals(109, e0["k_11"])
        assertEquals(107, e0["s_1"]); assertEquals(106, e0["s_4"]); assertEquals(131, e0["s_2"]); assertEquals(132, e0["s_3"])
        val e2 = SeedlessMapping.entries(p, 2, null, null).toMap()
        assertEquals(97, e2["p2_k_4"]); assertNull(e2["k_4"]); assertNull(e2["p2_s_2"])
    }

    @Test fun profileSurvivesJsonAndKnowsSuggestions() {
        val q = p.copy(wiiDefault = WiiController.CLASSIC, wiiPointer = WiiPointer.TOUCH, wiiMotion = false, hotkeyButton = 106)
            .withNintendoFace(true)
        assertEquals(q, ControlsProfile.fromJson(org.json.JSONObject(q.toJson().toString())))
        assertEquals(ControlsProfile(), ControlsProfile.fromJson(null))
        assertEquals(WiiController.SIDEWAYS, ControlsProfile.suggestion("SMNE01"))
        assertEquals(WiiController.SIDEWAYS, ControlsProfile.suggestion("SMNP01"))
        assertNull(ControlsProfile.suggestion("RMGE01"))
        assertFalse(p.nintendoFace)
    }
}
