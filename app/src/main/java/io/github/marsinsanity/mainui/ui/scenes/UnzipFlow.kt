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
 * "Unzip all" for a system whose emulators can't open .zip / .rar files (Game & Watch's .mgw, ...):
 * offered on its "No games found" screen and in its SELECT menu. Looks inside the archives first,
 * then offers: unzip all, then delete all the zips (room for everything; nothing deleted unless
 * it all got through), unzip one at a time deleting each zip (room for the biggest one only,
 * for a card that can't hold the games twice), or unzip and keep the zips (room for everything),
 * each with what it needs and what is free on that storage.
 */
object UnzipFlow {
    /** Archive types (.zip, .rar) [sys] can't play as they are. */
    private fun types(sys: GameSystem): Set<String> =
        if (sys.def.extensions.isEmpty()) emptySet() else RomUnzip.TYPES - sys.def.extensions

    /** Archives counted in [sys]'s folders that it can't play as they are; 0 = not offered. */
    fun archives(sys: GameSystem): Int = types(sys).sumOf { sys.otherFiles[it] ?: 0 }

    /** "Unzip all 14 .zip files" / ".rar files" / "archives", by what [sys]'s folders hold. */
    fun label(sys: GameSystem): String {
        val n = archives(sys)
        val found = types(sys).filter { (sys.otherFiles[it] ?: 0) > 0 }
        return "Unzip all $n " + if (found.size == 1) ".${found[0]} ${if (n == 1) "file" else "files"}"
            else if (n == 1) "archive" else "archives"
    }

    fun start(host: SceneHost, sys: GameSystem, done: () -> Unit) {
        val busy = BusyScene(host, "Looking inside the archives...")
        host.push(busy)
        val problems = java.util.Collections.synchronizedList(ArrayList<Pair<File, String>>())
        host.io({
            runCatching {
                RomUnzip.scan(sys.dirs, { n -> sys.def.accepts(File(n)) }, { d ->
                    d in GameRepository.MEDIA_FOLDERS || d in SystemRegistry.IGNORED_FOLDERS
                }, types(sys), problems)
            }
        }) { r ->
            host.pop(busy)
            val plan = r.getOrElse { host.push(DialogScene(host, "Can't unzip", it.message ?: it.toString(), "OK", null)); return@io }
            val exts = sys.def.extensions.sorted().joinToString(" ") { ".$it" }
            // Archives that can't be opened (password protected, damaged): said, not tried.
            val unreadable = problems.take(3).joinToString("\n") { (f, why) -> "${f.name}: $why" } +
                if (problems.size > 3) "\n...and ${problems.size - 3} more" else ""
            if (plan.archives.isEmpty()) {
                host.push(DialogScene(host, "Nothing to unzip",
                    "None of the archives in ${sys.name} holds $exts games." + if (problems.isEmpty()) "" else "\n\n$unreadable", "OK", null))
                return@io
            }
            // Each storage (SD card, internal) needs room for what is unpacked there.
            // (By volume: two system folders, or Roms and Roms2, on one card share its free space.)
            val vols = MainUI.storage.volumes()
            val byStorage = plan.archives.groupBy { a -> vols.firstOrNull { a.file.path.startsWith(it.dir.path + "/") }?.dir ?: a.file.parentFile }
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
            val onlyZips = plan.archives.all { !it.isRar }
            val items = mutableListOf(
                option("Unzip all, then delete them", RomUnzip.Mode.DELETE_AFTER),
                option("One at a time, delete each", RomUnzip.Mode.DELETE_EACH),
                option(if (onlyZips) "Unzip, keep the zips" else "Unzip, keep the archives", RomUnzip.Mode.KEEP),
                UiItem("${RomUnzip.size(plan.bytes)} unpacked, ${RomUnzip.size(freeNow)} free", disabled = true),
            )
            when {
                !fits(RomUnzip.Mode.DELETE_EACH) -> items += UiItem("Not enough free space: free some up first", disabled = true)
                !fits(RomUnzip.Mode.DELETE_AFTER) -> items += UiItem("Only room for one at a time", disabled = true)
            }
            if (problems.isNotEmpty()) items += UiItem("${plural(problems.size, "archive")} can't be opened", disabled = true)
            val kinds = plan.archives.map { it.file.extension.lowercase() }.distinct()
            val what = if (kinds.size == 1) ".${kinds[0]} ${if (zips == 1) "file" else "files"}" else if (zips == 1) "archive" else "archives"
            host.push(PopMenuScene(host, "Unzip ${plural(plan.games, "game")} from $zips $what", items))
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
                // A folder MainUI can't write to (some SD cards): Android's unzip as root, never overwriting (.zip only).
                // Time for a big zip on a slow card (at least 1 MB/s), not Root.exec's 20 s.
                // Killed by toybox's timeout itself when too slow: stopping only the su client would
                // leave unzip writing as root in the background while the next archive starts.
                val limit = 600 + zip.length() / (1L shl 20)
                Root.knownAvailable() && Root.exec("timeout -s KILL $limit unzip -n -q -d ${Root.q(dir.path)} ${Root.q(zip.path)}",
                    timeoutSec = limit + 15).ok
            })
            MainUI.games.invalidate()
            runCatching { MainUI.games.countGames(sys) }
            r
        }) { r ->
            host.pop(busy)
            val lines = buildList {
                add("Unpacked ${plural(r.unpacked, "file")} from ${r.done} of ${plural(plan.archives.size, "archive")}.")
                if (r.existing > 0) add("${plural(r.existing, "file")} already there, left as they were.")
                if (mode != RomUnzip.Mode.KEEP) add("${plural(r.deleted, "archive")} deleted.")
                if (r.stopped) add("Stopped: the rest are still zipped.")
                // All at once: the zips only go when everything got through.
                if (mode == RomUnzip.Mode.DELETE_AFTER && (r.stopped || r.problem != null))
                    add("No archive was deleted. Run it again to finish: what is already unpacked is skipped.")
                r.problem?.let { add(it) }
                r.failed.take(4).forEach { (f, why) -> add("${f.name}: $why") }
                if (r.failed.size > 4) add("...and ${r.failed.size - 4} more.")
            }
            host.push(DialogScene(host, if (r.failed.isEmpty() && r.problem == null && !r.stopped) "Unzipped" else "Unzipped some",
                lines.joinToString("\n"), "OK", null))
            done()
        }
    }
}
