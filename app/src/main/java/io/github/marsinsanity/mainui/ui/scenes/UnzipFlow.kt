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
 * then offers to unzip and delete each zip (needs room for the biggest one) or unzip and keep
 * them (needs room for all), with what each needs and what is free on that storage.
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
            fun fits(keep: Boolean) = byStorage.all { (dir, list) -> free(dir) >= RomUnzip.spaceNeeded(list, keep) }
            val freeNow = byStorage.keys.minOfOrNull { free(it) } ?: 0L
            val needDelete = byStorage.values.maxOf { RomUnzip.spaceNeeded(it, false) }
            val needKeep = byStorage.values.maxOf { RomUnzip.spaceNeeded(it, true) }
            val canDelete = fits(false); val canKeep = fits(true)
            val zips = plan.archives.size
            val items = mutableListOf(
                UiItem("Unzip, then delete the .zip files", sub = if (canDelete) null else "needs ${RomUnzip.size(needDelete)}",
                    disabled = !canDelete, onSelect = { run(host, sys, plan, true, done) }),
                UiItem("Unzip, keep the .zip files", sub = if (canKeep) null else "needs ${RomUnzip.size(needKeep)}",
                    disabled = !canKeep, onSelect = { run(host, sys, plan, false, done) }),
                UiItem("${RomUnzip.size(plan.bytes)} unpacked, ${RomUnzip.size(freeNow)} free", disabled = true),
            )
            if (!canDelete) items += UiItem("Not enough free space: free some up first", disabled = true)
            host.push(PopMenuScene(host, "Unzip ${plural(plan.games, "game")} from $zips .zip ${if (zips == 1) "file" else "files"}", items))
        }
    }

    private fun run(host: SceneHost, sys: GameSystem, plan: RomUnzip.Plan, delete: Boolean, done: () -> Unit) {
        val stop = AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val busy = BusyScene(host, "Unzipping...") { stop.set(true) }
        host.push(busy)
        host.io({
            val r = RomUnzip.unpack(plan, delete, { i, n, name ->
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
                if (delete) add("${plural(r.zipsDeleted, ".zip file")} deleted.")
                if (r.stopped) add("Stopped: the rest are still zipped.")
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
