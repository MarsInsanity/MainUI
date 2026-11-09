package io.github.marsinsanity.mainui.core

import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MainUI's virtual keyboard ("MainUI Keyboard"): a uinput device owned by a small root helper
 * ([io.github.marsinsanity.mainui.tools.KeyInject] in daemon mode, run with app_process).
 * Keys from it reach apps the way a real keyboard's do, unlike `input keyevent`, which
 * RetroArch ignores. The helper is started on first use and kept while MainUI runs.
 */
object VirtualKeys {
    private const val TAG = "MainUI.Keys"
    private var proc: Process? = null
    private var out: BufferedWriter? = null
    private val replies = LinkedBlockingQueue<String>()

    /** Presses [keys] ("F1", or "F1+F2") for [holdMs]. False without root or when the helper fails. */
    @Synchronized
    fun press(keys: String, holdMs: Int = 250): Boolean {
        repeat(2) {
            if (!ensure()) return false
            try {
                replies.clear()
                out!!.write("$keys $holdMs\n"); out!!.flush()
                val r = replies.poll(holdMs + 4000L, TimeUnit.MILLISECONDS)
                if (r == "ok") return true
                Log.w(TAG, "press $keys: $r")
                if (r != null && r.startsWith("error unknown")) return false
            } catch (e: Exception) {
                Log.w(TAG, "press $keys failed", e)
            }
            stop()
        }
        return false
    }

    /** Starts the helper in the background so the first key press doesn't wait for it. */
    fun warmUp() { Thread({ synchronized(this) { ensure() } }, "keys-warmup").apply { isDaemon = true }.start() }

    @Synchronized
    fun stop() {
        runCatching { out?.close() }
        proc?.destroy()
        proc = null; out = null
    }

    private fun ensure(): Boolean {
        if (proc?.isAlive == true && out != null) return true
        if (!Root.available()) return false
        val ai = MainUI.app.applicationInfo
        val lib = File(ai.nativeLibraryDir, "libmainuiinput.so")
        if (!lib.isFile) { Log.w(TAG, "missing $lib"); return false }
        val p = Root.startInteractive("env CLASSPATH=${Root.q(ai.sourceDir)} app_process /system/bin " +
            "io.github.marsinsanity.mainui.tools.KeyInject daemon ${Root.q(lib.path)}") ?: return false
        replies.clear()
        Thread({
            runCatching { p.inputStream.bufferedReader().forEachLine { replies.put(it.trim()) } }
        }, "keys-reader").apply { isDaemon = true }.start()
        // app_process takes a moment to start; then give Android time to add the new device.
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val l = replies.poll(500, TimeUnit.MILLISECONDS) ?: if (p.isAlive) continue else break
            if (l == "ready") {
                proc = p; out = p.outputStream.bufferedWriter()
                Thread.sleep(400)
                Log.i(TAG, "virtual keyboard ready")
                return true
            }
            if (l.startsWith("error")) { Log.w(TAG, l); break }
        }
        p.destroy()
        return false
    }
}
