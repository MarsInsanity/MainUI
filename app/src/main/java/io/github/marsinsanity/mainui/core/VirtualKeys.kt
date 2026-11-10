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

    /**
     * Tells the app in front a controller's current trigger / stick positions ([values] in the
     * helper's CONTROLLER_AXES order: X, Y, Z, RZ, RX, RY, HAT_X, HAT_Y, LTRIGGER, RTRIGGER,
     * BRAKE, GAS). Used after MainUI's menu had focus while they moved. Root only.
     */
    fun axes(deviceId: Int, values: FloatArray): Boolean {
        val args = "$deviceId ${values.joinToString(" ")}"
        // Through the running helper when there is one; otherwise a one-off root call, so no
        // virtual keyboard appears just for this (a new keyboard while a game starts up could be
        // taken for a controller).
        synchronized(this) {
            if (proc?.isAlive == true && out != null) return try {
                replies.clear()
                out!!.write("axes $args\n"); out!!.flush()
                replies.poll(3000, TimeUnit.MILLISECONDS) == "ok"
            } catch (e: Exception) { Log.w(TAG, "axes failed", e); false }
        }
        if (!Root.knownAvailable()) return false
        val ai = MainUI.app.applicationInfo
        val r = Root.exec("env CLASSPATH=${Root.q(ai.sourceDir)} app_process /system/bin " +
            "io.github.marsinsanity.mainui.tools.KeyInject axes $args", timeoutSec = 15)
        return r.ok && r.out.contains("ok")
    }

    /** Bumped for every new virtual keyboard (helper start). */
    @Volatile private var deviceGen = 0
    /** "<pkg>:<pid>:<deviceGen>" that has seen a key from the current keyboard. */
    @Volatile private var primed: String? = null

    /**
     * RetroArch takes note of a keyboard on its first key and doesn't act on that key, so the
     * first Save or Load did nothing. Before the first hotkey for a RetroArch process (and after
     * every new virtual keyboard), tap Ctrl, which RetroArch doesn't use, and give it a moment.
     */
    fun primeFor(pkg: String?) {
        if (pkg == null) return
        val pid = Root.Shell.run("pidof $pkg", 3000).out.trim()
        if (pid.isEmpty()) return
        if (!ensureLocked()) return
        val key = "$pkg:$pid:$deviceGen"
        if (primed == key) return
        if (press("CTRL", 80)) { primed = key; Thread.sleep(350) }
    }

    @Synchronized private fun ensureLocked() = ensure()

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
                deviceGen++
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
