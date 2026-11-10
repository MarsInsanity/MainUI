package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.data.Game
import io.github.marsinsanity.mainui.data.GameSystem
import io.github.marsinsanity.mainui.data.SystemDef
import io.github.marsinsanity.mainui.emu.ZipImages
import io.github.marsinsanity.mainui.ui.BusyScene
import io.github.marsinsanity.mainui.ui.DialogScene
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
            // Off the UI thread: both the running check and the resume use root.
            host.io({ MainUI.sessions.isRunning(active) && MainUI.sessions.resume() }) { resumed ->
                if (!resumed) launchFresh(host, game)
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
        def.menuItemFor(game.file)?.let { launchMenuItem(host, def, game, it); return }
        if (game.file?.exists() != true) { host.toast("File not found"); return }
        val em = MainUI.emulators
        host.io({
            // Cores may have been added in RetroArch since the last scan: look again before giving up.
            var opt = em.choose(def, game)
            if (opt != null && opt.isRetroArch && opt.installed != true) { em.refreshCores(); opt = em.choose(def, game) }
            opt
        }) { opt ->
            when {
                opt == null || opt.installed != false -> start(host, def, game)
                opt.isRetroArch && em.raPackage != null -> CoreInstall.offer(host, opt.core) { start(host, def, game) }
                else -> {
                    // The emulator itself is missing: offer its page in the App Store.
                    val emuId = if (opt.isRetroArch) "retroarch" else opt.ref.removePrefix("sa:")
                    val emuName = if (opt.isRetroArch) "RetroArch" else opt.name
                    host.push(io.github.marsinsanity.mainui.ui.DialogScene(host, "Emulator not installed",
                        "${def.name} games need $emuName, which is not installed.\nGet it now?", "GET", "CANCEL") {
                        host.push(EmulatorDetailScene(host, emuId))
                    })
                }
            }
        }
    }

    /**
     * A system's menu entry (PICO-8: Splore), from its game list, Recents, Favorites or the Game
     * Switcher: always with the entry's emulator, and its file needn't exist.
     */
    private fun launchMenuItem(host: SceneHost, def: SystemDef, game: Game, item: io.github.marsinsanity.mainui.data.SystemMenuItem) {
        host.io({ MainUI.emulators.optionFor(item.emulator) }) { opt ->
            when {
                opt == null -> host.toast("Unknown emulator ${item.emulator}")
                opt.installed != true -> host.push(DialogScene(host, "Emulator not installed",
                    "${item.name} needs ${opt.name}, which is not installed.\nGet it now?", "GET", "CANCEL") {
                    host.push(EmulatorDetailScene(host, item.emulator.removePrefix("sa:")))
                })
                else -> host.io({ MainUI.sessions.launch(def, game, opt) }) { err ->
                    if (err != null) host.push(DialogScene(host, "Can't start ${item.name}", err, "OK", null))
                }
            }
        }
    }

    private fun start(host: SceneHost, def: SystemDef, game: Game) {
        val f = game.file
        if (def.unzip.isNotEmpty() && f != null && f.isFile && f.extension.equals("zip", true)) { startZipped(host, def, game, f); return }
        host.io({ MainUI.sessions.launch(def, game, null) }) { err ->
            if (err != null) host.push(io.github.marsinsanity.mainui.ui.DialogScene(host, "Can't start game",
                "$err\n\nInstall it, or press SELECT on the game > Core to pick another emulator.", "OK", null))
        }
    }

    /**
     * A zipped disc image for an emulator that can't open archives (hakuX): launch the image an
     * earlier launch unpacked beside the zip, or offer to unpack it now (once).
     */
    private fun startZipped(host: SceneHost, def: SystemDef, game: Game, zip: java.io.File) {
        ZipImages.unpacked(zip, def.unzip)?.let { start(host, def, game.copy(file = it)); return }
        host.io({ runCatching { ZipImages.entry(zip, def.unzip) } }) { r ->
            val e = r.getOrNull()
            if (e == null) {
                host.push(DialogScene(host, "Can't start game", r.exceptionOrNull()?.let { "${zip.name} can't be read: ${it.message}" }
                    ?: "${zip.name} holds no ${def.unzip.joinToString("/") { ".$it" }} image.", "OK", null))
                return@io
            }
            val emu = MainUI.emulators.choose(def, game)?.name ?: "The emulator"
            val type = e.first.substringAfterLast('.').lowercase()
            host.push(DialogScene(host, "Unpack ${game.name}?",
                "$emu can't open .zip files. MainUI unpacks the .$type image (${ZipImages.gb(e.second)}) next to the zip, " +
                    "once; later launches use it.", "UNPACK", "CANCEL") { unpack(host, def, game, zip) })
        }
    }

    private fun unpack(host: SceneHost, def: SystemDef, game: Game, zip: java.io.File) {
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val busy = BusyScene(host, "Unpacking ${game.name}...") { stop.set(true) }
        host.push(busy)
        host.io({
            runCatching {
                ZipImages.unpack(zip, def.unzip, { done, total ->
                    busy.text = "Unpacking ${game.name}\n${ZipImages.gb(done)} / ${ZipImages.gb(total)}"
                    main.post { host.invalidate() }
                }, { stop.get() })
            }
        }) { r ->
            host.pop(busy)
            MainUI.games.invalidate()
            val img = r.getOrElse { err ->
                host.push(DialogScene(host, "Can't unpack ${game.name}", err.message ?: err.toString(), "OK", null))
                return@io
            }
            val play = { start(host, def, game.copy(file = img)) }
            host.push(DialogScene(host, "Unpacked",
                "Delete ${zip.name} to free ${ZipImages.gb(zip.length())}? MainUI launches the unpacked image from now on.",
                "DELETE", "KEEP", onConfirm = { zip.delete(); MainUI.games.invalidate(); play() }, onCancel = play))
        }
    }

    fun formatTime(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
