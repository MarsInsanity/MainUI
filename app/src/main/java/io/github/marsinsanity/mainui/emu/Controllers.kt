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
    /**
     * [prepare] ahead of time, as soon as the game is in front (it may still be starting up, so
     * an answer that it isn't there yet must not be taken as final).
     */
    fun warmUp() = prepare()
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

    /** Why the last [save] failed, in words for the user; null when unknown. */
    val saveProblem: String? get() = null
    /** [saveToResume] can save the running game where its next start continues from. */
    val savesToResume: Boolean get() = states == StateSupport.HOTKEY
    /** Saves the running game where its next start continues from (leaving for the Game Switcher). */
    fun saveToResume(): Boolean = save(0)
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
    /** The helper programs frozen instead of the app ([freeze]'s only), comma separated. */
    private const val PREF_ONLY = "frozen_only"
    private val NAME = Regex("[A-Za-z0-9_.+-]{1,64}")

    /** [pkg]'s app uid; never a system uid (those are shared with Android itself). */
    private fun appUid(pkg: String): Int? =
        runCatching { MainUI.app.packageManager.getApplicationInfo(pkg, 0).uid }.getOrNull()
            ?.takeIf { it % 100_000 in 10_000..19_999 }

    /**
     * Prints the pids of [pkg]'s processes, or with [only] of its helper programs with those
     * names (running under the app's uid). null when [only]'s processes can't be told apart.
     */
    private fun pidsCmd(pkg: String, only: List<String>?): String? {
        if (only == null) return "ps -A -o PID,NAME | awk '\$2 == \"$pkg\" || index(\$2, \"$pkg:\") == 1 {print \$1}'"
        val uid = appUid(pkg) ?: return null
        val names = only.filter { NAME.matches(it) }.ifEmpty { return null }
        return "ps -A -o PID,UID,NAME | awk '\$2 == \"$uid\" && (" +
            names.joinToString(" || ") { "\$3 == \"$it\"" } + ") {print \$1}'"
    }

    /** Bumped on every freeze so a late thaw from an older menu can't undo a newer freeze. */
    @Volatile var generation = 0
        private set

    /** Serialises freeze / settle / thaw so a settle can never re-freeze a game that was just thawed. */
    private val lock = Any()

    /** Processes frozen by the last [freeze] ("pid pid ..."), and the app's main process. */
    @Volatile private var pids = ""
    @Volatile private var mainPid = ""
    /** The last [freeze] stopped only helper programs: the app itself keeps running. */
    @Volatile private var helpersOnly = false

    /** Prints "<pid> <pid> ..." for every process of [pkg], then "main <pid>". */
    private fun lookupCmd(pkg: String) =
        "ps -A -o PID,NAME | awk '\$2 == \"$pkg\" || index(\$2, \"$pkg:\") == 1 {printf \"%s \", \$1} " +
            "\$2 == \"$pkg\" {m = \$1} END {printf \"\\nmain %s\\n\", m}'"

    /**
     * Freezes [pkg], or with [only] just its helper programs of those names (an app that runs
     * the game in processes of its own, like PICO-8's proot / pico8_64: the app stays responsive
     * and needs no [settle]).
     */
    fun freeze(pkg: String, only: List<String>? = null): Boolean {
        if (!Root.knownAvailable() && !Root.available()) return false
        synchronized(lock) {
            generation++
            val cmd = if (only == null) lookupCmd(pkg) else pidsCmd(pkg, only) ?: run {
                Log.d(TAG, "freeze $pkg: helpers $only not found"); return false
            }
            MainUI.settings.prefs.edit().putString(PREF, pkg)
                .apply { if (only != null) putString(PREF_ONLY, only.joinToString(",")) else remove(PREF_ONLY) }.commit()
            val look = Root.Shell.run(cmd, 15000)
            val found = if (only == null) look.lines.firstOrNull { !it.startsWith("main") }?.trim().orEmpty()
                else look.lines.map { it.trim() }.filter { it.isNotEmpty() && it.all(Char::isDigit) }.joinToString(" ")
            val main = if (only == null) look.lines.firstOrNull { it.startsWith("main ") }?.substringAfter(' ')?.trim().orEmpty() else ""
            val ok = found.isNotEmpty() && Root.Shell.run("kill -STOP $found", 15000).ok
            if (ok) { pids = found; mainPid = main; helpersOnly = only != null }
            else {
                // Never leave a half-frozen game behind: if the stop may have happened, undo it.
                if (found.isNotEmpty()) Root.Shell.run("kill -CONT $found", 15000)
                pids = ""; mainPid = ""; helpersOnly = false
                MainUI.settings.prefs.edit().remove(PREF).remove(PREF_ONLY).apply()
            }
            Log.d(TAG, "freeze $pkg${if (only != null) " $only" else ""} -> $ok ($found, main $main)")
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
            if (gen != generation || frozen() != pkg || pids.isEmpty() || helpersOnly) return
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
            val only = MainUI.settings.prefs.getString(PREF_ONLY, null)
                ?.takeIf { MainUI.settings.prefs.getString(PREF, null) == pkg }?.split(',')?.filter { it.isNotEmpty() }
            val look = if (cached.isEmpty()) pidsCmd(pkg, only) ?: pidsCmd(pkg, null) else null
            val cmd = if (cached.isNotEmpty()) "kill -CONT $cached" else "for p in \$($look); do kill -CONT \$p; done"
            val r = Root.Shell.run(cmd, 15000)
            if (!r.ok && cached.isNotEmpty()) Root.exec("kill -CONT $cached")
            pids = ""; mainPid = ""; helpersOnly = false
            if (MainUI.settings.prefs.getString(PREF, null) == pkg) MainUI.settings.prefs.edit().remove(PREF).remove(PREF_ONLY).commit()
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
 *  - otherwise: RetroArch pauses itself while MainUI's menu has focus
 *    (pause_nonactive; never frozen, which could leave it without sound), Save /
 *    Load with its keyboard hotkeys (root) or its own quick menu, Exit by force stop.
 */
class RetroArchController(private val port: Int, private val pkg: String? = null, private val rom: String? = null) : GameController {
    @Volatile private var reachable: Boolean? = null
    @Volatile override var saveProblem: String? = null
        private set
    private var frozen = false
    private var frozenGen = -1

    override fun prepare() {
        if (reachable == null) reachable = probe(rememberNo = true)
        // Official builds: keyboard hotkeys through MainUI's virtual keyboard (needs root).
        if (reachable == false && Root.available()) {
            io.github.marsinsanity.mainui.core.VirtualKeys.warmUp()
            snapshotStates()
        }
    }

    /** The game's state files as they were before a Save (see [saveByKey]), read in the background. */
    private class StateFiles(val base: String, val dirs: List<String>, val find: String, val files: Map<String, String>)
    @Volatile private var stateFiles: StateFiles? = null
    @Volatile private var snapshot: Thread? = null

    /**
     * Reads the game's state files now, while the menu is open (nothing saves meanwhile), so a
     * Save doesn't wait for it before pressing F2.
     */
    private fun snapshotStates() {
        val p = pkg ?: return
        val r = rom ?: return
        if (snapshot != null) return
        snapshot = Thread({ stateFiles = runCatching { readStates(p, r) }.getOrNull() }, "ra-states").apply { isDaemon = true; start() }
    }

    private fun readStates(p: String, r: String): StateFiles {
        val cfg = Root.readFile(MainUI.emulators.raConfigPath(p))
        val base = java.io.File(r).nameWithoutExtension
        val dirs = RetroArchCores.stateDirs(cfg, p, java.io.File(r).parent)
        val find = "find ${dirs.joinToString(" ") { Root.q(it) }} -maxdepth 3 -type f " +
            "-name ${Root.q(RetroArchCores.globEscape(base) + ".state*")} -exec stat -c '%Y %s %n' {} + 2>/dev/null"
        return StateFiles(base, dirs, find, listStates(find, base))
    }

    /** "<mtime> <size>" by path of the game's state files (not their thumbnails). */
    private fun listStates(find: String, base: String): Map<String, String> =
        Root.Shell.run(find, 5000).out.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .associate { l -> l.split(' ', limit = 3).let { it.getOrElse(2) { "" } to "${it[0]} ${it.getOrElse(1) { "" }}" } }
            .filterKeys { path -> RetroArchCores.stateSlotOf(path.substringAfterLast('/'), base) != null }

    /** Right after the game appears RetroArch may not listen yet: a "no" isn't remembered then. */
    override fun warmUp() {
        if (reachable == null) reachable = probe(rememberNo = false)
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
    /** Slots 0-9 with network commands, and with keys (F6 / F7 step RetroArch's slot, see [selectSlotByKey]). */
    override val hasSlots get() = api || keys
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

    /**
     * Whether this RetroArch answers network commands. Remembered for a while per package and
     * port: every menu open makes a new controller, and official builds never answer, so asking
     * each time would hold every HOME press up by the full timeout.
     */
    private fun probe(rememberNo: Boolean): Boolean {
        val key = "$pkg:$port"
        val now = android.os.SystemClock.elapsedRealtime()
        probes[key]?.let { (ok, at) -> if (now - at < if (ok) 30 * 60_000L else 10 * 60_000L) return ok }
        val ok = status() != null
        if (ok || rememberNo) probes[key] = ok to now
        return ok
    }

    companion object {
        /** RetroArch's current slot per run ("<package>:<pid>"), as MainUI moved it. */
        private val keySlots = java.util.concurrent.ConcurrentHashMap<String, Int>()
        private val probes = java.util.concurrent.ConcurrentHashMap<String, Pair<Boolean, Long>>()
    }

    override fun pause(): Boolean {
        prepare()
        if (!api) {
            // RetroArch pauses itself while MainUI's menu or Game Switcher has focus
            // (pause_nonactive, set by MainUI's RetroArch configuration). Freezing the process on
            // top of that (SIGSTOP) could leave it without sound afterwards, so it isn't frozen.
            frozen = false
            return false
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

    /**
     * Presses RetroArch's keyboard hotkey [key] (F1 menu, F2 save state, F4 load state) on MainUI's
     * virtual keyboard, held long enough to be seen on a frame. RetroArch ignores a new keyboard's
     * first key, so Ctrl is tapped first the first time for a RetroArch process (and keyboard).
     *
     * Not as the handheld's controller: since Android 12 an injected event arrives from device -1
     * ("Virtual") whatever device it names, and RetroArch made that a new controller with a port.
     */
    private fun hotkey(key: String): Boolean {
        // Introduced once per RetroArch process and keyboard (when the game started, see
        // SessionManager.introduceKeyboard), not before every key: that cost ~0.4 s each time.
        val primed = io.github.marsinsanity.mainui.core.VirtualKeys.primeFor(pkg)
        val ok = io.github.marsinsanity.mainui.core.VirtualKeys.press(key, 250)
        io.github.marsinsanity.mainui.core.SessionLog.log("RetroArch hotkey $key on MainUI Keyboard: ${if (ok) "pressed" else "FAILED"}${if (primed) "" else " (not primed)"}")
        return ok
    }

    /**
     * Save (F2), checked: one of the game's state files in RetroArch's savestate folders must
     * change. Pressed again once if none did within 3 s (a key can be missed while RetroArch
     * settles). Without the game's path the press alone counts.
     *
     * F2 saves into RetroArch's current slot, and RetroArch sets that to 0 whenever it loads a
     * game (or to the playlist entry's / the game's last slot), whatever retroarch.cfg says. Its
     * auto load only reads the auto slot ("<game>.state.auto"), which it only writes itself when
     * a game is closed properly, never when MainUI switches games or force stops it. So with
     * auto save on, the state just saved is copied into the auto slot: the Game Switcher, Exit and
     * a later launch then continue from it, straight away when RetroArch starts the game.
     */
    private fun saveByKey(): Boolean = saveChecked("F2", "Couldn't press RetroArch's save key (needs root)") { hotkey("F2") }

    /**
     * With network commands RetroArch saves into slot 0, which its auto load never reads either:
     * checked and copied into the auto slot like [saveByKey] (root, for its files).
     */
    override val savesToResume get() = keys || (api && pkg != null && rom != null && Root.knownAvailable())
    override fun saveToResume(): Boolean =
        if (api && pkg != null && rom != null && Root.knownAvailable()) {
            saveChecked("SAVE_STATE_SLOT 0", "RetroArch didn't take the save command") {
                val reply = ask("SAVE_STATE_SLOT 0", 1500)
                if (reply != null && !reply.contains("-1")) true else { selectSlot(0); send("SAVE_STATE") }
            }
        } else if (!api) keys && saveByKey() // the slot it is on (the user's pick), copied into the auto slot
        else save(0)

    /** Saves with [press] ([what]: for the log), checked and copied into the auto slot (see [saveByKey]). */
    private fun saveChecked(what: String, pressFailed: String, press: () -> Boolean): Boolean {
        saveProblem = null
        val p = pkg
        val r = rom
        if (p == null || r == null) return press().also { if (!it) saveProblem = pressFailed }
        val log = io.github.marsinsanity.mainui.core.SessionLog::log
        // Read while the menu was open when possible (see snapshotStates); taken once.
        snapshot?.join(5000)
        val known = stateFiles.also { stateFiles = null; snapshot = null }
        val sf = known ?: readStates(p, r)
        val base = sf.base
        val dirs = sf.dirs
        fun states() = listStates(sf.find, base)
        val before = sf.files
        repeat(2) { attempt ->
            if (!press()) { saveProblem = pressFailed; return false }
            val t0 = android.os.SystemClock.uptimeMillis()
            while (android.os.SystemClock.uptimeMillis() - t0 < 3000) {
                Thread.sleep(300)
                val now = states()
                val saved = now.keys.firstOrNull { path -> now[path]?.substringBefore(' ') != before[path]?.substringBefore(' ') }
                    ?: continue
                // Until RetroArch has finished writing it (its size stays the same).
                var last = now[saved]
                var settled = false
                for (i in 0 until 80) { // up to ~20 s: big compressed states on a slow card take a while
                    Thread.sleep(250)
                    val cur = states()[saved]
                    if (cur == last) { settled = true; break }
                    last = cur
                }
                if (!settled) {
                    log("RetroArch was still writing $saved after 20 s")
                    saveProblem = "RetroArch was still writing the save"
                    return false
                }
                log("RetroArch saved $saved")
                val slot = RetroArchCores.stateSlotOf(saved.substringAfterLast('/'), base)
                // Where RetroArch really is now (its slot count, in case MainUI's was off).
                if (slot != null && slot >= 0) slotKey()?.let { keySlots[it] = slot }
                lastSavedSlot = slot
                if (slot != -1 && MainUI.settings.autoSaveOnExit) {
                    val auto = saved.substringBeforeLast('/') + "/$base.state.auto"
                    // Owner, mode and SELinux label copied from RetroArch's own file: in its private
                    // folder a root-owned copy would be one RetroArch can't overwrite later.
                    val src = Root.q(saved); val dst = Root.q(auto)
                    val cp = Root.Shell.run("cp -f $src $dst && { chown \$(stat -c %u:%g $src) $dst; chmod \$(stat -c %a $src) $dst; " +
                        "chcon \$(stat -c %C $src) $dst; true; } 2>/dev/null && " +
                        "[ \"\$(stat -c %s $src)\" = \"\$(stat -c %s $dst)\" ] && echo copied", 10000)
                    if (cp.out.contains("copied")) log("copied slot $slot into the auto slot: $auto")
                    else {
                        log("could not copy $saved into the auto slot $auto: ${cp.err.trim()}")
                        saveProblem = "Saved in slot $slot, but MainUI couldn't copy it into RetroArch's auto slot"
                    }
                }
                return true
            }
            log("RetroArch changed no $base.state* file within 3 s (try ${attempt + 1}; looked in ${dirs.joinToString()}; before: ${before.keys.ifEmpty { setOf("none") }.joinToString()})")
        }
        saveProblem = "RetroArch didn't save (it ignored MainUI's $what). Send MainUI/logs/session.txt"
        return false
    }

    /** The slot of the state file the last checked save wrote (null: unknown). */
    @Volatile var lastSavedSlot: Int? = null
        private set

    /** "<package>:<pid>": one RetroArch run, whose current slot MainUI follows in [keySlots]. */
    private fun slotKey(): String? {
        val p = pkg ?: return null
        val pid = Root.Shell.run("pidof $p", 3000).out.trim().substringBefore(' ')
        return if (pid.isEmpty()) null else "$p:$pid"
    }

    /**
     * Moves RetroArch's current slot to [slot] with its slot keys (F7 next, F6 previous), counting
     * from the slot MainUI last knew this RetroArch run on: 0 when it started the game (RetroArch
     * sets that whenever it loads a game), corrected by each checked save (the file it wrote).
     */
    private fun selectSlotByKey(slot: Int): Boolean {
        val k = slotKey()
        val cur = k?.let { keySlots[it] } ?: 0
        val diff = slot - cur
        if (diff == 0) return true
        io.github.marsinsanity.mainui.core.VirtualKeys.primeFor(pkg)
        repeat(kotlin.math.abs(diff)) {
            if (!io.github.marsinsanity.mainui.core.VirtualKeys.press(if (diff > 0) "F7" else "F6", 70)) return false
            Thread.sleep(90)
        }
        io.github.marsinsanity.mainui.core.SessionLog.log("RetroArch slot $cur -> $slot (${kotlin.math.abs(diff)} slot key presses)")
        if (k != null) keySlots[k] = slot
        return true
    }

    override fun save(slot: Int): Boolean {
        if (!api) return keys && selectSlotByKey(slot) && saveByKey()
        // RetroArch >= 1.17 understands the *_SLOT variants; older builds ignore them,
        // so fall back to selecting the slot by stepping.
        val reply = ask("SAVE_STATE_SLOT $slot", 1500)
        if (reply != null && !reply.contains("-1")) return true
        selectSlot(slot)
        return send("SAVE_STATE")
    }

    override fun load(slot: Int): Boolean {
        if (!api) return keys && selectSlotByKey(slot) && hotkey("F4")
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

    /**
     * [autoSave] without network commands: F2 into the auto slot ("Configure RetroArch" sets
     * state_slot -1 when auto save is on), which RetroArch loads by itself next time. The game
     * must be in front and running (see InGameMenuScene.exitGame).
     */
    override fun quit(autoSave: Boolean): Boolean {
        // Checked: the force stop below must not cut the save short. When the save didn't happen
        // (or didn't reach the auto slot RetroArch loads next time) RetroArch is left running:
        // false with saveProblem set, so the caller says so and the user can save another way.
        if (!api && autoSave && keys && (!saveByKey() || saveProblem != null)) return false
        if (api) {
            // savestate_auto_save (set by "Configure RetroArch") writes the auto slot on QUIT.
            send("QUIT")
            Thread.sleep(150)
            send("QUIT") // RetroArch asks for confirmation on the first QUIT by default
            // Until it has written that and gone (a big state takes a while), at most 15 s.
            val t0 = android.os.SystemClock.uptimeMillis()
            do { Thread.sleep(250) } while (pkg != null && Root.knownAvailable() &&
                android.os.SystemClock.uptimeMillis() - t0 < 15_000 && Root.exec("pidof $pkg").out.isNotBlank())
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
class StandaloneController(
    private val pkg: String,
    private val def: EmuDef?,
    /** The save key reached the emulator (MainUI then loads that state when the game starts again). */
    private val onSaved: () -> Unit = {},
) : GameController {
    /** The emulator's id in emulators.json (null for Android games). */
    val emuId: String? get() = def?.id

    /** Save / load keys: the user's choice (in-game menu > Save / load keys), else emulators.json's. */
    val saveSpec: String? get() = def?.let { Hotkeys.get(it.id).first ?: it.save }
    val loadSpec: String? get() = def?.let { Hotkeys.get(it.id).second ?: it.load }
    val defaultSave: String? get() = def?.save
    val defaultLoad: String? get() = def?.load

    /**
     * How long the game needs, once its emulator's screen has settled, before the load key works
     * (auto load on start): the user's choice (in-game menu > Save / load keys), else the
     * emulator's "loadDelay" in emulators.json, else [DEFAULT_LOAD_DELAY_MS]. 0 = no auto load.
     */
    val loadDelayMs: Long get() = def?.let { d ->
        Hotkeys.loadDelay(d.id)?.let { it * 1000L } ?: d.loadDelayMs.takeIf { it > 0 }
    } ?: DEFAULT_LOAD_DELAY_MS
    val defaultLoadDelayMs: Long get() = def?.loadDelayMs?.takeIf { it > 0 } ?: DEFAULT_LOAD_DELAY_MS

    override val states: StateSupport get() = when {
        def == null -> StateSupport.MENU // Android games: their own UI
        // Keys set for it (by emulators.json or the user): MainUI presses them, which needs root.
        saveSpec != null && loadSpec != null && (def.states == StateSupport.HOTKEY || Hotkeys.get(def.id) != (null to null)) ->
            if (Root.knownAvailable()) StateSupport.HOTKEY else StateSupport.MENU
        def.states == StateSupport.HOTKEY -> StateSupport.MENU
        else -> def.states
    }
    override val hasSlots = false
    override val saveKeyName get() = saveSpec?.let { Hotkeys.label(it) }
    override val loadKeyName get() = loadSpec?.let { Hotkeys.label(it) }
    private var frozen = false

    private var frozenGen = -1

    override fun pause(): Boolean {
        frozen = MainUI.settings.freezeStandalone && Freezer.freeze(pkg, def?.freezeOnly)
        frozenGen = Freezer.generation
        return frozen
    }

    override fun settle() { if (frozen) Freezer.settle(pkg, frozenGen) }

    override fun unpause() {
        if (frozen) Freezer.thaw(pkg, frozenGen)
        frozen = false
    }

    override fun save(slot: Int) = keys(saveSpec).also { if (it) onSaved() }
    override fun load(slot: Int) = keys(loadSpec)

    override fun openNativeMenu(): Boolean {
        val m = def?.menu ?: "back"
        return when {
            m == "none" -> false
            m == "back" -> io.github.marsinsanity.mainui.service.OverlayService.instance?.globalBack()
                ?: Root.keyEvent("KEYCODE_BACK")
            else -> keys(m)
        }
    }

    /** [autoSave]: the save key first; the game must be in front and running (see InGameMenuScene.exitGame). */
    @Volatile override var saveProblem: String? = null
        private set

    override fun quit(autoSave: Boolean): Boolean {
        saveProblem = null
        if (autoSave && states == StateSupport.HOTKEY && saveSpec != null) {
            unpause()
            // Not stopped without its save: false with saveProblem set, the game keeps running.
            if (!keys(saveSpec)) { saveProblem = "MainUI couldn't press the save key"; return false }
            onSaved()
            waitForWrites()
        }
        return Root.forceStop(pkg)
    }

    /**
     * After a save key: waits (at most 15 s) until the emulator stops writing (its processes'
     * write_bytes stay the same twice), so stopping it can't cut a big state (PS2, Wii, 3DS) short.
     */
    private fun waitForWrites() {
        val cmd = "for p in \$(pidof ${Root.q(pkg)}); do grep write_bytes /proc/\$p/io; done | awk '{s+=\$2} END {print s+0}'"
        Thread.sleep(800)
        val t0 = android.os.SystemClock.uptimeMillis()
        var last = -1L
        var same = 0
        while (same < 2 && android.os.SystemClock.uptimeMillis() - t0 < 15_000) {
            val w = Root.Shell.run(cmd, 3000).out.trim().toLongOrNull() ?: return
            same = if (w == last) same + 1 else 0
            last = w
            Thread.sleep(400)
        }
    }

    /**
     * Sends a hotkey spec ("keys:KEYCODE_F1" or "keys:KEYCODE_SHIFT_LEFT+KEYCODE_F7"): on MainUI's
     * virtual keyboard when it has those keys (a real device, which every app accepts and which
     * emulators that bind keys per device see the same way every time), else with `input`.
     */
    fun keys(spec: String?): Boolean {
        if (spec == null) return false
        Hotkeys.virtualKeys(spec)?.let { if (io.github.marsinsanity.mainui.core.VirtualKeys.press(it, 250)) return true }
        val ks = spec.removePrefix("keys:").removePrefix("key:").split('+').map { it.trim() }.filter { it.isNotEmpty() }
        return Root.keyEvent(*ks.toTypedArray())
    }

    companion object {
        fun keyLabel(spec: String) = Hotkeys.label(spec)
        const val DEFAULT_LOAD_DELAY_MS = 6000L
    }
}
