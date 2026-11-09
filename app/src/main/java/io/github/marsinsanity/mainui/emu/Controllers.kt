package io.github.marsinsanity.mainui.emu

import io.github.marsinsanity.mainui.core.Root
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/** In-game actions for the emulator-specific menu. All calls block; run them off the UI thread. */
interface GameController {
    val canSaveLoad: Boolean
    val hasSlots: Boolean
    /** Pause emulation while the menu is up (no-op if unsupported). */
    fun pause()
    fun unpause()
    fun save(slot: Int): Boolean
    fun load(slot: Int): Boolean
    /** Open the emulator's own menu. Called after MainUI's overlay is gone. */
    fun openNativeMenu(): Boolean
    /** Ask the emulator to quit (after an optional auto save). */
    fun quit(autoSave: Boolean): Boolean
}

/**
 * RetroArch network command interface (UDP). Needs `network_cmd_enable = true`
 * which Settings > Emulators > "Configure RetroArch" writes for you.
 */
class RetroArchController(private val port: Int) : GameController {
    override val canSaveLoad = true
    override val hasSlots = true

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

    /** "GET_STATUS PAUSED snes,Game,crc32=..." or "GET_STATUS CONTENTLESS" */
    fun status(): String? = ask("GET_STATUS")
    val reachable get() = status() != null

    private fun isPaused() = status()?.contains("PAUSED") == true

    override fun pause() { if (!isPaused()) send("PAUSE_TOGGLE") }
    override fun unpause() { if (isPaused()) send("PAUSE_TOGGLE") }

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
 * Standalone Android emulators have no remote control API. We use the BACK key
 * (which opens the pause menu in virtually all of them) and optional user
 * configured hotkeys injected with root.
 */
class StandaloneController(private val pkg: String, private val def: EmuDef?) : GameController {
    override val canSaveLoad get() = def?.save != null && def.load != null && Root.knownAvailable()
    override val hasSlots = false

    override fun pause() {}
    override fun unpause() {}

    private fun keys(spec: String?): Boolean {
        if (spec == null) return false
        val ks = spec.removePrefix("keys:").removePrefix("key:").split('+').map { it.trim() }.filter { it.isNotEmpty() }
        return Root.keyEvent(*ks.toTypedArray())
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
        if (autoSave && def?.save != null) { keys(def.save); Thread.sleep(800) }
        return Root.forceStop(pkg)
    }
}
