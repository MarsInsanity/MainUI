package io.github.marsinsanity.mainui.ui.scenes

import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.data.GameRepository
import io.github.marsinsanity.mainui.data.GameSystem
import io.github.marsinsanity.mainui.data.RomUnzip
import io.github.marsinsanity.mainui.data.SystemRegistry
import io.github.marsinsanity.mainui.ui.BusyScene
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.PopMenuScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.ui.UiItem
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * "Unzip all" for a system whose emulators can't open .zip files (Game & Watch's .mgw, ...):
 * offered on its "No games found" screen and in its SELECT menu. Looks inside the zips first,
 * then offers: unzip all, then delete all the zips (room for everything; nothing deleted unless
 * it all got through), unzip one at a time deleting each zip (room for the biggest one only,
 * for a card that can't hold the games twice), or unzip and keep the zips (room for everything),
 * each with what it needs and what is free on that storage.
 */
object UnzipFlow {
    /** .zip files counted in [sys]'s folders that it can't play as they are; 0 = not offered. */
    fun zips(sys: GameSystem): Int =
        if (sys.def.extensions.isEmpty() || "zip" in sys.def.extensions) 0 else sys.otherFiles["zip"] ?: 0

    fun start(host: SceneHost, sys: GameSystem, done: () -> Unit) {
        val busy = BusyScene(host, "Looking inside the .zip files...")
        host.push(busy)
        host.io({
            runCatching {
                RomUnzip.scan(sys.dirs, { n -> sys.def.accepts(File(n)) }) { d ->
                    d in GameRepository.MEDIA_FOLDERS || d in SystemRegistry.IGNORED_FOLDERS
                }
            }
        }) { r ->
            host.pop(busy)
            val plan = r.getOrElse { host.push(DialogScene(host, "Can't unzip", it.message ?: it.toString(), "OK", null)); return@io }
            val exts = sys.def.extensions.sorted().joinToString(" ") { ".$it" }
            if (plan.archives.isEmpty()) {
                host.push(DialogScene(host, "Nothing to unzip", "None of the .zip files in ${sys.name} holds $exts games.", "OK", null))
                return@io
            }
            // Each storage (SD card, internal) needs room for what is unpacked there.
            val byStorage = plan.archives.groupBy { a -> sys.dirs.firstOrNull { a.zip.path.startsWith(it.path + "/") } ?: a.zip.parentFile }
            fun free(dir: File?) = dir?.usableSpace ?: 0L
            fun fits(mode: RomUnzip.Mode) = byStorage.all { (dir, list) -> free(dir) >= RomUnzip.spaceNeeded(list, mode) }
            fun need(mode: RomUnzip.Mode) = byStorage.values.maxOf { RomUnzip.spaceNeeded(it, mode) }
            val freeNow = byStorage.keys.minOfOrNull { free(it) } ?: 0L
            val zips = plan.archives.size
            fun option(label: String, mode: RomUnzip.Mode): UiItem {
                val ok = fits(mode)
                return UiItem(label, sub = if (ok) null else "needs ${RomUnzip.size(need(mode))}", disabled = !ok,
                    onSelect = { run(host, sys, plan, mode, done) })
            }
            val items = mutableListOf(
                option("Unzip all, then delete zips", RomUnzip.Mode.DELETE_AFTER),
                option("One at a time, delete each", RomUnzip.Mode.DELETE_EACH),
                option("Unzip, keep the zips", RomUnzip.Mode.KEEP),
                UiItem("${RomUnzip.size(plan.bytes)} unpacked, ${RomUnzip.size(freeNow)} free", disabled = true),
            )
            when {
                !fits(RomUnzip.Mode.DELETE_EACH) -> items += UiItem("Not enough free space: free some up first", disabled = true)
                !fits(RomUnzip.Mode.DELETE_AFTER) -> items += UiItem("Only room for one at a time", disabled = true)
            }
            host.push(PopMenuScene(host, "Unzip ${plural(plan.games, "game")} from $zips .zip ${if (zips == 1) "file" else "files"}", items))
        }
    }

    private fun run(host: SceneHost, sys: GameSystem, plan: RomUnzip.Plan, mode: RomUnzip.Mode, done: () -> Unit) {
        val stop = AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val busy = BusyScene(host, "Unzipping...") { stop.set(true) }
        host.push(busy)
        host.io({
            val r = RomUnzip.unpack(plan, mode, { i, n, name ->
                busy.text = "Unzipping $i / $n\n$name"
                main.post { host.invalidate() }
            }, { stop.get() }, fallback = { zip, dir ->
                // A folder MainUI can't write to (some SD cards): Android's unzip as root, never overwriting.
                Root.knownAvailable() && Root.exec("unzip -n -q -d ${Root.q(dir.path)} ${Root.q(zip.path)}").ok
            })
            MainUI.games.invalidate()
            runCatching { MainUI.games.countGames(sys) }
            r
        }) { r ->
            host.pop(busy)
            val lines = buildList {
                add("Unpacked ${plural(r.unpacked, "file")} from ${r.zipsDone} of ${plan.archives.size} .zip files.")
                if (r.existing > 0) add("${plural(r.existing, "file")} already there, left as they were.")
                if (mode != RomUnzip.Mode.KEEP) add("${plural(r.zipsDeleted, ".zip file")} deleted.")
                if (r.stopped) add("Stopped: the rest are still zipped.")
                // All at once: the zips only go when everything got through.
                if (mode == RomUnzip.Mode.DELETE_AFTER && (r.stopped || r.problem != null))
                    add("No .zip file was deleted. Run it again to finish: what is already unpacked is skipped.")
                r.problem?.let { add(it) }
                r.failed.take(4).forEach { (zip, why) -> add("${zip.name}: $why") }
                if (r.failed.size > 4) add("...and ${r.failed.size - 4} more.")
            }
            host.push(DialogScene(host, if (r.failed.isEmpty() && r.problem == null && !r.stopped) "Unzipped" else "Unzipped some",
                lines.joinToString("\n"), "OK", null))
            done()
        }
    }
}
