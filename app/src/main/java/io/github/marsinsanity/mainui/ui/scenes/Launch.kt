package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.GameSystem
import io.github.marsinsanity.mainui.data.SystemDef
import io.github.marsinsanity.mainui.ui.SceneHost

/** Shared helpers for starting games from any list. */
object Launch {

    fun systemDef(id: String): SystemDef? = MainUI.systems.byId(id)
        ?: MainUI.games.systems(MainUI.settings.showUnknownFolders).firstOrNull { it.id == id }?.def

    fun gameSystem(id: String): GameSystem? =
        MainUI.games.systems(MainUI.settings.showUnknownFolders).firstOrNull { it.id == id }

    /** Starts [game]; resumes it instead if it is the running session. */
    fun game(host: SceneHost, game: Game) {
        val active = MainUI.sessions.active
        if (active != null && active.gameKey == game.key) {
            // Only bring the emulator back if it is still running; otherwise
            // relaunch the game (resuming a dead emulator would open it empty).
            host.io({ MainUI.sessions.isRunning(active) }) { running ->
                if (!(running && MainUI.sessions.resume())) launchFresh(host, game)
            }
            return
        }
        launchFresh(host, game)
    }

    private fun launchFresh(host: SceneHost, game: Game) {
        if (game.isAndroid) {
            val err = MainUI.sessions.launchApp(game.pkg!!, game.name, asGame = true)
            if (err != null) host.toast(err)
            return
        }
        val def = systemDef(game.systemId)
        if (def == null) { host.toast("Unknown system ${game.systemId}"); return }
        if (game.file?.exists() != true) { host.toast("File not found"); return }
        val em = MainUI.emulators
        host.io({
            // Cores may have been added in RetroArch since the last scan: look again before giving up.
            var opt = em.choose(def, game)
            if (opt != null && opt.isRetroArch && opt.installed != true) { em.refreshCores(); opt = em.choose(def, game) }
            opt
        }) { opt ->
            if (opt != null && opt.isRetroArch && opt.installed == false) {
                CoreInstall.offer(host, opt.core) { start(host, def, game) }
            } else start(host, def, game)
        }
    }

    private fun start(host: SceneHost, def: SystemDef, game: Game) {
        host.io({ MainUI.sessions.launch(def, game, null) }) { err ->
            if (err != null) host.push(io.github.marsinsanity.mainui.ui.DialogScene(host, "Can't start game",
                "$err\n\nInstall it, or press SELECT on the game > Core to pick another emulator.", "OK", null))
        }
    }

    fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
