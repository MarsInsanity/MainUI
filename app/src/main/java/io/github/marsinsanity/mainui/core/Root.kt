package io.github.marsinsanity.mainui.core

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Minimal `su` helper. Every privileged feature degrades gracefully when root
 * is missing, so callers always get a result object instead of an exception.
 */
object Root {
    private const val TAG = "MainUI.Root"

    data class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
        val lines get() = out.lines().filter { it.isNotBlank() }
    }

    @Volatile
    private var cached: Boolean? = null

    /**
     * Debug builds only: when the system property `debug.mainui.rootport` is set,
     * root commands go to a root shell server on 127.0.0.1:<port> (started over
     * `adb root` with toybox nc). Used to test root features on emulator images
     * whose own su refuses apps. Release builds always use the real `su` (Magisk).
     */
    private val testRootPort: Int? by lazy {
        if (!io.github.marsinsanity.mainui.BuildConfig.DEBUG) null
        else runCatching {
            Runtime.getRuntime().exec(arrayOf("getprop", "debug.mainui.rootport")).inputStream
                .bufferedReader().readText().trim().toIntOrNull()
        }.getOrNull()
    }

    /** argv that runs [cmd] as root. */
    fun rootArgv(cmd: String): Array<String> {
        val port = testRootPort ?: return arrayOf("su", "-c", cmd)
        return arrayOf("sh", "-c", "printf '%s\\nexit\\n' ${q(cmd)} | nc -q 60 127.0.0.1 $port")
    }

    /** argv for an interactive root shell that reads commands from stdin. */
    private fun shellArgv(): Array<String> {
        val port = testRootPort ?: return arrayOf("su")
        return arrayOf("sh", "-c", "nc 127.0.0.1 $port")
    }

    /**
     * One root shell kept open for latency critical commands (freezing and
     * thawing a game around the in-game menu): spawning `su` costs 50-300 ms
     * on a handheld, writing a line to an open shell costs almost nothing.
     * Falls back to [exec] when the shell can't be started.
     */
    object Shell {
        private var proc: Process? = null
        private var stdin: java.io.Writer? = null
        private val lines = java.util.concurrent.LinkedBlockingQueue<String>()
        private var seq = 0

        @Synchronized
        fun run(cmd: String, timeoutMs: Long = 5000): Result {
            if (!knownAvailable() && !available()) return Result(-1, "", "no root")
            val alive = proc?.isAlive == true
            if (!alive && start() == null) return exec(cmd, timeoutMs / 1000 + 1)
            val marker = "__mainui_end_${++seq}__"
            try {
                stdin!!.write("$cmd\necho \"$marker \$?\"\n")
                stdin!!.flush()
            } catch (e: java.io.IOException) {
                close()
                return exec(cmd, timeoutMs / 1000 + 1)
            }
            val out = StringBuilder()
            val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
            while (true) {
                val left = deadline - android.os.SystemClock.uptimeMillis()
                val l = if (left > 0) lines.poll(left, TimeUnit.MILLISECONDS) else null
                if (l == null) { close(); return Result(-2, out.toString(), "timeout") }
                if (l.startsWith(marker)) return Result(l.substringAfter(' ').trim().toIntOrNull() ?: 0, out.toString(), "")
                out.appendLine(l)
            }
        }

        private fun start(): Process? = runCatching {
            lines.clear()
            val p = ProcessBuilder(*shellArgv()).redirectErrorStream(true).start()
            proc = p
            stdin = p.outputStream.bufferedWriter()
            Thread({ runCatching { p.inputStream.bufferedReader().forEachLine { lines.put(it) } } }, "root-shell")
                .apply { isDaemon = true }.start()
            p
        }.onFailure { Log.w(TAG, "root shell failed", it) }.getOrNull()

        @Synchronized
        fun close() {
            runCatching { proc?.destroy() }
            proc = null
            stdin = null
        }
    }

    /** True when `su` exists and granted us root. Cached after first success. */
    fun available(): Boolean {
        cached?.let { return it }
        val hasBinary = testRootPort != null || listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
            .any { File(it).exists() } || runCatching {
            Runtime.getRuntime().exec(arrayOf("sh", "-c", "command -v su")).inputStream
                .bufferedReader().readText().isNotBlank()
        }.getOrDefault(false)
        if (!hasBinary) {
            cached = false
            return false
        }
        val r = exec("id", timeoutSec = 15)
        val ok = r.ok && r.out.contains("uid=0")
        // Only cache positive results; the user may still be looking at the
        // Magisk prompt the first time.
        if (ok) cached = true
        return ok
    }

    /** Last known state without spawning a process. */
    fun knownAvailable(): Boolean = cached == true


    fun exec(cmd: String, timeoutSec: Long = 20): Result = run(rootArgv(cmd), timeoutSec)

    /** Runs a shell command as the app user (no root). */
    fun sh(cmd: String, timeoutSec: Long = 20): Result = run(arrayOf("sh", "-c", cmd), timeoutSec)

    private fun run(argv: Array<String>, timeoutSec: Long): Result {
        return try {
            val p = ProcessBuilder(*argv).start()
            val out = StringBuilder()
            val err = StringBuilder()
            val tOut = Thread { p.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }
            val tErr = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }
            tOut.start(); tErr.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                return Result(-2, out.toString(), "timeout")
            }
            tOut.join(1000); tErr.join(1000)
            Result(p.exitValue(), out.toString(), err.toString())
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${argv.joinToString(" ")}", e)
            Result(-1, "", e.message ?: "error")
        }
    }

    /** Quote a string for use inside a single shell command. */
    fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    // ---- common privileged helpers ------------------------------------------------

    fun forceStop(pkg: String): Boolean = exec("am force-stop ${q(pkg)}").ok

    fun kill(pid: Int): Boolean = exec("kill -9 $pid").ok

    fun keyEvent(vararg keyCodes: String): Boolean {
        if (keyCodes.isEmpty()) return false
        return if (keyCodes.size == 1) exec("input keyevent ${keyCodes[0]}").ok
        else exec("input keycombination ${keyCodes.joinToString(" ")}").ok ||
            exec("input keyevent ${keyCodes.joinToString(" ")}").ok
    }

    fun screencap(dest: File): Boolean {
        dest.parentFile?.mkdirs()
        val tmp = "/data/local/tmp/mainui_cap.png"
        val r = exec("screencap -p $tmp && cat $tmp > ${q(dest.absolutePath)} && rm $tmp")
        return r.ok && dest.length() > 0
    }

    fun listDir(path: String): List<String> = exec("ls -1 ${q(path)} 2>/dev/null").lines

    fun readFile(path: String): String? = exec("cat ${q(path)}").takeIf { it.ok }?.out

    /**
     * Writes [content] to [path] as root. The data goes through a temp file in
     * MainUI's cache (root can read it) rather than the command line, which
     * Linux limits to 128 KB per argument (RetroArch's config is bigger).
     * `cat >` keeps the target's owner and permissions.
     */
    fun writeFile(path: String, content: String): Boolean {
        val tmp = File(io.github.marsinsanity.mainui.MainUI.app.cacheDir, "root-write-${System.nanoTime()}")
        return try {
            tmp.writeText(content)
            tmp.setReadable(true, false)
            exec("cat ${q(tmp.absolutePath)} > ${q(path)}").ok
        } finally {
            tmp.delete()
        }
    }

    /** Returns the package name of the app that currently owns the focused window. */
    fun foregroundPackage(): String? {
        val r = exec("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1")
        return Regex("""u0 ([\w.]+)/""").find(r.out)?.groupValues?.get(1)
    }
}
