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
    fun axes(deviceId: Int, values: FloatArray): Boolean =
        inject("axes $deviceId ${values.joinToString(" ")}", 3000) == "ok"

    // ---- the injector: input as the handheld's own controller, no virtual device ----------------
    // A second root helper (KeyInject "inject"). It never creates an input device: apps see its
    // presses as the built-in controller's. RetroArch counts at most three keyboards per run (the
    // volume / power keys and every virtual keyboard MainUI made included); past that it took
    // MainUI's keyboard for a controller, gave it a port (port 1 when it came first) and read its
    // hotkeys as buttons, so saves failed. RetroArch's keys go through here instead.

    private var injProc: Process? = null
    private var injOut: BufferedWriter? = null
    private val injReplies = LinkedBlockingQueue<String>()

    private fun ensureInjector(): Boolean {
        if (injProc?.isAlive == true && injOut != null) return true
        if (!Root.available()) return false
        val ai = MainUI.app.applicationInfo
        val p = Root.startInteractive("env CLASSPATH=${Root.q(ai.sourceDir)} app_process /system/bin " +
            "io.github.marsinsanity.mainui.tools.KeyInject inject") ?: return false
        injReplies.clear()
        Thread({ runCatching { p.inputStream.bufferedReader().forEachLine { injReplies.put(it.trim()) } } }, "inject-reader")
            .apply { isDaemon = true }.start()
        if (injReplies.poll(15, TimeUnit.SECONDS) == "ready") {
            injProc = p; injOut = p.outputStream.bufferedWriter()
            return true
        }
        p.destroy()
        return false
    }

    /** One command to the injector; its reply, or null when there is no injector (no root). */
    @Synchronized
    private fun inject(line: String, waitMs: Long): String? {
        repeat(2) {
            if (!ensureInjector()) return null
            try {
                injReplies.clear()
                injOut!!.write(line + "\n"); injOut!!.flush()
                injReplies.poll(waitMs, TimeUnit.MILLISECONDS)?.let { return it }
            } catch (e: Exception) {
                Log.w(TAG, "inject $line failed", e)
            }
            injProc?.destroy(); injProc = null; injOut = null
        }
        return null
    }

    /**
     * Presses Android key codes [codes] together on the built-in controller (the first ones held
     * first: a hotkey enable button). "ok <device id>", "error no controller", or null (no root).
     */
    fun pad(codes: List<Int>, holdMs: Int = 200): String? =
        inject("pad ${codes.joinToString("+")} $holdMs", holdMs + 3000L)

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

    /** Starts the injector in the background (RetroArch's keys go through it), making no input device. */
    fun warmUpInjector() { Thread({ synchronized(this) { ensureInjector() } }, "inject-warmup").apply { isDaemon = true }.start() }

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
