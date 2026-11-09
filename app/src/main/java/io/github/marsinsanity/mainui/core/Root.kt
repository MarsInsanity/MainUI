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

    /** True when `su` exists and granted us root. Cached after first success. */
    fun available(): Boolean {
        cached?.let { return it }
        val hasBinary = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su")
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

    fun exec(cmd: String, timeoutSec: Long = 20): Result = run(arrayOf("su", "-c", cmd), timeoutSec)

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

    fun writeFile(path: String, content: String): Boolean {
        // base64 avoids any quoting issues with arbitrary file contents
        val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
        return exec("echo $b64 | base64 -d > ${q(path)}").ok
    }

    /** Returns the package name of the app that currently owns the focused window. */
    fun foregroundPackage(): String? {
        val r = exec("dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity' | head -n 1")
        return Regex("""u0 ([\w.]+)/""").find(r.out)?.groupValues?.get(1)
    }
}
