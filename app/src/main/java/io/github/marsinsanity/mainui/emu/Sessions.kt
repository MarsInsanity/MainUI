package io.github.marsinsanity.mainui.emu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.core.Settings
import io.github.marsinsanity.mainui.core.Storage
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.Library
import io.github.marsinsanity.mainui.data.SystemDef
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** A game (or Android app) that MainUI launched and that may still be running. */
data class Session(
    val gameKey: String,
    val systemId: String,
    val path: String?,
    val name: String,
    val emuRef: String?,
    val pkg: String,
    val activity: String?,
    val startedAt: Long,
    /** The intent that started the game (Intent.toUri), to bring the same task back. */
    val launchUri: String? = null,
    /**
     * [android.os.SystemClock.uptimeMillis] at the start: play time counts uptime, which stops
     * while the handheld sleeps (sessions are only restored within the same boot).
     */
    val startedUptime: Long = android.os.SystemClock.uptimeMillis(),
) {
    val isRetroArch get() = emuRef?.startsWith("ra:") == true
    val isAndroidApp get() = emuRef == null

    fun json() = JSONObject().put("gameKey", gameKey).put("system", systemId).put("path", path).put("name", name)
        .put("emu", emuRef).put("pkg", pkg).put("activity", activity).put("startedAt", startedAt).put("launch", launchUri)
        .put("startedUptime", startedUptime)

    companion object {
        fun from(o: JSONObject) = Session(o.getString("gameKey"), o.getString("system"), o.optString("path").ifEmpty { null },
            o.getString("name"), o.optString("emu").ifEmpty { null }, o.getString("pkg"),
            o.optString("activity").ifEmpty { null }, o.getLong("startedAt"), o.optString("launch").ifEmpty { null },
            o.optLong("startedUptime", android.os.SystemClock.uptimeMillis()))
    }
}

/** Android tasks, from `dumpsys activity recents` (root). */
object Tasks {
    private val taskRe = Regex("""Task\{[0-9a-f]+ #(\d+)""")
    private val pkgRe = Regex("""(?:\bA=\d+:|\bI=|realActivity=\{?|cmp=)([A-Za-z0-9_.]+)""")

    /**
     * The id of [pkg]'s most recent task that still has activities ("sz=0" tasks are only
     * remembered in recents), or null.
     */
    fun idOf(dump: String, pkg: String): Int? {
        var id: Int? = null
        var live = false
        var match = false
        fun done(): Int? = if (id != null && live && match) id else null
        for (line in dump.lineSequence()) {
            val t = taskRe.find(line)
            if (t != null) {
                done()?.let { return it }
                id = t.groupValues[1].toIntOrNull()
                live = !line.contains("sz=0")
                match = false
            }
            if (id != null && pkgRe.findAll(line).any { it.groupValues[1] == pkg }) match = true
        }
        return done()
    }
}

/**
 * Tracks the running session, play time and the per-game screenshots that the
 * Game Switcher shows. Survives MainUI process death through SharedPreferences.
 */
class SessionManager(
    private val ctx: Context,
    private val settings: Settings,
    private val storage: Storage,
    private val library: Library,
    private val emulators: EmulatorManager,
) {
    /**
     * The game being played. Restored after MainUI's process restarts, but not after a reboot:
     * a session started before this boot can't still be running (counting from then would add
     * the whole night as play time, and HOME in an emulator opened by hand would act on it).
     */
    @Volatile var active: Session? = runCatching {
        settings.prefs.getString("active_session", null)?.let { Session.from(JSONObject(it)) }
    }.getOrNull()?.takeIf { it.startedAt >= bootTime() - 60_000 }
        private set

    /**
     * When MainUI last brought the session's game to the front (started or resumed it), uptime ms.
     * MainActivity compares it with its own pause to tell "left for the game" from "left for
     * another app" (any other app MainUI opens: settings panels, the process manager...).
     */
    @Volatile var shownAt = 0L
        private set

    private fun bootTime() = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()

    /** Package names of every app launched from MainUI in this boot (for the process manager). */
    val launchedPackages: MutableSet<String> = linkedSetOf()

    private fun persist() {
        settings.prefs.edit().putString("active_session", active?.json()?.toString()).apply()
    }

    /** Launches a rom with the chosen emulator. Returns an error message or null. */
    fun launch(sys: SystemDef, game: Game, opt: EmuOption?): String? {
        val option = opt ?: emulators.choose(sys, game) ?: return "No emulator available for ${sys.name}"
        if (option.installed == false) return "${option.name} is not installed"
        if (option.isRetroArch) runCatching { emulators.ensureRetroArchConfigured() }
        val intent = try { emulators.buildIntent(game, option) } catch (e: IllegalStateException) { return e.message }
        val rootLaunch = emulators.defFor(option.ref)?.rootLaunch == true
        if (rootLaunch && !Root.available()) return "${option.name} can only be started by MainUI with root"
        val pkg = intent.component?.packageName ?: intent.`package` ?: option.pkg ?: ""
        val s = Session(game.key, game.systemId, game.file?.absolutePath, game.name, option.ref, pkg,
            intent.component?.className, System.currentTimeMillis(), intent.toUri(Intent.URI_INTENT_SCHEME))
        // The new session is in place before the game appears (MainActivity.onPause reads it while
        // the start is still under way); the previous one comes back if the start fails.
        val sw = swapIn(s)
        val err = try {
            if (rootLaunch) Root.startActivity(intent)?.let { "Could not start ${option.name}: $it" }
            else { ctx.startActivity(intent); null }
        } catch (e: Exception) {
            "Could not start ${option.name}: ${e.message}"
        }
        if (err != null) { swapBack(s, sw); return err }
        sw.prev?.let { account(it) }
        io.github.marsinsanity.mainui.core.SessionLog.log("launch ${game.name} with ${option.ref} ($pkg)")
        library.addRecent(game, option.ref)
        if (option.isRetroArch) introduceKeyboard(s) else autoLoad(s)
        return null
    }

    /**
     * Makes [s] the active session for a start that is about to happen, the paused game (the
     * new one may start in the same emulator) running again first. Returns the previous one.
     */
    private fun swapIn(s: Session): Swapped {
        unpark()
        val sw = synchronized(this) { Swapped(active, shownAt, loadPending).also { active = s } }
        loadPending = null
        shownAt = android.os.SystemClock.uptimeMillis()
        launchedPackages += s.pkg
        persist()
        return sw
    }

    /** What [swapIn] replaced: the previous session and its state, back if the start fails. */
    private class Swapped(val prev: Session?, val shownAt: Long, val loadPending: Session?)

    /**
     * The start of [s] failed: the previous session (still running) is the session again, as it
     * was (when it was shown, its pending auto load), paused again behind MainUI.
     */
    private fun swapBack(s: Session, sw: Swapped) {
        synchronized(this) {
            if (active === s) { active = sw.prev; shownAt = sw.shownAt; loadPending = sw.loadPending }
        }
        persist()
        park()
    }

    /**
     * Launches an Android app / Android game. [port]: the Ports system's entry it was started from
     * (its port file): the session, recents and Game Switcher go by that entry.
     */
    fun launchApp(pkg: String, label: String, asGame: Boolean, port: Game? = null): String? {
        val apps = io.github.marsinsanity.mainui.MainUI.apps
        // (Before anything is thawed: an app that can't start leaves the paused game paused.)
        val intent = apps.launchIntent(pkg) ?: return if (runCatching { ctx.packageManager.getPackageInfo(pkg, 0) }.isSuccess)
            "$label cannot be launched" else "$label is not installed ($pkg)"
        unpark() // never leave a paused game frozen behind another app
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val g = port ?: Game(SystemDef.ANDROID.id, null, label, pkg = pkg)
        val s = if (asGame) Session(g.key, g.systemId, g.file?.absolutePath, label, null, pkg, intent.component?.className,
            System.currentTimeMillis(), intent.toUri(Intent.URI_INTENT_SCHEME)) else null
        val sw = s?.let { swapIn(it) }
        return try {
            ctx.startActivity(intent)
            launchedPackages += pkg
            if (s != null) {
                sw?.prev?.let { account(it) }
                library.addRecent(g, null)
            }
            null
        } catch (e: Exception) {
            if (s != null && sw != null) swapBack(s, sw)
            "Could not start $label: ${e.message}"
        }
    }

    // ---- the game paused while MainUI is in front --------------------------------------
    // Standalone emulators keep running behind MainUI's own screens (ARMSX2 plays on behind the
    // Game Switcher, sound and all): Android only tells them they're in the background. So while
    // MainUI's screen is in front with a standalone emulator's game behind it, the game is
    // paused like for the in-game menu, and resumed before anything brings it back or
    // starts something else. RetroArch pauses itself (pause_nonactive); Android games are theirs.

    private val parkLock = Any()
    @Volatile private var parkWanted = false
    /** (package, Freezer generation) of the game paused while MainUI is in front; [API_PAUSED]: by the State API. */
    @Volatile private var parked: Pair<String, Int>? = null
    /** Sends park's State API pause and resume, in order, off the caller's thread. */
    private val apiParker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "park-api").apply { isDaemon = true } }

    /** A game is paused behind MainUI's screen. */
    val isParked: Boolean get() = parked != null

    /**
     * MainUI's screen came to the front: pause the active standalone emulator's game after
     * [delayMs] (time for the emulator to take in that it's in the background), unless
     * [unpark] comes first. A MainUI build pauses itself (frozen apps can come back without
     * sound); others are frozen (root).
     */
    fun park(delayMs: Long = 800) {
        val s = active ?: return
        if (s.emuRef?.startsWith("sa:") != true) return
        parkWanted = true
        Thread({
            Thread.sleep(delayMs)
            val def = s.emuRef?.let { emulators.defFor(it) }
            val api = def != null && StateApi.version(s.pkg) >= 2
            if (!api && (!settings.freezeStandalone || !Root.knownAvailable())) return@Thread
            synchronized(parkLock) {
                if (!parkWanted || parked != null || active !== s) return@Thread
                if (api) {
                    // Counted as paused at once, so an unpark meanwhile resumes it (its resume is sent after).
                    val mark = s.pkg to API_PAUSED
                    parked = mark
                    apiParker.execute {
                        val r = StateApi.call(s.pkg, "pause", timeoutMs = 2000)
                        if (r.ok) ApiPauses.paused(s.pkg)
                        // Answered but refused (no game yet, can't pause): frozen like emulators without the API.
                        else if (r.extras != null && settings.freezeStandalone && Root.knownAvailable()) synchronized(parkLock) {
                            if (parked === mark && Freezer.freeze(s.pkg, def?.freezeOnly)) parked = s.pkg to Freezer.generation
                        }
                        io.github.marsinsanity.mainui.core.SessionLog.log("paused ${s.name} (${s.pkg}) while MainUI is in front: ${r.error ?: "ok"}")
                    }
                } else if (Freezer.freeze(s.pkg, def?.freezeOnly)) {
                    parked = s.pkg to Freezer.generation
                    io.github.marsinsanity.mainui.core.SessionLog.log("paused ${s.name} (${s.pkg}) while MainUI is in front")
                }
            }
        }, "park").apply { isDaemon = true }.start()
    }

    /**
     * Resumes the game [park] paused (and stops a pending [park]). Called before the game comes
     * back, before anything is launched, when the session ends and when MainUI's screen leaves.
     * Off the UI thread it returns once the game runs again.
     */
    fun unpark() {
        parkWanted = false
        val resumed = synchronized(parkLock) {
            val p = parked ?: return
            parked = null
            io.github.marsinsanity.mainui.core.SessionLog.log("resumed ${p.first}")
            if (p.second != API_PAUSED) { Freezer.thaw(p.first, p.second); return }
            // (Always sent: a pause that answered late still took effect.)
            apiParker.submit {
                // Not to an emulator just force-stopped: the broadcast would start it again.
                if (!Root.knownAvailable() || Root.exec("pidof ${Root.q(p.first)}").out.isNotBlank())
                    StateApi.call(p.first, "resume", timeoutMs = 2000)
                ApiPauses.resumed(p.first)
            }
        }
        if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            runCatching { resumed.get(5, java.util.concurrent.TimeUnit.SECONDS) }
    }

    /** Ends the running session (play time accounting). Does not kill anything. */
    fun endActive() {
        unpark()
        // Taken under the lock, so two callers at once (exit thread, onResume) count it once.
        val s = synchronized(this) { active.also { active = null } } ?: return
        loadPending = null
        loadAsking = null
        ApiPauses.resumed(s.pkg) // (stopped or left: nothing to resume after a restart)
        account(s)
        persist()
    }

    /**
     * Adds [s]'s play time: uptime since it started (sleep doesn't count), at most a day for one
     * session (a game left running for days still gets its day, not nothing).
     */
    private fun account(s: Session) {
        if (isAdopted(s)) return // an emulator on its own: no game's play time
        val secs = ((android.os.SystemClock.uptimeMillis() - s.startedUptime) / 1000).coerceAtMost(24 * 3600L)
        if (secs > 0) {
            library.addPlayTime(s.gameKey, secs)
            library.updateRecent(s.gameKey) { it.playSeconds += secs }
        }
    }

    /** Re-opens the running emulator exactly where it was. */
    /**
     * Brings the running game back as it was, without starting anything in it:
     *  1. with root, its task is moved to the front (Android's own task switch);
     *  2. else, for an emulator whose game screen is an ordinary activity (Flycast...), the intent
     *     that started the game, which Android matches to the running task;
     *  3. else (activities that take new intents themselves, like RetroArch's) a plain launch.
     * A plain launch of an ordinary activity started a second, empty game screen on top of the
     * running one: Flycast showed black until its menu was opened and closed.
     */
    /** When MainUI last brought back a game that was already running (Game Switcher, menu), uptime ms. */
    @Volatile var resumedAt = 0L
        private set

    fun resume(): Boolean {
        val s = active ?: return false
        shownAt = android.os.SystemClock.uptimeMillis()
        resumedAt = shownAt
        // The task lookup runs root commands: never on the UI thread (it can wait seconds for the shell).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            Thread({ resumeSession(s) }, "resume").start()
            return true
        }
        return resumeSession(s)
    }

    private fun resumeSession(s: Session): Boolean {
        io.github.marsinsanity.mainui.core.SessionLog.log("resume ${s.name} (${s.pkg})")
        unpark() // running again before it is in front
        if (Root.knownAvailable()) {
            val id = runCatching { Tasks.idOf(Root.Shell.run("dumpsys activity recents", 4000).out, s.pkg) }.getOrNull()
            if (id != null && runCatching {
                    ctx.getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(id, 0); true
                }.getOrDefault(false)) {
                // Android may quietly refuse the switch: if MainUI is still in front a moment later, launch instead.
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val fg = io.github.marsinsanity.mainui.service.OverlayService.instance?.foregroundPackage
                    if (active === s && fg == ctx.packageName) relaunchToFront(s)
                }, 1200)
                return true
            }
        }
        return relaunchToFront(s)
    }

    private fun relaunchToFront(s: Session): Boolean {
        val launch = s.launchUri?.let { runCatching { Intent.parseUri(it, Intent.URI_INTENT_SCHEME) }.getOrNull() }
        if (launch != null && launch.component != null && isStandard(launch)) {
            // Same action / data / component as the task's root: Android only brings the task back.
            launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (runCatching { ctx.startActivity(launch); true }.getOrDefault(false)) return true
        }
        val intent = emulators.resumeIntent(s.pkg, s.activity) ?: return false
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    /** The activity [intent] starts uses the standard launch mode (a new instance per differing intent). */
    private fun isStandard(intent: Intent): Boolean = runCatching {
        ctx.packageManager.getActivityInfo(intent.component!!, 0).launchMode == android.content.pm.ActivityInfo.LAUNCH_MULTIPLE
    }.getOrDefault(false)

    /** Sessions made for an emulator opened by itself (no game from MainUI): see [adoptEmulator]. */
    fun isAdopted(s: Session?) = s?.gameKey?.startsWith(ADOPTED) == true

    /**
     * An emulator app opened by itself (from Emulators, Apps, its own menu) is in front, with no
     * game of MainUI's running: it becomes the session, so HOME opens the in-game menu for it
     * (Save / Load keys can be set there). Not in Recents or the Game Switcher. Replaces only no
     * session or another adopted one (a game MainUI started stays the session). Returns it, or null.
     */
    fun adoptEmulator(pkg: String, label: String): Session? {
        val cur = active
        if (cur != null && cur.pkg == pkg) return cur
        if (cur != null && !isAdopted(cur)) return null
        val s = Session("$ADOPTED$pkg", "", null, label, emulators.refForPackage(pkg), pkg, null, System.currentTimeMillis())
        synchronized(this) { if (active !== cur) return null; active = s }
        persist()
        io.github.marsinsanity.mainui.core.SessionLog.log("emulator $label ($pkg) opened by itself: a session for the in-game menu (${s.emuRef ?: "no recipe"})")
        return s
    }

    fun isRunning(s: Session): Boolean {
        if (!Root.knownAvailable()) return true // can't tell, assume so
        return Root.exec("pidof ${s.pkg}").out.isNotBlank()
    }

    fun controllerFor(s: Session): GameController =
        if (s.isRetroArch) RetroArchController(emulators.raCommandPort, s.pkg, s.path)
        else StandaloneController(s.pkg, s.emuRef?.let { emulators.defFor(it) }) { markSaved(s.gameKey) }

    /**
     * RetroArch only takes keys from the first three keyboards it hears from after its input
     * starts (volume keys and the like count); a later one becomes a controller with a port. So
     * once [s]'s game is up, MainUI's keyboard taps Ctrl (unused by RetroArch) to be among them
     * before anything else, and again a little later in case RetroArch restarted its input while
     * loading the game. Save / Load tap it again first anyway (RetroArchController.hotkey).
     */
    private fun introduceKeyboard(s: Session) {
        if (!Root.knownAvailable()) return
        Thread({
            logAutoState(s)
            val overlay = { io.github.marsinsanity.mainui.service.OverlayService.instance }
            val now = { android.os.SystemClock.uptimeMillis() }
            val t0 = now()
            while (overlay()?.foregroundPackage != s.pkg) {
                if (active !== s || overlay() == null || now() - t0 > 30_000) return@Thread
                Thread.sleep(250)
            }
            val inFront = now()
            for (settle in longArrayOf(2_500, 10_000)) {
                while (now() - maxOf(inFront, overlay()?.lastWindowChangeAt ?: 0L) < settle && now() - inFront < settle + 20_000) Thread.sleep(250)
                val o = overlay() ?: return@Thread
                if (active !== s || o.foregroundPackage != s.pkg || o.overlayShowing) return@Thread
                val ok = io.github.marsinsanity.mainui.core.VirtualKeys.primeFor(s.pkg, always = true)
                io.github.marsinsanity.mainui.core.SessionLog.log("MainUI Keyboard introduced to RetroArch: ${if (ok) "ok" else "FAILED"}")
            }
        }, "ra-keyboard").apply { isDaemon = true }.start()
    }

    /** Logs whether RetroArch has an auto slot to load for [s]'s game (savestate_auto_load). */
    private fun logAutoState(s: Session) = runCatching {
        val rom = s.path ?: return@runCatching
        val cfg = Root.readFile(emulators.raConfigPath(s.pkg))
        val name = RetroArchCores.stateFileName(rom, -1)
        val dirs = RetroArchCores.stateDirs(cfg, s.pkg, File(rom).parent)
        val found = Root.Shell.run("find ${dirs.joinToString(" ") { Root.q(it) }} -maxdepth 3 -type f " +
            "-name ${Root.q(RetroArchCores.globEscape(name))} -exec stat -c '%y %n' {} + 2>/dev/null", 5000).out.trim()
        io.github.marsinsanity.mainui.core.SessionLog.log("RetroArch auto load for ${s.name}: " +
            "savestate_auto_load ${RetroArchCores.cfgValue(cfg, "savestate_auto_load")}, state_slot ${RetroArchCores.cfgValue(cfg, "state_slot")}, " +
            if (found.isEmpty()) "no $name yet" else "will load $found")
    }

    // ---- auto load (standalone emulators) ----------------------------------------------
    // RetroArch loads its auto slot by itself when a game starts. Standalone emulators don't, so
    // with auto save & load on, a game MainUI saved with the emulator's save key (leaving it for
    // the Game Switcher, exiting it, the menu's Save) gets the emulator's load key once it has
    // started again: picking it in the Game Switcher continues it where it was left.

    private fun savedKey(gameKey: String) = "saved_state:" + sha1(gameKey)

    /** MainUI saved [gameKey]'s state with its emulator's save key. */
    fun markSaved(gameKey: String) { settings.prefs.edit().putLong(savedKey(gameKey), System.currentTimeMillis()).apply() }

    fun hasSavedState(gameKey: String) = settings.prefs.contains(savedKey(gameKey))

    /**
     * Presses the emulator's load key once [s]'s game is up: when the emulator's window is in
     * front (seen by the accessibility service; without it, after a fixed wait), plus time for
     * the game to start running. Skipped if another game started meanwhile, the user left it, or
     * MainUI's menu is open.
     */
    /**
     * The game whose last save MainUI is about to load (auto load waiting for the game to be up).
     * Until it is loaded the running game is a fresh start: an automatic save now (leaving for
     * the Game Switcher, Exit) would overwrite the real save with it, so those skip the save.
     */
    @Volatile private var loadPending: Session? = null

    /** True while [s]'s auto load hasn't happened yet (see [loadPending]). */
    fun autoLoadPending(s: Session?) = s != null && loadPending === s

    /**
     * A game MainUI never saved whose emulator is still asked for a resume state: automatic saves
     * wait quietly (they would queue behind the load, and could overwrite the state it finds).
     */
    @Volatile private var loadAsking: Session? = null

    fun autoLoadAsking(s: Session?) = s != null && loadAsking === s

    /**
     * [s] was loaded (or saved) by hand from the menu: what runs now is what the user wants kept,
     * so automatic saves are made again (and a pending auto load doesn't load over it).
     */
    fun loadDone(s: Session?) { if (s != null && loadPending === s) { loadPending = null; io.github.marsinsanity.mainui.core.SessionLog.log("${s.name}: loaded / saved by hand, auto save on again") } }

    private fun autoLoad(s: Session) {
        val log = io.github.marsinsanity.mainui.core.SessionLog::log
        if (!settings.autoSaveOnExit) return
        val c = controllerFor(s) as? StandaloneController ?: return
        if (c.states == StateSupport.NONE) return // no save states at all (PICO-8, Switch)
        if (c.states == StateSupport.DIRECT) { autoLoadDirect(s, c, hasSavedState(s.gameKey)); return }
        if (!hasSavedState(s.gameKey)) { log("auto load ${s.name}: skipped, MainUI never saved it"); return }
        if (c.states != StateSupport.HOTKEY || c.loadSpec == null) {
            log("auto load ${s.name}: skipped, no load key MainUI can press (states ${c.states}, key ${c.loadSpec}, root ${Root.knownAvailable()})"); return
        }
        val settle = c.loadDelayMs
        if (settle <= 0) { log("auto load ${s.name}: turned off for this emulator"); return }
        log("auto load ${s.name}: waiting for ${s.pkg}, then ${settle} ms, then ${c.loadSpec}")
        loadPending = s
        Thread({
            val overlay = { io.github.marsinsanity.mainui.service.OverlayService.instance }
            val now = { android.os.SystemClock.uptimeMillis() }
            val t0 = now()
            if (overlay() != null) {
                while (overlay()?.foregroundPackage != s.pkg) {
                    if (active !== s || now() - t0 > 30_000) {
                        log("auto load ${s.name}: gave up, ${s.pkg} never came to the front (in front: ${overlay()?.foregroundPackage})"); return@Thread
                    }
                    Thread.sleep(250)
                }
                toast("Loading your last save in about ${(settle + 999) / 1000} s...")
                // Count from the emulator's last screen change (its loading screen giving way to the
                // game, a dialog), not its first window: the game only takes the key once it runs.
                val inFront = now()
                while (true) {
                    val o = overlay() ?: break
                    if (active !== s || o.foregroundPackage != s.pkg) {
                        log("auto load ${s.name}: cancelled, left the game (in front: ${o.foregroundPackage})"); return@Thread
                    }
                    if (now() - maxOf(inFront, o.lastWindowChangeAt) >= settle || now() - inFront > settle + 60_000) break
                    Thread.sleep(250)
                }
            } else Thread.sleep(settle + 4000)
            // MainUI's menu open just then: after it closes (the user may have loaded by hand meanwhile).
            val waitFrom = now()
            while (overlay()?.overlayShowing == true && active === s && loadPending === s && now() - waitFrom < 300_000) Thread.sleep(250)
            if (now() - waitFrom > 250) Thread.sleep(1500) // the game running again
            val o = overlay()
            if (loadPending !== s) { log("auto load ${s.name}: not needed, loaded or saved by hand"); return@Thread }
            if (active !== s || (o != null && (o.foregroundPackage != s.pkg || o.overlayShowing))) {
                log("auto load ${s.name}: cancelled (in front: ${o?.foregroundPackage}, menu open: ${o?.overlayShowing})"); return@Thread
            }
            val ok = runCatching { c.load(0) }.getOrDefault(false)
            if (ok && loadPending === s) loadPending = null
            io.github.marsinsanity.mainui.core.SessionLog.log("auto load ${s.name}: load key ${if (ok) "sent" else "FAILED"} after ${settle} ms")
            if (ok) toast("Loaded your last save")
        }, "auto-load").apply { isDaemon = true }.start()
    }

    /**
     * Auto load through the State API (the emulator's MainUI-Android build): the emulator itself
     * waits until this game (started now, not one running before it) has booted and runs, then
     * loads MainUI's resume state. No keys, no guessing how long the game takes to start.
     * Asked even without MainUI's [marked] (its data cleared, the rom moved or converted): the
     * emulator knows whether it has the state. Automatic saves wait for it only when marked.
     */
    private fun autoLoadDirect(s: Session, c: StandaloneController, marked: Boolean) {
        val log = io.github.marsinsanity.mainui.core.SessionLog::log
        if (c.loadDelayMs <= 0) { log("auto load ${s.name}: turned off for this emulator"); return }
        log("auto load ${s.name}: ${s.pkg} loads MainUI's resume state once the game runs (State API)")
        if (marked) loadPending = s else loadAsking = s
        Thread({
            val r = StateApi.call(s.pkg, "load", StateApi.RESUME_SLOT, waitMs = 180_000, settleMs = 2_000, notBefore = s.startedUptime)
            if (loadAsking === s) loadAsking = null
            val mine = loadPending === s
            // The load never ran (the game didn't come up in time): still a fresh start, so
            // automatic saves stay off rather than overwrite the state that wasn't loaded.
            val notTried = !r.ok && (r.extras == null || r.error == "the game wasn't ready" || r.error == "no game is running")
            if (mine && !notTried) loadPending = null
            log("auto load ${s.name}: ${if (r.ok) "loaded" else "not loaded: ${r.error}"}")
            when {
                r.ok -> if ((mine || !marked) && active === s) toast("Loaded your last save")
                // (No resume state: nothing was left to continue from.)
                // (A game MainUI never saved, whose load never ran: nothing to say.)
                active === s && r.error?.contains("empty") != true && (marked || !notTried) -> toast("Your last save wasn't loaded: ${r.error}")
            }
        }, "auto-load").apply { isDaemon = true }.start()
    }

    fun toast(msg: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // ---- screenshots -----------------------------------------------------------------
    fun screenshotFile(gameKey: String): File = File(storage.screenshotDir, sha1(gameKey) + ".png")

    fun saveScreenshot(gameKey: String, bmp: Bitmap): File? = runCatching {
        val f = screenshotFile(gameKey)
        writeImage(f, bmp)
        library.updateRecent(gameKey) { it.screenshot = f.absolutePath }
        f
    }.getOrNull()

    /** Where a save slot's thumbnail is. Looked up while drawing, so it makes no folders: writers do. */
    fun slotThumb(gameKey: String, slot: Int): File =
        File(File(storage.stateThumbDir, sha1(gameKey)), "slot$slot.png")

    companion object {
        const val ADOPTED = "emulator:"
        /** [parked]'s generation for a game paused through the State API (Freezer's start at 0). */
        private const val API_PAUSED = -1
        /**
         * Writes [bmp] to [f] as PNG without ever leaving a half-written [f]: into a temporary file
         * first, then swapped in. A big screenshot takes about a second to write, and the Game
         * Switcher (opening at the same time) read it half done: the top of the picture, black below.
         */
        fun writeImage(f: File, bmp: Bitmap) {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, ".${f.name}.${Thread.currentThread().id}.tmp")
            try {
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                if (!tmp.renameTo(f)) { f.delete(); if (!tmp.renameTo(f)) throw java.io.IOException("could not replace ${f.name}") }
            } finally {
                tmp.delete()
            }
        }

        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
