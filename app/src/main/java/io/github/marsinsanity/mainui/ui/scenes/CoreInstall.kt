package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.ui.BusyScene
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.SceneHost
import io.github.marsinsanity.mainui.store.formatBytes

/** Helps the user get RetroArch cores: download with root, or point to RetroArch's own updater. */
object CoreInstall {

    /** Asks to download [core]; runs [then] after a successful install. */
    fun offer(host: SceneHost, core: String, then: (() -> Unit)? = null) {
        val em = MainUI.emulators
        val name = em.coreDisplayName(core)
        if (em.raPackage == null) {
            host.push(DialogScene(host, "RetroArch not installed",
                "$name runs in RetroArch, which is not installed.\nGet the 64-bit version from retroarch.com, or RetroArch from the App Store (F-Droid).",
                "APP STORE", "CANCEL") { host.push(AppStoreScene(host)) })
            return
        }
        host.io({ Root.available() }) { root ->
            if (!root) {
                host.push(DialogScene(host, "Core not installed",
                    "$name is not installed in RetroArch.\nMainUI needs root to add it. Without root: RetroArch > Online Updater > Core Downloader > $name.",
                    "OPEN RETROARCH", "CANCEL") { openRetroArch(host) })
                return@io
            }
            val local = em.localCores()[core]
            when {
                local != null -> host.push(DialogScene(host, "Install core",
                    "$name is not installed in RetroArch.\nInstall it from ${local.name} in MainUI/cores?",
                    "INSTALL", "CANCEL") { install(host, listOf(core), then) })
                core in NOT_ON_BUILDBOT -> host.push(DialogScene(host, "Core not installed",
                    "$name is not on the libretro buildbot. Put its Android build ($core${io.github.marsinsanity.mainui.emu.RetroArchCores.SUFFIX}, or a .zip of it) in MainUI/cores, then try again.",
                    "OK", null))
                else -> host.push(DialogScene(host, "Core not installed",
                    "$name is not installed in RetroArch.\nDownload it now from the libretro buildbot (the same source as RetroArch's Online Updater)?",
                    "DOWNLOAD", "CANCEL") { install(host, listOf(core), then) })
            }
        }
    }

    /** Cores MainUI knows about that the libretro buildbot doesn't build for Android. */
    val NOT_ON_BUILDBOT = setOf("yabadabadoo")

    /** Downloads [cores] one after another with a progress panel. */
    fun install(host: SceneHost, cores: List<String>, then: (() -> Unit)? = null) {
        if (cores.isEmpty()) return
        val busy = BusyScene(host, "Downloading...")
        host.push(busy)
        val em = MainUI.emulators
        host.io({
            cores.mapIndexed { i, c ->
                c to em.installCore(c) { msg ->
                    busy.text = if (cores.size > 1) "(${i + 1}/${cores.size}) $msg" else msg
                    host.invalidate()
                }
            }
        }) { results ->
            host.pop(busy)
            val failed = results.filter { it.second.isFailure }
            if (failed.isEmpty()) {
                host.toast(if (cores.size == 1) "${em.coreDisplayName(cores[0])} installed" else "${cores.size} cores installed", 2500)
                then?.invoke()
            } else {
                host.push(DialogScene(host, "Core download failed", failed.joinToString("\n") { (c, r) ->
                    "${em.coreDisplayName(c)}: ${r.exceptionOrNull()?.message ?: "failed"}"
                }.take(360), "OK", null))
            }
        }
    }

    /**
     * Downloads every core the libretro buildbot has that isn't installed yet:
     * shows how many and how big first, checks free space, then installs three
     * at a time. B stops after the cores being downloaded.
     */
    fun installAll(host: SceneHost, done: () -> Unit = {}) {
        val em = MainUI.emulators
        if (em.raPackage == null) { offer(host, "mgba"); return }
        val busy = BusyScene(host, "Checking the core list...")
        host.push(busy)
        host.io({ runCatching { Root.available() to em.missingRemoteCores() } }) { r ->
            host.pop(busy)
            r.onFailure { host.push(DialogScene(host, "Download all cores", "Could not get the core list:\n${it.message}", "OK", null)) }
            r.onSuccess { (root, missing) ->
                when {
                    !root -> host.push(DialogScene(host, "Download all cores",
                        "MainUI needs root to add cores to RetroArch. Without root: RetroArch > Online Updater > Core Downloader.", "OK", null))
                    missing.isEmpty() -> host.toast("All cores are already installed", 2500)
                    else -> confirmAll(host, missing, done)
                }
            }
        }
    }

    private fun confirmAll(host: SceneHost, missing: List<io.github.marsinsanity.mainui.emu.RetroArchCores.Remote>, done: () -> Unit) {
        val bytes = missing.sumOf { it.bytes }
        // Cores unpack to roughly 2.5 times their download size.
        val needed = (bytes * 2.5).toLong() + 200L * 1024 * 1024
        val free = runCatching { android.os.StatFs(android.os.Environment.getDataDirectory().path).availableBytes }.getOrDefault(Long.MAX_VALUE)
        val size = if (bytes > 0) " (${formatBytes(bytes)} to download, about ${formatBytes((bytes * 2.5).toLong())} installed)" else ""
        val msg = "Download ${missing.size} cores$size?" +
            if (bytes > 0 && free < needed) "\n\nOnly ${formatBytes(free)} free: it may not all fit." else ""
        host.push(DialogScene(host, "Download all cores", msg, "DOWNLOAD", "CANCEL") { runAll(host, missing, done) })
    }

    private fun runAll(host: SceneHost, missing: List<io.github.marsinsanity.mainui.emu.RetroArchCores.Remote>, done: () -> Unit) {
        val em = MainUI.emulators
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val busy = BusyScene(host, "Starting...") { stop.set(true) }
        host.push(busy)
        val queue = java.util.concurrent.ConcurrentLinkedQueue(missing.sortedBy { it.bytes })   // small ones first
        val ok = java.util.concurrent.atomic.AtomicInteger()
        val failed = java.util.Collections.synchronizedList(ArrayList<Pair<String, String>>())
        val current = java.util.concurrent.ConcurrentHashMap<String, String>()
        val total = missing.size
        fun show() {
            val head = "Cores: ${ok.get() + failed.size} of $total" + if (failed.isNotEmpty()) " (${failed.size} failed)" else ""
            busy.text = (listOf(head) + current.values.take(3) + listOfNotNull(if (stop.get()) "Stopping after these..." else null))
                .joinToString("\n")
            host.invalidate()
        }
        host.io({
            val workers = (1..3).map {
                Thread {
                    while (!stop.get()) {
                        val c = queue.poll() ?: break
                        current[c.core] = c.core
                        show()
                        em.installCore(c.core, rescan = false) { msg -> current[c.core] = msg; show() }
                            .onSuccess { ok.incrementAndGet() }
                            .onFailure { failed += c.core to (it.message ?: "failed") }
                        current.remove(c.core)
                        show()
                    }
                }.apply { start() }
            }
            workers.forEach { it.join() }
            em.refreshCores()
        }) {
            host.pop(busy)
            val skipped = total - ok.get() - failed.size
            val summary = buildString {
                append("${ok.get()} cores installed.")
                if (skipped > 0) append(" $skipped not downloaded (stopped).")
                if (failed.isNotEmpty()) append("\n" + failureSummary(failed))
            }
            host.push(DialogScene(host, "Download all cores", summary, "OK", null))
            done()
        }
    }

    /** "3 failed (a, b, c):\nreason" when they share a reason, otherwise one line per core. */
    internal fun failureSummary(failed: List<Pair<String, String>>): String {
        val reasons = failed.map { (core, msg) -> msg.replace(core, "\u0000") }.distinct()
        if (reasons.size == 1) {
            val names = failed.take(4).joinToString(", ") { it.first } + if (failed.size > 4) ", ..." else ""
            return "${failed.size} failed ($names):\n${failed[0].second}".take(300)
        }
        return "${failed.size} failed:\n" + failed.take(4).joinToString("\n") { "${it.first}: ${it.second}" }.take(260)
    }

    private fun openRetroArch(host: SceneHost) {
        val pkg = MainUI.emulators.raPackage ?: return
        host.hostContext.packageManager.getLaunchIntentForPackage(pkg)?.let {
            host.hostContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
