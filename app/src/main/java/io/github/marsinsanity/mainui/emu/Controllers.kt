package io.github.marsinsanity.mainui.emu

import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * In-game actions for the emulator-specific menu. All calls block; run them
 * off the UI thread.
 */
interface GameController {
    val states: StateSupport
    /** True when save/load can address slots 0-9 (RetroArch). */
    val hasSlots: Boolean
    /** Human readable key names the emulator must have bound (HOTKEY only). */
    val saveKeyName: String?
    val loadKeyName: String?
    /**
     * Freeze the game *before* the menu is drawn. Returns true when the game
     * is now paused.
     */
    fun pause(): Boolean
    fun unpause()
    /**
     * API: works while paused. HOTKEY: the caller must close the overlay and
     * call [unpause] first, because injected keys go to the focused window.
     */
    fun save(slot: Int): Boolean
    fun load(slot: Int): Boolean
    /** Open the emulator's own menu. Called after MainUI's overlay is gone. */
    fun openNativeMenu(): Boolean
    /** Ask the emulator to quit (after an optional auto save). */
    fun quit(autoSave: Boolean): Boolean
}

/**
 * Freezes and thaws a whole app with SIGSTOP / SIGCONT (root). This pauses
 * any emulator instantly, including ones without a pause API. The frozen
 * package is persisted so a crash of MainUI can never leave a game frozen:
 * [thawAll] runs on every start of the app, the overlay service and MainUI's
 * activity.
 */
object Freezer {
    private const val TAG = "MainUI.Freezer"
    private const val PREF = "frozen_pkg"

    private fun pidsCmd(pkg: String) =
        "ps -A -o PID,NAME | awk '\$2 == \"$pkg\" || index(\$2, \"$pkg:\") == 1 {print \$1}'"

    fun freeze(pkg: String): Boolean {
        if (!Root.knownAvailable() && !Root.available()) return false
        MainUI.settings.prefs.edit().putString(PREF, pkg).commit()
        val r = Root.exec("for p in \$(${pidsCmd(pkg)}); do kill -STOP \$p; done; ${pidsCmd(pkg)}")
        val ok = r.ok && r.lines.isNotEmpty()
        Log.d(TAG, "freeze $pkg -> $ok (${r.lines})")
        if (!ok) MainUI.settings.prefs.edit().remove(PREF).apply()
        return ok
    }

    fun thaw(pkg: String) {
        Root.exec("for p in \$(${pidsCmd(pkg)}); do kill -CONT \$p; done")
        if (MainUI.settings.prefs.getString(PREF, null) == pkg) MainUI.settings.prefs.edit().remove(PREF).commit()
        Log.d(TAG, "thaw $pkg")
    }

    /** Safety net: thaw whatever a previous MainUI process may have left frozen. */
    fun thawAll() {
        val pkg = MainUI.settings.prefs.getString(PREF, null) ?: return
        Thread { thaw(pkg) }.start()
    }

    fun frozen(): String? = MainUI.settings.prefs.getString(PREF, null)
}

/**
 * RetroArch network command interface (UDP). Needs `network_cmd_enable = true`
 * which Settings > Emulators > "Configure RetroArch" writes for you.
 */
class RetroArchController(private val port: Int) : GameController {
    override val states = StateSupport.API
    override val hasSlots = true
    override val saveKeyName: String? = null
    override val loadKeyName: String? = null

    private fun send(cmd: String) = runCatching {
        DatagramSocket().use { s ->
            val b = cmd.toByteArray()
            s.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), port))
        }
        true
    }.getOrDefault(false)

    private fun ask(cmd: String, timeoutMs: Int = 400): String? = runCatching {
        DatagramSocket().use { s ->
            s.soTimeout = timeoutMs
            val b = cmd.toByteArray()
            s.send(DatagramPacket(b, b.size, InetAddress.getLoopbackAddress(), port))
            val buf = ByteArray(4096)
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p); String(p.data, 0, p.length).trim() } catch (_: SocketTimeoutException) { null }
        }
    }.getOrNull()

    /** "GET_STATUS PAUSED snes,Game,crc32=..." or "GET_STATUS CONTENTLESS"; null if unreachable. */
    fun status(): String? = ask("GET_STATUS", 200)

    override fun pause(): Boolean {
        val st = status() ?: return false
        if (st.contains("PAUSED")) return true
        send("PAUSE_TOGGLE")
        // Wait until RetroArch confirms, so the screenshot shows the paused frame.
        repeat(10) {
            Thread.sleep(30)
            if (status()?.contains("PAUSED") == true) return true
        }
        return false
    }

    override fun unpause() { if (status()?.contains("PAUSED") == true) send("PAUSE_TOGGLE") }

    override fun save(slot: Int): Boolean {
        // RetroArch >= 1.17 understands the *_SLOT variants; older builds ignore them,
        // so fall back to selecting the slot by stepping.
        val reply = ask("SAVE_STATE_SLOT $slot", 1500)
        if (reply != null && !reply.contains("-1")) return true
        selectSlot(slot)
        return send("SAVE_STATE")
    }

    override fun load(slot: Int): Boolean {
        val reply = ask("LOAD_STATE_SLOT $slot", 1500)
        if (reply != null && !reply.contains("-1")) return true
        selectSlot(slot)
        return send("LOAD_STATE")
    }

    private fun selectSlot(slot: Int) {
        val cur = ask("GET_STATE_SLOT")?.substringAfterLast(' ')?.toIntOrNull()
        if (cur != null) {
            val diff = slot - cur
            repeat(kotlin.math.abs(diff)) { send(if (diff > 0) "STATE_SLOT_PLUS" else "STATE_SLOT_MINUS"); Thread.sleep(30) }
        }
    }

    override fun openNativeMenu(): Boolean = send("MENU_TOGGLE")

    override fun quit(autoSave: Boolean): Boolean {
        // savestate_auto_save (set by "Configure RetroArch") writes the auto slot on QUIT.
        send("QUIT")
        Thread.sleep(150)
        return send("QUIT") // RetroArch asks for confirmation on the first QUIT by default
    }
}

/**
 * Standalone Android emulators have no remote control API. MainUI pauses them
 * by freezing the process (root), opens their menu with Back, and injects the
 * save/load hotkeys declared in emulators.json (root) for emulators that let
 * you bind hotkeys.
 */
class StandaloneController(private val pkg: String, private val def: EmuDef?) : GameController {
    override val states: StateSupport = when {
        def == null -> StateSupport.MENU // Android games: their own UI
        def.states == StateSupport.HOTKEY && !Root.knownAvailable() -> StateSupport.MENU
        else -> def.states
    }
    override val hasSlots = false
    override val saveKeyName get() = def?.save?.let { keyLabel(it) }
    override val loadKeyName get() = def?.load?.let { keyLabel(it) }
    private var frozen = false

    override fun pause(): Boolean {
        frozen = MainUI.settings.freezeStandalone && Freezer.freeze(pkg)
        return frozen
    }

    override fun unpause() {
        if (frozen || Freezer.frozen() == pkg) Freezer.thaw(pkg)
        frozen = false
    }

    override fun save(slot: Int) = keys(def?.save)
    override fun load(slot: Int) = keys(def?.load)

    override fun openNativeMenu(): Boolean {
        val m = def?.menu ?: "back"
        return when {
            m == "none" -> false
            m == "back" -> io.github.marsinsanity.mainui.service.OverlayService.instance?.globalBack()
                ?: Root.keyEvent("KEYCODE_BACK")
            else -> keys(m)
        }
    }

    override fun quit(autoSave: Boolean): Boolean {
        if (autoSave && states == StateSupport.HOTKEY && def?.save != null) {
            unpause(); Thread.sleep(400); keys(def.save); Thread.sleep(1200)
        }
        return Root.forceStop(pkg)
    }

    /** Sends a hotkey spec ("keys:KEYCODE_F1" or "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F7"). */
    fun keys(spec: String?): Boolean {
        if (spec == null) return false
        val ks = spec.removePrefix("keys:").removePrefix("key:").split('+').map { it.trim() }.filter { it.isNotEmpty() }
        return Root.keyEvent(*ks.toTypedArray())
    }

    companion object {
        fun keyLabel(spec: String) = spec.removePrefix("keys:").removePrefix("key:")
            .split('+').joinToString("+") { it.removePrefix("KEYCODE_") }
    }
}
