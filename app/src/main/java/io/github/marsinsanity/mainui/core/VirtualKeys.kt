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

    // ---- the injector: input as a real controller, no device of its own -----------------------
    // A second root helper (KeyInject "inject"). Since Android 12 every event injected through
    // Android arrives from device -1 ("Virtual") whatever device it names, and RetroArch took that
    // for a new controller and gave it a port (port 1 when it came first). The injector instead
    // writes into a real controller's own /dev/input node, so apps hear it from that controller.

    private var injProc: Process? = null
    private var injOut: BufferedWriter? = null
    private val injReplies = LinkedBlockingQueue<String>()

    private fun ensureInjector(): Boolean {
        if (injProc?.isAlive == true && injOut != null) return true
        if (!Root.available()) return false
        val ai = MainUI.app.applicationInfo
        val lib = File(ai.nativeLibraryDir, "libmainuiinput.so")
        if (!lib.isFile) { Log.w(TAG, "missing $lib"); return false }
        val p = Root.startInteractive("env CLASSPATH=${Root.q(ai.sourceDir)} app_process /system/bin " +
            "io.github.marsinsanity.mainui.tools.KeyInject inject ${Root.q(lib.path)}") ?: return false
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
     * Makes controller [deviceId] tell the app in front where its sticks and triggers are now (a
     * trigger let go while MainUI's menu had focus is otherwise still held in the game: R2 is
     * RetroArch's fast-forward, which mutes the sound). One stick axis moves a hair and back
     * through the controller's own /dev/input node. "ok ...", "none", "error ..."; null without root.
     */
    fun nudge(deviceId: Int): String? {
        val d = android.view.InputDevice.getDevice(deviceId) ?: return "error no device $deviceId"
        if (d.isVirtual) return "error virtual device"
        return inject("nudge ${d.vendorId} ${d.productId} ${d.name}", 3000)
    }

    /** Bumped for every new virtual keyboard (helper start). */
    @Volatile private var deviceGen = 0
    /** "<pkg>:<pid>:<deviceGen>" that has seen a key from the current keyboard. */
    @Volatile private var primed: String? = null

    /**
     * RetroArch takes note of a keyboard on its first key and doesn't act on that key, so the
     * first Save or Load did nothing: tap Ctrl, which RetroArch doesn't use, and give it a moment.
     * Done when a RetroArch game starts, so MainUI's keyboard is among the first three keyboards
     * RetroArch hears from (it counts no more; a later one becomes a controller with a port),
     * and with [always] again (the game's start, a second time). Otherwise once per RetroArch
     * process and keyboard: RetroArch also forgets its keyboards when its input restarts inside
     * the same process (content loaded from its own menu), which can't be seen from outside, so
     * the first key after that can be missed (Save checks and presses again; Load / Menu don't).
     */
    fun primeFor(pkg: String?, always: Boolean = false): Boolean {
        if (pkg == null) return false
        val pid = Root.Shell.run("pidof $pkg", 3000).out.trim()
        if (pid.isEmpty()) return false
        if (!ensureLocked()) return false
        val key = "$pkg:$pid:$deviceGen"
        if (!always && primed == key) return true
        if (!press("CTRL", 80)) return false
        primed = key
        Thread.sleep(350)
        return true
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
