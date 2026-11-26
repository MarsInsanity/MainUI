package io.github.marsinsanity.mainui.ui.scenes

import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.emu.setup.AutoSetup
import io.github.marsinsanity.mainui.emu.setup.Controls
import io.github.marsinsanity.mainui.emu.setup.ControlsProfile
import io.github.marsinsanity.mainui.emu.setup.Hotkey
import io.github.marsinsanity.mainui.emu.setup.PadInfo
import io.github.marsinsanity.mainui.emu.setup.Role
import io.github.marsinsanity.mainui.emu.setup.Stick
import io.github.marsinsanity.mainui.emu.setup.WiiController
import io.github.marsinsanity.mainui.emu.setup.WiiPointer
import io.github.marsinsanity.mainui.scraper.RomIdent
import io.github.marsinsanity.mainui.ui.Btn
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.Gfx
import io.github.marsinsanity.mainui.ui.ListScene
import io.github.marsinsanity.mainui.ui.PopMenuScene
import io.github.marsinsanity.mainui.ui.Scene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem

/** What the handheld's bottom face button sends ("bottom button sends A"). */
internal fun faceLabel(p: ControlsProfile) = "bottom button sends ${keyLabel(p.key(Role.SOUTH))}"

/** A key code as the button it is on a gamepad ("A", "L3", "Select"). */
internal fun keyLabel(code: Int): String = when (code) {
    KeyEvent.KEYCODE_BUTTON_THUMBL -> "L3"
    KeyEvent.KEYCODE_BUTTON_THUMBR -> "R3"
    KeyEvent.KEYCODE_BUTTON_START -> "Start"
    KeyEvent.KEYCODE_BUTTON_SELECT -> "Select"
    KeyEvent.KEYCODE_BUTTON_MODE -> "Mode"
    KeyEvent.KEYCODE_DPAD_UP -> "Up"
    KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
    KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
    KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
    else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_").replace('_', ' ')
}

/**
 * Settings > Controls > Emulator controls: the buttons and hotkeys MainUI sets every emulator
 * up with, and Set up emulators, which writes them into each one's own settings (root).
 */
class EmulatorControlsScene(host: SceneHost) : ListScene(host, "Emulator controls") {
    override val showPreview = false

    override fun onEnter() = build()
    override fun onResume() = build()

    private fun update(change: (ControlsProfile) -> ControlsProfile) { Controls.profile = change(Controls.profile); build() }

    private fun build() {
        val p = Controls.profile
        val apps = AutoSetup.installed()
        val setupSub = when {
            apps.isEmpty() -> "no emulator to set up"
            !Controls.everSetUp -> "not set up yet"
            Controls.changedSinceSetup -> "changed: set up again"
            else -> "done"
        }
        list.set(listOf(
            action("Set up emulators", setupSub) { setUp() },
            action("Handheld buttons...", faceLabel(p)) { host.push(HandheldButtonsScene(host)) },
            action("Hotkeys...", "${keyLabel(p.hotkeyButton)} + button") { host.push(HotkeysScene(host)) },
            toggle("Hide touch controls", { p.hideTouchControls }) { v -> update { it.copy(hideTouchControls = v) } },
            multi("Wii: default controller", WiiController.entries.map { it.name to it.short }, { p.wiiDefault.name }) { v ->
                update { it.copy(wiiDefault = WiiController.valueOf(v)) }
            },
            multi("Wii: pointer", WiiPointer.entries.map { it.name to it.short }, { p.wiiPointer.name }) { v ->
                update { it.copy(wiiPointer = WiiPointer.valueOf(v)) }
            },
            toggle("Wii: motion controls (shake, tilt)", { p.wiiMotion }) { v -> update { it.copy(wiiMotion = v) } },
            action("What Set up changes") {
                host.push(DialogScene(host, "Set up emulators",
                    "Writes these buttons into each installed emulator MainUI knows (${AutoSetup.names}), closing it first: " +
                        "player 1's controller, the hotkeys (as combos with the hotkey button where the emulator has them, " +
                        "else on buttons its system lacks) and MainUI's save / load keys, and with Hide touch controls their " +
                        "on-screen controls go off. Each also gets MainUI's game folders for its systems (folder access " +
                        "given with root), so it lists those games and opens the ones MainUI starts. Dolphin also gets a Wii Remote " +
                        "profile for each way to play (pick one per Wii game in its options, SELECT); motion controls use " +
                        "the handheld's accelerometer and gyroscope. Other settings stay; the first change keeps each " +
                        "original file as .mainui.bak.", "OK", null))
            },
        ), keepIndex = true)
    }

    private fun setUp() {
        val apps = AutoSetup.installed()
        if (apps.isEmpty()) {
            host.push(DialogScene(host, "Set up emulators", "None of the emulators MainUI can set up is installed (${AutoSetup.names}).", "OK", null))
            return
        }
        host.push(DialogScene(host, "Set up emulators",
            "Writes your buttons into ${apps.joinToString(", ") { it.second }}. They will be closed (a game running in one ends).") {
            host.toast("Setting up emulators...", 8000)
            host.io({ AutoSetup.runAll(host.hostContext) }) { results ->
                build()
                val text = results.joinToString("\n") { "${it.label}: ${if (it.ok) "" else "not set up. "}${it.message}" }
                host.push(DialogScene(host, if (results.all { it.ok }) "Emulators set up" else "Set up emulators", text, "OK", null))
            }
        })
    }
}

/** Which key each of the handheld's buttons sends (A sends KEYCODE_BUTTON_A on most handhelds). */
class HandheldButtonsScene(host: SceneHost) : ListScene(host, "Handheld buttons") {
    override val showPreview = false

    override fun onEnter() = build()

    private fun build() {
        val p = Controls.profile
        // Found by pressing: whether a handheld's bottom button sends A or B has nothing to do with
        // its labels, and "A at the bottom" read as where the games' A goes.
        val items = mutableListOf(
            action("Face buttons", faceLabel(p)) {
                host.push(KeyCaptureScene(host, "the bottom face button") { code ->
                    Controls.profile = Controls.profile.let {
                        when (code) {
                            KeyEvent.KEYCODE_BUTTON_A -> it.withNintendoFace(false)
                            KeyEvent.KEYCODE_BUTTON_B -> it.withNintendoFace(true)
                            else -> it.copy(keys = it.keys + (Role.SOUTH to code))
                        }
                    }
                    build()
                })
            },
        )
        for (r in Role.entries) items += action(r.label, keyLabel(p.key(r))) {
            host.push(KeyCaptureScene(host, r.label) { code -> Controls.profile = Controls.profile.let { it.copy(keys = it.keys + (r to code)) }; build() })
        }
        val pad = PadInfo.detect(host.hostContext)?.withSticks(p)
        fun axes(a: Pair<Int, Int>?) = a?.let { Stick.entries.firstOrNull { s -> s.axes == it }?.label } ?: "none"
        for ((name, choice, found) in listOf(
            Triple("Left stick", p.leftStick, pad?.leftStick), Triple("Right stick", p.rightStick, pad?.rightStick),
        )) items += action(name, if (choice == Stick.AUTO) "Automatic: ${axes(found)}" else choice.label) {
            host.push(KeyCaptureScene(host, "the ${name.lowercase()}", stick = true) { axis ->
                val s = Stick.of(axis) ?: return@KeyCaptureScene
                Controls.profile = Controls.profile.let { if (name == "Left stick") it.copy(leftStick = s) else it.copy(rightStick = s) }
                build()
            })
        }
        items += action("Reset to defaults") {
            Controls.profile = Controls.profile.copy(keys = ControlsProfile().keys, leftStick = Stick.AUTO, rightStick = Stick.AUTO); build()
        }
        list.set(items, keepIndex = true)
    }
}

/** The hotkey button and what it does with each other button. */
class HotkeysScene(host: SceneHost) : ListScene(host, "Hotkeys") {
    override val showPreview = false

    override fun onEnter() = build()

    private fun build() {
        val p = Controls.profile
        val items = mutableListOf(
            action("Hotkey button", keyLabel(p.hotkeyButton)) {
                host.push(KeyCaptureScene(host, "the hotkey button") { code -> Controls.profile = Controls.profile.copy(hotkeyButton = code); build() })
            },
        )
        for (h in Hotkey.entries) items += action(h.label, "${keyLabel(p.hotkeyButton)} + ${keyLabel(p.hotkey(h))}") {
            host.push(KeyCaptureScene(host, h.label + " (with the hotkey button)") { code ->
                Controls.profile = Controls.profile.let { it.copy(hotkeys = it.hotkeys + (h to code)) }; build()
            })
        }
        items += action("Reset to defaults") {
            val d = ControlsProfile()
            Controls.profile = Controls.profile.copy(hotkeyButton = d.hotkeyButton, hotkeys = d.hotkeys); build()
        }
        list.set(items, keepIndex = true)
    }
}

/**
 * "Press the button for ...": the next key (or trigger, or d-pad reported as a hat) pressed is
 * the answer, whichever it is, so B can be bound too. With [stick], the axis of the first stick
 * moved, and a button cancels. Nothing pressed for a few seconds, or a tap, cancels.
 */
class KeyCaptureScene(
    host: SceneHost, private val what: String, private val stick: Boolean = false, private val onKey: (Int) -> Unit,
) : Scene(host) {
    override val isOverlay = true
    private val until = SystemClock.uptimeMillis() + TIMEOUT_MS
    /** The key taken, closed on its release so that doesn't reach the list below. */
    private var taken: Int? = null

    override fun draw(g: Gfx) {
        val left = ((until - SystemClock.uptimeMillis()) / 1000 + 1).coerceAtLeast(0)
        if (stick) g.dialog("Move the stick", "Move $what.\n\nCancels in $left s, or press a button or tap the screen.")
        else g.dialog("Press a button", "Press the button for $what.\n\nCancels in $left s, or tap the screen.")
        g.footer(null, null, covering = true)
    }

    private var closing = false

    override fun animating(now: Long): Boolean {
        if (SystemClock.uptimeMillis() >= until && taken == null && !closing) {
            // (Not during this call: the view is going through its scenes.)
            closing = true
            android.os.Handler(android.os.Looper.getMainLooper()).post { close() }
            return false
        }
        return !closing
    }

    private fun take(code: Int) {
        if (taken != null) return
        taken = code
        onKey(code)
    }

    override fun onRawKey(e: KeyEvent): Boolean {
        when (e.keyCode) {
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> return false
        }
        if (stick) {
            // (On the press: the release of the button that opened this would cancel it at once.)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0 && taken == null) cancelKey = e.keyCode
            else if (e.action == KeyEvent.ACTION_UP && e.keyCode == cancelKey) close()
            return true
        }
        if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) take(e.keyCode)
        else if (e.action == KeyEvent.ACTION_UP && e.keyCode == taken) close()
        return true
    }

    /** The key was a trigger or hat direction, closed when the axes let go. */
    private var byAxis = false

    override fun onRawMotion(e: MotionEvent): Boolean {
        if (stick) return stickMotion(e)
        val lt = maxOf(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE))
        val rt = maxOf(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS))
        // A d-pad reported as a hat stands for its keys, as the emulator setups expect.
        val hx = e.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val code = when {
            lt > 0.6f -> KeyEvent.KEYCODE_BUTTON_L2
            rt > 0.6f -> KeyEvent.KEYCODE_BUTTON_R2
            hy < -0.5f -> KeyEvent.KEYCODE_DPAD_UP
            hy > 0.5f -> KeyEvent.KEYCODE_DPAD_DOWN
            hx < -0.5f -> KeyEvent.KEYCODE_DPAD_LEFT
            hx > 0.5f -> KeyEvent.KEYCODE_DPAD_RIGHT
            else -> null
        }
        if (taken == null && code != null) { byAxis = true; take(code) }
        else if (byAxis && lt < 0.2f && rt < 0.2f && kotlin.math.abs(hx) < 0.5f && kotlin.math.abs(hy) < 0.5f) close()
        return true
    }

    /** With [stick]: the button pressed to cancel, closed on its release. */
    private var cancelKey: Int? = null

    private fun stickMotion(e: MotionEvent): Boolean {
        val moved = STICK_AXES.map { it to e.getAxisValue(it) }
        if (taken == null) moved.filter { kotlin.math.abs(it.second) > 0.6f }.maxByOrNull { kotlin.math.abs(it.second) }?.let { take(it.first) }
        else if (moved.all { kotlin.math.abs(it.second) < 0.2f }) close()
        return true
    }

    override fun onKey(b: Btn, repeat: Boolean) = true
    override fun onTap(x: Float, y: Float): Boolean { close(); return true }

    companion object {
        private const val TIMEOUT_MS = 10_000L
        private val STICK_AXES = Stick.entries.mapNotNull { it.axes }.flatMap { listOf(it.first, it.second) }
    }
}

/** A Wii game's options entry: how it is played (Dolphin loads the matching profile for it). */
internal object WiiControllerOption {
    fun item(host: SceneHost, g: Game): UiItem? {
        val file = g.file?.takeIf { g.systemId == "wii" && !g.isFolder } ?: return null
        return UiItem("Wii controller...", onSelect = { open(host, g.name, file) })
    }

    private fun open(host: SceneHost, name: String, file: java.io.File) {
        host.io({ runCatching { RomIdent.discId6(file) }.getOrNull() }) { id ->
            if (id == null) { host.toast("Couldn't read the game's disc id", 2500); return@io }
            val own = Controls.wiiOwn(id)
            val auto = ControlsProfile.suggestion(id) ?: Controls.profile.wiiDefault
            val items = mutableListOf(UiItem("Automatic (${auto.short})", mark = own == null, onSelect = { choose(host, id, null) }))
            for (c in WiiController.entries) items += UiItem(c.label, mark = own == c, onSelect = { choose(host, id, c) })
            host.push(PopMenuScene(host, name, items))
        }
    }

    private fun choose(host: SceneHost, id: String, c: WiiController?) {
        if (MainUI.sessions.active?.pkg?.startsWith("org.dolphinemu.") == true) {
            host.toast("Exit the running Dolphin game first", 2500); return
        }
        Controls.setWiiOwn(id, c)
        host.io({ AutoSetup.applyWiiGame(id) }) { problem ->
            host.toast(problem ?: if (Controls.everSetUp) "Saved for Dolphin" else "Saved: Settings > Controls > Emulator controls > Set up emulators writes it", 3000)
        }
    }
}
