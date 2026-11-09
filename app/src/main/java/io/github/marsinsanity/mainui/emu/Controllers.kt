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
    /** Blocking capability probe (network). Called before [states] is read; never on the UI thread. */
    fun prepare() {}
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
     * Called once MainUI's overlay has taken input focus. A frozen app can't
     * acknowledge the focus change, and Android declares it "not responding"
     * after 5 s, so a frozen game is thawed for a moment and frozen again.
     */
    fun settle() {}
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

    /** Bumped on every freeze so a late thaw from an older menu can't undo a newer freeze. */
    @Volatile var generation = 0
        private set

    /** Serialises freeze / settle / thaw so a settle can never re-freeze a game that was just thawed. */
    private val lock = Any()

    /** Processes frozen by the last [freeze] ("pid pid ..."), and the app's main process. */
    @Volatile private var pids = ""
    @Volatile private var mainPid = ""

    /** Prints "<pid> <pid> ..." for every process of [pkg], then "main <pid>". */
    private fun lookupCmd(pkg: String) =
        "ps -A -o PID,NAME | awk '\$2 == \"$pkg\" || index(\$2, \"$pkg:\") == 1 {printf \"%s \", \$1} " +
            "\$2 == \"$pkg\" {m = \$1} END {printf \"\\nmain %s\\n\", m}'"

    fun freeze(pkg: String): Boolean {
        if (!Root.knownAvailable() && !Root.available()) return false
        synchronized(lock) {
            generation++
            MainUI.settings.prefs.edit().putString(PREF, pkg).commit()
            val look = Root.Shell.run(lookupCmd(pkg), 15000)
            val found = look.lines.firstOrNull { !it.startsWith("main") }?.trim().orEmpty()
            val main = look.lines.firstOrNull { it.startsWith("main ") }?.substringAfter(' ')?.trim().orEmpty()
            val ok = found.isNotEmpty() && Root.Shell.run("kill -STOP $found", 15000).ok
            if (ok) { pids = found; mainPid = main }
            else {
                // Never leave a half-frozen game behind: if the stop may have happened, undo it.
                if (found.isNotEmpty()) Root.Shell.run("kill -CONT $found", 15000)
                pids = ""; mainPid = ""
                MainUI.settings.prefs.edit().remove(PREF).apply()
            }
            Log.d(TAG, "freeze $pkg -> $ok ($found, main $main)")
            return ok
        }
    }

    /**
     * Lets a frozen app run just long enough for its main thread to handle the
     * window events that are waiting for it (the focus change caused by MainUI's
     * overlay), then freezes it again. A frozen app can't acknowledge them, and
     * Android shows "isn't responding" after 5 s. The main thread is done once
     * it has run and is back waiting in its event loop (epoll): usually a few
     * milliseconds, at most 20 polls of 10 ms.
     */
    fun settle(pkg: String, gen: Int) {
        synchronized(lock) {
            if (gen != generation || frozen() != pkg || pids.isEmpty()) return
            val m = mainPid.ifEmpty { pids.substringBefore(' ') }
            val r = Root.Shell.run(
                "c0=\$(grep -m1 voluntary_ctxt /proc/$m/status); kill -CONT $pids; i=0; " +
                    "while [ \$i -lt 20 ]; do sleep 0.01; i=\$((i+1)); " +
                    "[ \"\$(grep -m1 voluntary_ctxt /proc/$m/status)\" = \"\$c0\" ] && continue; " +
                    "case \"\$(cat /proc/$m/wchan 2>/dev/null)\" in *epoll*|0|'') break;; esac; done; " +
                    "sleep 0.02; kill -STOP $pids; echo \$i", 15000)
            Log.d(TAG, "settle $pkg: ${if (r.ok) "after ${r.out.trim()} polls" else "failed ${r.err}"}")
        }
    }

    /** Thaws [pkg] if [gen] is still the latest freeze (null = always, used by the safety net). */
    fun thaw(pkg: String, gen: Int? = null) {
        synchronized(lock) {
            if (gen != null && gen != generation) { Log.d(TAG, "stale thaw ignored"); return }
            val cached = pids
            // After a MainUI restart the cached list is gone: look the processes up again.
            val cmd = if (cached.isNotEmpty()) "kill -CONT $cached" else "for p in \$(${pidsCmd(pkg)}); do kill -CONT \$p; done"
            val r = Root.Shell.run(cmd, 15000)
            if (!r.ok && cached.isNotEmpty()) Root.exec("kill -CONT $cached")
            pids = ""; mainPid = ""
            if (MainUI.settings.prefs.getString(PREF, null) == pkg) MainUI.settings.prefs.edit().remove(PREF).commit()
            Log.d(TAG, "thaw $pkg")
        }
    }

    /** Safety net: thaw whatever a previous MainUI process may have left frozen. */
    fun thawAll() {
        val pkg = MainUI.settings.prefs.getString(PREF, null) ?: return
        Thread { thaw(pkg) }.start()
    }

    fun frozen(): String? = MainUI.settings.prefs.getString(PREF, null)
}

/**
 * RetroArch. Official Android builds of RetroArch do **not** include the
 * network command interface (verified on 1.22.2: no PAUSE_TOGGLE / GET_STATUS
 * handlers compiled in), and keyboard hotkeys injected from outside did not
 * reach it in testing. So MainUI probes the command port once:
 *  - a build that answers (custom / future builds): pause, slots 0-9 and quit
 *    through UDP commands;
 *  - otherwise: pause by freezing the process (root), Save / Load through
 *    RetroArch's own quick menu (Back opens it), Exit by force stop.
 */
class RetroArchController(private val port: Int, private val pkg: String? = null) : GameController {
    @Volatile private var reachable: Boolean? = null
    private var frozen = false
    private var frozenGen = -1

    override fun prepare() {
        if (reachable == null) reachable = status() != null
        // Official builds: keyboard hotkeys through MainUI's virtual keyboard (needs root).
        if (reachable == false && Root.available()) io.github.marsinsanity.mainui.core.VirtualKeys.warmUp()
    }
    private val api get() = reachable == true

    /**
     * Official Android builds have no network commands. RetroArch ignores keys injected with
     * `input keyevent` / accessibility actions (Android's virtual keyboard device), and only
     * treats Back from player 1's controller as the menu key. So with root MainUI presses its
     * keyboard hotkeys (F1 menu, F2 save state, F4 load state; "Configure RetroArch" makes sure
     * they are set) on its own virtual keyboard ([io.github.marsinsanity.mainui.core.VirtualKeys]).
     */
    private val keys get() = !api && Root.knownAvailable()

    override val states get() = when {
        api -> StateSupport.API
        keys -> StateSupport.HOTKEY
        else -> StateSupport.MENU
    }
    override val hasSlots get() = api
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
        prepare()
        if (!api) {
            frozen = pkg != null && MainUI.settings.freezeStandalone && Freezer.freeze(pkg)
            frozenGen = Freezer.generation
            return frozen
        }
        val st = status() ?: return false
        if (st.contains("PAUSED")) return true
        send("PAUSE_TOGGLE")
        // Wait (up to ~1.5 s on slow devices) until RetroArch confirms, so the
        // menu only opens over a paused game and the screenshot is the paused frame.
        repeat(30) {
            Thread.sleep(50)
            if (status()?.contains("PAUSED") == true) return true
        }
        return false
    }

    override fun settle() { if (frozen) Freezer.settle(pkg!!, frozenGen) }

    override fun unpause() {
        if (frozen) { Freezer.thaw(pkg!!, frozenGen); frozen = false; return }
        if (api && status()?.contains("PAUSED") == true) send("PAUSE_TOGGLE")
    }

    /** Held long enough for RetroArch to see it on a frame (a tap can come and go between polls). */
    private fun hotkey(key: String) = io.github.marsinsanity.mainui.core.VirtualKeys.press(key, 250)

    override fun save(slot: Int): Boolean {
        if (!api) return keys && hotkey("F2")
        // RetroArch >= 1.17 understands the *_SLOT variants; older builds ignore them,
        // so fall back to selecting the slot by stepping.
        val reply = ask("SAVE_STATE_SLOT $slot", 1500)
        if (reply != null && !reply.contains("-1")) return true
        selectSlot(slot)
        return send("SAVE_STATE")
    }

    override fun load(slot: Int): Boolean {
        if (!api) return keys && hotkey("F4")
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

    override fun openNativeMenu(): Boolean {
        if (api) return send("MENU_TOGGLE")
        if (Root.knownAvailable() || Root.available()) return hotkey("F1")
        // Without root only Back is possible; RetroArch opens its menu with it when the
        // controller profile has no menu button of its own.
        return io.github.marsinsanity.mainui.service.OverlayService.instance?.globalBack() ?: false
    }

    override fun quit(autoSave: Boolean): Boolean {
        if (api) {
            // savestate_auto_save (set by "Configure RetroArch") writes the auto slot on QUIT.
            send("QUIT")
            Thread.sleep(150)
            send("QUIT") // RetroArch asks for confirmation on the first QUIT by default
            Thread.sleep(1500)
        }
        // Make sure it is really gone (also the only way without the command interface).
        return if (pkg != null && Root.knownAvailable()) {
            if (Root.exec("pidof $pkg").out.isNotBlank()) Root.forceStop(pkg) else true
        } else api
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

    private var frozenGen = -1

    override fun pause(): Boolean {
        frozen = MainUI.settings.freezeStandalone && Freezer.freeze(pkg)
        frozenGen = Freezer.generation
        return frozen
    }

    override fun settle() { if (frozen) Freezer.settle(pkg, frozenGen) }

    override fun unpause() {
        if (frozen) Freezer.thaw(pkg, frozenGen)
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
