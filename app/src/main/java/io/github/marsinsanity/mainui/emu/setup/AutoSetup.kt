package io.github.marsinsanity.mainui.emu.setup

import android.content.Context
import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.core.SessionLog
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.scraper.RomIdent
import java.io.File

/** Something about an emulator kept its setup from finishing; the message says what to do. */
class SetupProblem(message: String) : Exception(message)

/** Writes the [ControlsProfile] into one emulator's own settings (root: they're in its private folders). */
interface EmulatorSetup {
    /** The emulator's name ("Dolphin"). */
    val name: String

    /** The installed apps this sets up: package to the name shown. */
    fun installed(): List<Pair<String, String>>

    /** Sets up [pkg] (stopped first) and says what it did; [SetupProblem] when it couldn't. */
    fun apply(pkg: String, label: String, p: ControlsProfile, pad: PadInfo, wiiGames: () -> List<String>): String
}

/** Settings > Emulator controls > Set up emulators: every [EmulatorSetup] whose app is installed. */
object AutoSetup {
    private const val TAG = "MainUI.Setup"

    val all: List<EmulatorSetup> = listOf(DolphinSetup, PpssppSetup, Armsx2Setup, MelonSetup, SeedlessSetup)

    data class Result(val label: String, val ok: Boolean, val message: String)

    /** The names of the emulators MainUI can set up ("Dolphin, PPSSPP"). */
    val names: String get() = all.joinToString(", ") { it.name }

    /** The installed apps that would be set up (and stopped). */
    fun installed(): List<Pair<String, String>> = all.flatMap { it.installed() }

    fun runAll(ctx: Context): List<Result> {
        if (!Root.available()) return listOf(Result("Root", false, "Setting up emulators needs root (their settings are in their private folders)."))
        val pad = PadInfo.detect(ctx) ?: return listOf(Result("Controller", false, "No built-in controller found."))
        val p = Controls.profile
        val apps = installed()
        if (apps.isEmpty()) return listOf(Result("Emulators", false, "None of the emulators MainUI can set up is installed ($names)."))
        val wii by lazy { wiiDiscIds() }
        val results = all.flatMap { s ->
            s.installed().map { (pkg, label) ->
                try {
                    Result(label, true, s.apply(pkg, label, p, pad) { wii })
                } catch (e: SetupProblem) {
                    Result(label, false, e.message ?: "failed")
                } catch (e: Exception) {
                    Log.w(TAG, "setting up $pkg failed", e)
                    Result(label, false, e.message ?: e.javaClass.simpleName)
                }
            }
        }
        if (results.any { it.ok }) Controls.markSetUp(p)
        // A game running in a stopped emulator has ended.
        MainUI.sessions.active?.let { a -> if (apps.any { it.first == a.pkg }) MainUI.sessions.endActive(a) }
        SessionLog.log("Emulator setup on ${pad.name} (${pad.controllerNumber}): " + results.joinToString("; ") { "${it.label} ${if (it.ok) "ok" else "failed"}: ${it.message}" })
        return results
    }

    /**
     * The Wii game [discId]'s controller changed in its options: written to every set up Dolphin
     * at once (stopping it). Null when done, else why not.
     */
    fun applyWiiGame(discId: String): String? {
        if (!Controls.everSetUp) return null // nothing set up yet: the next Set up writes it
        if (!Root.available()) return "Needs root"
        val p = Controls.profile
        val problems = DolphinSetup.installed().mapNotNull { (pkg, label) ->
            try { DolphinSetup.applyGame(pkg, discId, Controls.wiiFor(discId, p), p); null } catch (e: Exception) { "$label: ${e.message}" }
        }
        return problems.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** The disc ids of the Wii games in the library, read from their files. */
    fun wiiDiscIds(): List<String> {
        val sys = MainUI.games.systems(includeUnknown = false).firstOrNull { it.def.id == "wii" } ?: return emptyList()
        val ids = LinkedHashSet<String>()
        fun walk(dir: File?, depth: Int) {
            for (g: Game in MainUI.games.list(sys, dir)) {
                val f = g.file ?: continue
                if (g.isFolder) { if (depth < 3) walk(f, depth + 1); continue }
                runCatching { RomIdent.discId6(f) }.getOrNull()?.let { ids += it }
            }
        }
        walk(null, 0)
        return ids.toList()
    }
}
