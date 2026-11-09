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
) {
    val isRetroArch get() = emuRef?.startsWith("ra:") == true
    val isAndroidApp get() = emuRef == null

    fun json() = JSONObject().put("gameKey", gameKey).put("system", systemId).put("path", path).put("name", name)
        .put("emu", emuRef).put("pkg", pkg).put("activity", activity).put("startedAt", startedAt)

    companion object {
        fun from(o: JSONObject) = Session(o.getString("gameKey"), o.getString("system"), o.optString("path").ifEmpty { null },
            o.getString("name"), o.optString("emu").ifEmpty { null }, o.getString("pkg"),
            o.optString("activity").ifEmpty { null }, o.getLong("startedAt"))
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
    var active: Session? = runCatching {
        settings.prefs.getString("active_session", null)?.let { Session.from(JSONObject(it)) }
    }.getOrNull()
        private set

    /** Package names of every app launched from MainUI in this boot (for the process manager). */
    val launchedPackages: MutableSet<String> = linkedSetOf()

    private fun persist() {
        settings.prefs.edit().putString("active_session", active?.json()?.toString()).apply()
    }

    /** Launches a rom with the chosen emulator. Returns an error message or null. */
    fun launch(sys: SystemDef, game: Game, opt: EmuOption?): String? {
        val option = opt ?: emulators.choose(sys, game) ?: return "No emulator available for ${sys.name}"
        if (option.installed == false) return "${option.name} is not installed"
        val intent = try { emulators.buildIntent(game, option) } catch (e: IllegalStateException) { return e.message }
        endActive()
        return try {
            ctx.startActivity(intent)
            val pkg = intent.component?.packageName ?: intent.`package` ?: option.pkg ?: ""
            begin(Session(game.key, game.systemId, game.file?.absolutePath, game.name, option.ref, pkg,
                intent.component?.className, System.currentTimeMillis()))
            library.addRecent(game, option.ref)
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
                begin(Session(g.key, g.systemId, null, label, null, pkg, intent.component?.className, System.currentTimeMillis()))
                library.addRecent(g, null)
            }
            null
        } catch (e: Exception) {
            "Could not start $label: ${e.message}"
        }
    }

    private fun begin(s: Session) {
        active = s
        launchedPackages += s.pkg
        persist()
    }

    /** Ends the running session (play time accounting). Does not kill anything. */
    fun endActive() {
        val s = active ?: return
        val secs = (System.currentTimeMillis() - s.startedAt) / 1000
        if (secs in 1..(24 * 3600)) {
            library.addPlayTime(s.gameKey, secs)
            library.updateRecent(s.gameKey) { it.playSeconds += secs }
        }
        active = null
        persist()
    }

    /** Re-opens the running emulator exactly where it was. */
    fun resume(): Boolean {
        val s = active ?: return false
        val intent = emulators.resumeIntent(s.pkg, s.activity) ?: return false
        return runCatching { ctx.startActivity(intent); true }.getOrDefault(false)
    }

    fun isRunning(s: Session): Boolean {
        if (!Root.knownAvailable()) return true // can't tell, assume so
        return Root.exec("pidof ${s.pkg}").out.isNotBlank()
    }

    fun controllerFor(s: Session): GameController =
        if (s.isRetroArch) RetroArchController(emulators.raCommandPort, s.pkg)
        else StandaloneController(s.pkg, s.emuRef?.let { emulators.defFor(it) })

    // ---- screenshots -----------------------------------------------------------------
    fun screenshotFile(gameKey: String): File = File(storage.screenshotDir, sha1(gameKey) + ".png")

    fun saveScreenshot(gameKey: String, bmp: Bitmap): File? = runCatching {
        val f = screenshotFile(gameKey)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
        library.updateRecent(gameKey) { it.screenshot = f.absolutePath }
        f
    }.getOrNull()

    fun slotThumb(gameKey: String, slot: Int): File =
        File(File(storage.stateThumbDir, sha1(gameKey)).apply { mkdirs() }, "slot$slot.png")

    companion object {
        fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
