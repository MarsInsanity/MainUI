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
) {
    val isRetroArch get() = emuRef?.startsWith("ra:") == true
    val isAndroidApp get() = emuRef == null

    fun json() = JSONObject().put("gameKey", gameKey).put("system", systemId).put("path", path).put("name", name)
        .put("emu", emuRef).put("pkg", pkg).put("activity", activity).put("startedAt", startedAt).put("launch", launchUri)

    companion object {
        fun from(o: JSONObject) = Session(o.getString("gameKey"), o.getString("system"), o.optString("path").ifEmpty { null },
            o.getString("name"), o.optString("emu").ifEmpty { null }, o.getString("pkg"),
            o.optString("activity").ifEmpty { null }, o.getLong("startedAt"), o.optString("launch").ifEmpty { null })
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
        endActive()
        return try {
            if (emulators.defFor(option.ref)?.rootLaunch == true) {
                if (!Root.available()) return "${option.name} can only be started by MainUI with root"
                Root.startActivity(intent)?.let { return "Could not start ${option.name}: $it" }
            } else ctx.startActivity(intent)
            val pkg = intent.component?.packageName ?: intent.`package` ?: option.pkg ?: ""
            val s = Session(game.key, game.systemId, game.file?.absolutePath, game.name, option.ref, pkg,
                intent.component?.className, System.currentTimeMillis(), intent.toUri(Intent.URI_INTENT_SCHEME))
            io.github.marsinsanity.mainui.core.SessionLog.log("launch ${game.name} with ${option.ref} ($pkg)")
            begin(s)
            library.addRecent(game, option.ref)
            if (option.isRetroArch) introduceKeyboard(s) else autoLoad(s)
            null
        } catch (e: Exception) {
            "Could not start ${option.name}: ${e.message}"
        }
    }

    /** Launches an Android app / Android game. */
    fun launchApp(pkg: String, label: String, asGame: Boolean): String? {
        val apps = io.github.marsinsanity.mainui.MainUI.apps
        val intent = apps.launchIntent(pkg) ?: return "$label cannot be launched"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            ctx.startActivity(intent)
            launchedPackages += pkg
            if (asGame) {
                val g = Game(SystemDef.ANDROID.id, null, label, pkg = pkg)
                endActive()
                begin(Session(g.key, g.systemId, null, label, null, pkg, intent.component?.className, System.currentTimeMillis(),
                    intent.toUri(Intent.URI_INTENT_SCHEME)))
                library.addRecent(g, null)
            }
            null
        } catch (e: Exception) {
            "Could not start $label: ${e.message}"
        }
    }

    private fun begin(s: Session) {
        synchronized(this) { active = s }
        shownAt = android.os.SystemClock.uptimeMillis()
        launchedPackages += s.pkg
        persist()
    }

    /** Ends the running session (play time accounting). Does not kill anything. */
    fun endActive() {
        // Taken under the lock, so two callers at once (exit thread, onResume) count it once.
        val s = synchronized(this) { active.also { active = null } } ?: return
        val secs = (System.currentTimeMillis() - s.startedAt) / 1000
        if (secs in 1..(24 * 3600)) {
            library.addPlayTime(s.gameKey, secs)
            library.updateRecent(s.gameKey) { it.playSeconds += secs }
        }
        persist()
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

    fun isRunning(s: Session): Boolean {
        if (!Root.knownAvailable()) return true // can't tell, assume so
        return Root.exec("pidof ${s.pkg}").out.isNotBlank()
    }

    fun controllerFor(s: Session): GameController =
        if (s.isRetroArch) RetroArchController(emulators.raCommandPort, s.pkg)
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
    private fun autoLoad(s: Session) {
        if (!settings.autoSaveOnExit || !hasSavedState(s.gameKey)) return
        val c = controllerFor(s) as? StandaloneController ?: return
        if (c.states != StateSupport.HOTKEY || c.loadSpec == null) return
        val settle = c.loadDelayMs
        if (settle <= 0) return // turned off for this emulator
        Thread({
            val overlay = { io.github.marsinsanity.mainui.service.OverlayService.instance }
            val now = { android.os.SystemClock.uptimeMillis() }
            val t0 = now()
            if (overlay() != null) {
                while (overlay()?.foregroundPackage != s.pkg) {
                    if (active !== s || now() - t0 > 30_000) return@Thread
                    Thread.sleep(250)
                }
                toast("Loading your last save in about ${(settle + 999) / 1000} s...")
                // Count from the emulator's last screen change (its loading screen giving way to the
                // game, a dialog), not its first window: the game only takes the key once it runs.
                val inFront = now()
                while (true) {
                    val o = overlay() ?: break
                    if (active !== s || o.foregroundPackage != s.pkg) return@Thread
                    if (now() - maxOf(inFront, o.lastWindowChangeAt) >= settle || now() - inFront > settle + 60_000) break
                    Thread.sleep(250)
                }
            } else Thread.sleep(settle + 4000)
            val o = overlay()
            if (active !== s || (o != null && (o.foregroundPackage != s.pkg || o.overlayShowing))) return@Thread
            val ok = runCatching { c.load(0) }.getOrDefault(false)
            io.github.marsinsanity.mainui.core.SessionLog.log("auto load ${s.name}: load key ${if (ok) "sent" else "FAILED"} after ${settle} ms")
            if (ok) toast("Loaded your last save")
        }, "auto-load").apply { isDaemon = true }.start()
    }

    private fun toast(msg: String) = android.os.Handler(android.os.Looper.getMainLooper()).post {
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
