package io.github.marsinsanity.mainui.ui.scenes

import android.content.Intent
import io.github.marsinsanity.mainui.MainUI
import io.github.marsinsanity.mainui.core.Root
import io.github.marsinsanity.mainui.ui.BusyScene
import io.github.marsinsanity.mainui.ui.DialogScene
import io.github.marsinsanity.mainui.ui.SceneHost

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

    private fun openRetroArch(host: SceneHost) {
        val pkg = MainUI.emulators.raPackage ?: return
        host.hostContext.packageManager.getLaunchIntentForPackage(pkg)?.let {
            host.hostContext.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
