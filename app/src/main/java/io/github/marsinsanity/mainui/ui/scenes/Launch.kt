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
            if (MainUI.sessions.resume()) return
        }
        if (game.isAndroid) {
            val err = MainUI.sessions.launchApp(game.pkg!!, game.name, asGame = true)
            if (err != null) host.toast(err)
            return
        }
        val def = systemDef(game.systemId)
        if (def == null) { host.toast("Unknown system ${game.systemId}"); return }
        if (game.file?.exists() != true) { host.toast("File not found"); return }
        host.io({ MainUI.sessions.launch(def, game, null) }) { err -> if (err != null) host.toast(err, 3000) }
    }

    fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
