package io.github.marsinsanity.mainui

import io.github.marsinsanity.mainui.emu.setup.Armsx2Mapping
import io.github.marsinsanity.mainui.emu.setup.AzaharMapping
import io.github.marsinsanity.mainui.emu.setup.ControlsProfile
import io.github.marsinsanity.mainui.emu.setup.DolphinKeyNames
import io.github.marsinsanity.mainui.emu.setup.DolphinMapping
import io.github.marsinsanity.mainui.emu.setup.FlycastMapping
import io.github.marsinsanity.mainui.emu.setup.Hotkey
import io.github.marsinsanity.mainui.emu.setup.IniText
import io.github.marsinsanity.mainui.emu.setup.KeySpec
import io.github.marsinsanity.mainui.emu.setup.Mame4droidMapping
import io.github.marsinsanity.mainui.emu.setup.MelonMapping
import io.github.marsinsanity.mainui.emu.setup.MupenMapping
import io.github.marsinsanity.mainui.emu.setup.SeedlessMapping
import io.github.marsinsanity.mainui.emu.setup.PadInfo
import io.github.marsinsanity.mainui.emu.setup.PpssppMapping
import io.github.marsinsanity.mainui.emu.setup.PrefsXml
import io.github.marsinsanity.mainui.emu.setup.RetroArchMapping
import io.github.marsinsanity.mainui.emu.setup.Role
import io.github.marsinsanity.mainui.emu.setup.Stick
import io.github.marsinsanity.mainui.emu.setup.WiiController
import io.github.marsinsanity.mainui.emu.setup.WiiPointer
import io.github.marsinsanity.mainui.emu.RetroArchCores
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

    // ---- PPSSPP --------------------------------------------------------------------------------

    @Test fun ppssppBindsPadKeysAxesCombosAndKeyboardKeys() {
        val e = PpssppMapping.entries(p, retroid, "keys:KEYCODE_F2", "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4").toMap()
        assertEquals("10-96", e["Cross"]); assertEquals("10-97", e["Circle"])
        assertEquals("10-99", e["Square"]); assertEquals("10-100", e["Triangle"])
        assertEquals("10-19,10-4033", e["Up"]); assertEquals("10-22,10-4030", e["Right"])
        assertEquals("10-102", e["L"]); assertEquals("10-108", e["Start"])
        assertEquals("10-4003", e["An.Up"]); assertEquals("10-4000", e["An.Right"])
        assertEquals("10-4029", e["RightAn.Up"]); assertEquals("10-4022", e["RightAn.Right"])
        assertEquals("10-104,10-4034", e["Pause"])
        assertEquals("10-105,10-4036,10-109:10-103", e["Fast-forward"])
        assertEquals("10-109:10-102", e["Screenshot"])
        assertEquals("0-132,1-132", e["Save State"])
        assertEquals("0-59:0-134,1-59:1-134", e["Load State"])
        // Only "device-code" tokens: a malformed one crashes PPSSPP 1.20 when it loads them.
        for ((k, v) in e) for (t in v.split(',')) for (c in t.split(':'))
            assertTrue("$k = $v", Regex("\\d+-\\d+").matches(c))
        val bareE = PpssppMapping.entries(p, bare, null, null).toMap()
        assertEquals("10-19", bareE["Up"]); assertNull(bareE["RightAn.Up"]); assertNull(bareE["Save State"])
    }

    @Test fun ppssppFindsItsFolderFromPathsAndStorageUris() {
        assertEquals("/storage/emulated/0/Games/PSP", PpssppMapping.memstickPath(
            "content://com.android.externalstorage.documents/tree/primary%3AGames%2FPSP"))
        assertEquals("/storage/1234-ABCD/emu", PpssppMapping.memstickPath(
            "content://com.android.externalstorage.documents/tree/1234-ABCD%3A/document/1234-ABCD%3Aemu"))
        assertEquals("/storage/emulated/0", PpssppMapping.memstickPath("content://com.android.externalstorage.documents/tree/primary%3A"))
        assertEquals("/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files",
            PpssppMapping.memstickPath("/storage/emulated/0/Android/data/org.ppsspp.ppsspp/files/"))
        assertNull(PpssppMapping.memstickPath("content://com.google.android.apps.docs.storage/tree/x"))
        assertEquals("/storage/emulated/0/PSP", PpssppMapping.pspFolder("/storage/emulated/0"))
        assertEquals("/storage/emulated/0/Games/psp", PpssppMapping.pspFolder("/storage/emulated/0/Games/psp"))
    }

    @Test fun iniKeepsAByteOrderMarkAndFindsSectionsWhateverTheirCase() {
        val ini = IniText("\uFEFF[ControlMapping]\nCross = 10-96\n[Other]\nA = 1\n")
        ini.replaceSection("controlmapping", listOf("Cross" to "10-97"))
        assertEquals("\uFEFF[ControlMapping]\nCross = 10-97\n[Other]\nA = 1\n", ini.toString())
        assertEquals(listOf("ControlMapping", "Other"), ini.sectionNames)
    }

    // ---- Azahar --------------------------------------------------------------------------------

    @Test fun azaharNewerFormGetsGamepadValues() {
        val e = AzaharMapping.ini(p, retroid, "keys:KEYCODE_F1", "keys:KEYCODE_F2").toMap()
        assertEquals("engine:gamepad,code:97", e["button_a"]); assertEquals("engine:gamepad,code:96", e["button_b"])
        assertEquals("engine:gamepad,axis:17,threshold:0.5,direction:1", e["button_zl"])
        assertEquals("engine:gamepad,axis:16,threshold:0.5,direction:-1", e["dpad_up"])
        assertEquals("engine:gamepad,axis:1,direction:-1", e["circlepad_up"])
        assertEquals("engine:gamepad,axis:11,direction:1", e["cstick_right"])
        assertEquals("", e["hotkey_enable"])
        assertEquals("engine:gamepad,code:107", e["hotkey_turbo_toggle"])
        assertEquals("engine:gamepad,code:131", e["hotkey_quicksave"]); assertEquals("engine:gamepad,code:132", e["hotkey_quickload"])
        assertEquals("engine:gamepad,code:19", AzaharMapping.ini(p, bare, null, null).toMap()["dpad_up"])
    }

    @Test fun azaharOlderFormIsKeyedByTheHandheldsInput() {
        val prefs = AzaharMapping.prefs(p, retroid, "keys:KEYCODE_F1", "keys:KEYCODE_F2")
        fun int(k: String) = prefs.filterIsInstance<AzaharMapping.Pref.IntP>().lastOrNull { it.key == k }?.v
        fun str(k: String) = prefs.filterIsInstance<AzaharMapping.Pref.Str>().lastOrNull { it.key == k }?.v
        assertEquals(700, int("InputMapping_HostAxis_97")); assertEquals(701, int("InputMapping_HostAxis_96"))
        assertEquals("InputMapping_HostAxis_97", str("InputMapping_ReverseMapping_button_a"))
        // Triggers and the hat as axes; no d-pad keys (later versions would migrate key 19-22 as axes 19-22).
        assertEquals(707, int("InputMapping_HostAxis_17_GuestButton")); assertEquals(1, int("InputMapping_HostAxis_17_GuestOrientation"))
        assertEquals(780, int("InputMapping_HostAxis_15_GuestButton")); assertEquals(0, int("InputMapping_HostAxis_15_GuestOrientation"))
        assertEquals(780, int("InputMapping_HostAxis_16_GuestButton")); assertEquals(1, int("InputMapping_HostAxis_16_GuestOrientation"))
        assertNull(int("InputMapping_HostAxis_19"))
        assertEquals(713, int("InputMapping_HostAxis_0_GuestButton")); assertEquals(718, int("InputMapping_HostAxis_14_GuestButton"))
        assertEquals("InputMapping_HostAxis_1", str("InputMapping_ReverseMapping_circlepad_axis_vertical_1"))
        // Quick save is "hotkey_quickload" (10005) in Azahar itself.
        assertEquals(10005, int("InputMapping_HostAxis_131")); assertEquals(10006, int("InputMapping_HostAxis_132"))
        assertEquals(10007, int("InputMapping_HostAxis_107")); assertEquals(10001, int("InputMapping_HostAxis_106"))
        // Without a hat the d-pad keys are used, and each handheld key drives one 3DS input.
        val keys = AzaharMapping.prefs(p, bare, null, null).filterIsInstance<AzaharMapping.Pref.IntP>()
        assertEquals(709, keys.single { it.key == "InputMapping_HostAxis_19" }.v)
        assertEquals(keys.map { it.key }.distinct(), keys.map { it.key })
        assertTrue(AzaharMapping.isInputPref("InputMapping_HostAxis_96")); assertFalse(AzaharMapping.isInputPref("InputMapping_Migrated"))
        assertTrue(AzaharMapping.isInputPref("hotkey_enable")); assertFalse(AzaharMapping.isInputPref("CITRA_DIRECTORY"))
    }

    @Test fun azaharFindsItsUserFolder() {
        assertEquals("/storage/emulated/0/Azahar", AzaharMapping.userDir("content://com.android.externalstorage.documents/tree/primary%3AAzahar"))
        assertEquals("/storage/ABCD-1234/emu/3ds", AzaharMapping.userDir("content://com.android.externalstorage.documents/tree/ABCD-1234%3Aemu%2F3ds"))
        assertNull(AzaharMapping.userDir("content://x/tree/nocolon"))
    }

    @Test fun retroArchBindsPlayerOneAndTheHotkeyButton() {
        val b = RetroArchMapping.binds(p, retroid)
        assertEquals("\"96\"", b["input_player1_b_btn"]); assertEquals("\"97\"", b["input_player1_a_btn"])
        assertEquals("\"h0up\"", b["input_player1_up_btn"]); assertEquals("\"h0right\"", b["input_player1_right_btn"])
        assertEquals("\"+6\"", b["input_player1_l2_axis"]); assertEquals("\"+7\"", b["input_player1_r2_axis"])
        assertEquals("\"-0\"", b["input_player1_l_x_minus_axis"]); assertEquals("\"+1\"", b["input_player1_l_y_plus_axis"])
        assertEquals("\"+2\"", b["input_player1_r_x_plus_axis"]); assertEquals("\"-3\"", b["input_player1_r_y_minus_axis"])
        assertEquals("\"109\"", b["input_enable_hotkey_btn"])
        assertEquals("\"103\"", b["input_toggle_fast_forward_btn"]); assertEquals("\"102\"", b["input_screenshot_btn"])
        assertEquals("\"nul\"", b["input_enable_hotkey"])
        val plain = RetroArchMapping.binds(p, bare)
        assertEquals("\"19\"", plain["input_player1_up_btn"]); assertEquals("\"nul\"", plain["input_player1_l2_axis"])
        assertNull(plain["input_player1_r_x_plus_axis"])
        // BRAKE / GAS triggers are RetroArch's axes 8 / 9; RX / RY right sticks it doesn't read.
        val other = RetroArchMapping.binds(p, retroid.copy(axes = setOf(0, 1, 12, 13, 22, 23)))
        assertEquals("\"+8\"", other["input_player1_l2_axis"]); assertEquals("\"+9\"", other["input_player1_r2_axis"])
        assertNull(other["input_player1_r_x_plus_axis"])
        val cfg = (1..120).joinToString("\n") { "setting_$it = \"x\"" } + "\ninput_player1_b_btn = \"nul\"\n"
        val merged = RetroArchCores.mergeConfig(cfg, b)!!
        assertTrue(merged.contains("input_player1_b_btn = \"96\"\n")); assertFalse(merged.contains("input_player1_b_btn = \"nul\""))
    }

    @Test fun flycastWritesItsMappingFiles() {
        assertEquals("Android_Retroid Pocket Controller.cfg", FlycastMapping.fileName("Retroid Pocket Controller"))
        assertEquals("Android_A-B-C_arcade.cfg", FlycastMapping.fileName("A/B:C", arcade = true))
        val pad = retroid.copy(halfAxes = setOf(18, 17))
        val ini = IniText(FlycastMapping.padFile("[emulator]\ndead_zone = 25\nversion = 2\n[dreamcast]\nbtn_a = 96\n", p, pad, arcade = false))
        fun binds(section: String): List<String> = generateSequence(0) { it + 1 }.map { ini.get(section, "bind$it") }.takeWhile { it != null }.filterNotNull().toList()
        val digital = binds("digital")
        assertEquals("19:btn_dpad1_up", digital[0])
        assertTrue(digital.containsAll(listOf("96:btn_a", "97:btn_b", "99:btn_x", "100:btn_y", "103:btn_c", "102:btn_z", "108:btn_start", "104:btn_trigger_left")))
        assertFalse(digital.any { it.startsWith("109:") }) // the hotkey button alone does nothing
        assertTrue(binds("analog").containsAll(listOf("0-:btn_analog_left", "1+:btn_analog_down", "11-:axis2_left", "14+:axis2_down", "17+:btn_trigger_left", "18+:btn_trigger_right")))
        assertEquals(listOf("109,103:btn_fforward:1", "109,102:btn_screenshot:1"), binds("combo"))
        assertEquals("25", ini.get("emulator", "dead_zone")); assertEquals("17,18", ini.get("emulator", "triggers"))
        assertEquals("4", ini.get("emulator", "version")); assertFalse(ini.has("dreamcast"))
        val arcade = IniText(FlycastMapping.padFile("", p, pad, arcade = true))
        assertEquals("99:btn_c", (0..20).mapNotNull { arcade.get("digital", "bind$it") }.single { it.endsWith(":btn_c") })
        assertTrue((0..20).mapNotNull { arcade.get("digital", "bind$it") }.contains("109:btn_d"))

        val kb = IniText(FlycastMapping.keyboardFile("", "keys:KEYCODE_F1", "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F4")!!)
        assertEquals("131:btn_quick_save", kb.get("digital", "bind0")); assertNull(kb.get("digital", "bind1"))
        assertEquals("59,134:btn_jump_state:1", kb.get("combo", "bind0"))
        assertEquals("MainUI Keyboard", kb.get("emulator", "mapping_name")); assertEquals("", kb.get("emulator", "triggers"))
        assertNull(FlycastMapping.keyboardFile("", null, null))
        assertEquals("/sdcard/maps", FlycastMapping.mappingsPath("[config]\nDreamcast.MappingsPath = \"/sdcard/maps/\";/x\n"))
        assertNull(FlycastMapping.mappingsPath("[config]\nDreamcast.MappingsPath = \n"))
    }

    @Test fun mupenProfileUsesItsCodesAndFallsBackWithoutSticks() {
        fun pairs(pad: PadInfo) = MupenMapping.map(p, pad, "keys:KEYCODE_F1", "keys:KEYCODE_F2").split(',').map { it.substringBefore(':').toInt() to it.substringAfter(':').toInt() }
        val m = pairs(retroid)
        assertTrue(m.containsAll(listOf(7 to 96, 6 to 99, 10 to 97, 9 to 100, 4 to 108, 13 to 102, 12 to 103)))
        assertTrue(m.containsAll(listOf(16 to -1, 17 to -2, 18 to -3, 19 to -4))) // stick on X / Y
        assertTrue(m.containsAll(listOf(8 to -23, 9 to -24, 10 to -29, 11 to -30))) // C buttons on Z / RZ
        assertTrue(m.containsAll(listOf(0 to 22, 3 to 19, 0 to -31, 1 to -32, 2 to -33, 3 to -34))) // d-pad and hat
        assertTrue(m.containsAll(listOf(5 to 104, 5 to -35, 26 to 105, 26 to -37, 21 to 131, 22 to 132)))
        assertEquals(m.map { it.second }.distinct(), m.map { it.second }) // one command per input
        val noRight = pairs(bare)
        assertTrue(noRight.containsAll(listOf(8 to 22, 9 to 21, 10 to 20, 11 to 19))); assertFalse(noRight.any { it.first in 0..3 })
        val noSticks = pairs(PadInfo("Pad", 1, emptySet(), hasAccelerometer = false, hasGyroscope = false, hasVibrator = false))
        assertTrue(noSticks.containsAll(listOf(16 to 22, 17 to 21, 18 to 20, 19 to 19)))
        assertEquals("MainUI", MupenMapping.PROFILE)
        assertEquals("Set up by MainUI", MupenMapping.section(p, retroid, null, null).v("comment"))
    }

    @Test fun mame4droidKeysGoUnderTheHandheldsId() {
        val id = 1000
        fun code(k: Int, dev: Int = id) = (dev shl 16) or k
        val fresh = Mame4droidMapping.definedKeys(null, id, p)
        assertTrue(fresh.endsWith(":"))
        val keys = fresh.removeSuffix(":").split(':').map { it.toInt() }
        assertEquals(72, keys.size)
        assertEquals(listOf(code(19), code(20), code(21), code(22), code(97), code(96), code(99), code(100)), keys.take(8))
        assertEquals(code(109), keys[14]); assertEquals(code(108), keys[15])
        assertEquals(-1, keys[12]); assertEquals(-1, keys[16]); assertTrue(keys.drop(18).all { it == -1 })
        // Another pad's block stays; this one's goes where it was, and its strays elsewhere go.
        val other = (0 until 18).map { code(96 + it, 1001) }
        val mine = listOf(code(19)) + List(17) { -1 }
        val stray = listOf(code(50)) + List(17) { -1 }
        val after = Mame4droidMapping.definedKeys((other + mine + stray + List(18) { -1 }).joinToString(":", postfix = ":"), id, p)
            .removeSuffix(":").split(':').map { it.toInt() }
        assertEquals(other, after.take(18)); assertEquals(code(97), after[18 + 4]); assertTrue(after.drop(36).all { it == -1 })
        // MAME4droid's defaults (device 0) don't hold a block.
        val defaults = listOf(19, 20, 21, 22, 97, 96) + List(66) { -1 }
        assertEquals(code(19), Mame4droidMapping.definedKeys(defaults.joinToString(":"), id, p).split(':')[0].toInt())

        val known = "{\"abc\":1000,\"nextPersistentID\":1001}"
        assertEquals(1000 to null, Mame4droidMapping.deviceId(known, "abc"))
        val (newId, json) = Mame4droidMapping.deviceId(known, "xyz")
        assertEquals(1001, newId)
        assertEquals(1002, org.json.JSONObject(json!!).getInt("nextPersistentID")); assertEquals(1001, org.json.JSONObject(json).getInt("xyz"))
        assertEquals(1000, Mame4droidMapping.deviceId(null, "xyz").first)
        assertEquals(1000, Mame4droidMapping.deviceId("{", "xyz").first)
    }

    @Test fun sticksFollowTheProfile() {
        assertEquals(Stick.XY, Stick.of(1)); assertEquals(Stick.Z_RZ, Stick.of(14)); assertEquals(Stick.RX_RY, Stick.of(12)); assertNull(Stick.of(15))
        // Automatic: X / Y on the left, Z / RZ (else RX / RY) on the right.
        assertEquals(0 to 1, retroid.leftStick); assertEquals(11 to 14, retroid.rightStick)
        // Swapped, each writer follows.
        val swapped = retroid.withSticks(p.copy(leftStick = Stick.Z_RZ, rightStick = Stick.XY))
        assertEquals(11 to 14, swapped.leftStick); assertEquals(0 to 1, swapped.rightStick)
        assertEquals("10-4029", PpssppMapping.entries(p, swapped, null, null).v("An.Up"))
        assertEquals("\"+2\"", RetroArchMapping.binds(p, swapped)["input_player1_l_x_plus_axis"])
        assertEquals("\"-1\"", RetroArchMapping.binds(p, swapped)["input_player1_r_y_minus_axis"])
        // A left stick on the right one's usual axes leaves the automatic right stick to RX / RY.
        val both = retroid.copy(axes = retroid.axes + setOf(12, 13)).withSticks(p.copy(leftStick = Stick.Z_RZ))
        assertEquals(12 to 13, both.rightStick)
        assertNull(RetroArchMapping.binds(p, both)["input_player1_r_x_plus_axis"]) // RetroArch doesn't read RX / RY
        assertNull(retroid.copy(axes = setOf(11, 14)).withSticks(p.copy(rightStick = Stick.Z_RZ)).leftStick)
        // Automatic sticks keep the profile's JSON as it was before sticks existed.
        assertFalse(p.toJson().has("leftStick"))
        val q = p.copy(leftStick = Stick.RX_RY)
        assertEquals(q, ControlsProfile.fromJson(org.json.JSONObject(q.toJson().toString())))
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
