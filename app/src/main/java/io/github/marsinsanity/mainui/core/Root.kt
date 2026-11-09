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

    /**
     * How root commands reach the system-wide view of the file system.
     *
     * Android 11+ gives every app its own view of /data/data (and of other apps'
     * Android/data folders) that only contains the app itself. Magisk's `su`
     * runs in the view of the app that called it by default, so root commands
     * from MainUI couldn't see RetroArch's folder ("stat '/data/data/com.retroarch':
     * No such file or directory"). MainUI therefore runs root commands in init's
     * mount namespace: with `nsenter -t 1 -m` (any root solution), or with su's
     * own --mount-master option when nsenter isn't available.
     */
    private enum class Ns { UNKNOWN, NSENTER, MOUNT_MASTER, PLAIN }

    @Volatile private var ns = Ns.UNKNOWN

    private fun wrap(cmd: String): String = when (ns) {
        Ns.NSENTER -> "nsenter -t 1 -m -- sh -c ${q(cmd)}"
        else -> cmd
    }

    /** argv that runs [cmd] as root, in the system-wide view once [detectNamespace] ran. */
    fun rootArgv(cmd: String): Array<String> {
        val port = testRootPort
        if (port != null) return arrayOf("sh", "-c", "printf '%s\\nexit\\n' ${q(wrap(cmd))} | nc -q 60 127.0.0.1 $port")
        return if (ns == Ns.MOUNT_MASTER) arrayOf("su", "--mount-master", "-c", cmd) else arrayOf("su", "-c", wrap(cmd))
    }

    /** argv for an interactive root shell that reads commands from stdin (see [Shell]). */
    private fun shellArgv(): Array<String> {
        val port = testRootPort
        if (port != null) return arrayOf("sh", "-c", "nc 127.0.0.1 $port")
        return if (ns == Ns.MOUNT_MASTER) arrayOf("su", "--mount-master") else arrayOf("su")
    }

    /**
     * Starts a long-running root command with stdin / stdout piped to it: a root shell
     * (in the system-wide view) that execs into [cmd]. Null without root.
     */
    fun startInteractive(cmd: String): Process? = runCatching {
        ready()
        val p = ProcessBuilder(*shellArgv()).redirectErrorStream(true).start()
        val w = p.outputStream.bufferedWriter()
        shellPrelude()?.let { w.write("$it\n") }
        w.write("exec $cmd\n")
        w.flush()
        p
    }.onFailure { Log.w(TAG, "startInteractive failed", it) }.getOrNull()

    /** First line sent to a new interactive root shell. */
    private fun shellPrelude(): String? = if (ns == Ns.NSENTER) "exec nsenter -t 1 -m -- sh" else null

    /** Picks how to reach the system-wide view; runs once, after root was granted. */
    @Synchronized
    private fun detectNamespace() {
        if (ns != Ns.UNKNOWN) return
        fun su(cmd: String) = if (testRootPort != null) rootArgv(cmd) else arrayOf("su", "-c", cmd)
        var found = Ns.PLAIN
        if (run(su("nsenter -t 1 -m -- sh -c 'echo mainui-ns-ok'"), 15).out.contains("mainui-ns-ok")) {
            found = Ns.NSENTER
        } else if (testRootPort == null &&
            run(arrayOf("su", "--mount-master", "-c", "echo mainui-ns-ok"), 15).out.contains("mainui-ns-ok")) {
            found = Ns.MOUNT_MASTER
        }
        ns = found
        Log.i(TAG, "root namespace: $ns")
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
            ready()
            lines.clear()
            val p = ProcessBuilder(*shellArgv()).redirectErrorStream(true).start()
            proc = p
            stdin = p.outputStream.bufferedWriter()
            shellPrelude()?.let { stdin!!.write("$it\n"); stdin!!.flush() }
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
        val r = run(rootArgv("id"), 15)
        val ok = r.ok && r.out.contains("uid=0")
        // Only cache positive results; the user may still be looking at the
        // Magisk prompt the first time.
        if (ok) {
            detectNamespace()
            cached = true
        }
        return ok
    }

    /** Last known state without spawning a process. */
    fun knownAvailable(): Boolean = cached == true


    /** Makes sure the namespace is known before a root command runs. */
    private fun ready() { if (ns == Ns.UNKNOWN) available() }

    fun exec(cmd: String, timeoutSec: Long = 20): Result { ready(); return run(rootArgv(cmd), timeoutSec) }

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

    /** `am start` arguments for [intent]: component, action, data, typed extras and flags. */
    fun amStartArgs(intent: android.content.Intent): String = buildString {
        append("am start --user 0")
        intent.component?.let { append(" -n ").append(q(it.flattenToString())) }
        intent.action?.let { append(" -a ").append(q(it)) }
        intent.dataString?.let { append(" -d ").append(q(it)) }
        intent.type?.let { append(" -t ").append(q(it)) }
        intent.extras?.let { b ->
            for (k in b.keySet()) {
                @Suppress("DEPRECATION") val v = b.get(k)
                when (v) {
                    is Boolean -> append(" --ez ").append(q(k)).append(' ').append(v)
                    is Int -> append(" --ei ").append(q(k)).append(' ').append(v)
                    is Long -> append(" --el ").append(q(k)).append(' ').append(v)
                    is Array<*> -> append(" --esa ").append(q(k)).append(' ')
                        .append(q(v.joinToString(",") { it.toString().replace(",", "\\,") }))
                    null -> append(" --esn ").append(q(k))
                    else -> append(" --es ").append(q(k)).append(' ').append(q(v.toString()))
                }
            }
        }
        if (intent.flags != 0) append(" -f ").append(intent.flags)
    }

    /**
     * Starts an activity as root, for emulators whose game activity isn't exported
     * to other apps. Returns null on success, otherwise what `am` said.
     */
    fun startActivity(intent: android.content.Intent): String? {
        val r = exec(amStartArgs(intent), 30)
        val out = (r.out + "\n" + r.err).lines().map { it.trim() }.filter { it.isNotEmpty() }
        val error = out.firstOrNull { it.startsWith("Error") || it.contains("Exception") }
        return when {
            error != null -> error
            !r.ok -> out.lastOrNull() ?: "am start failed"
            else -> null
        }
    }

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

    /**
     * The same file seen without Android's storage layers: /storage/emulated/N/x is
     * /data/media/N/x, and an SD card /storage/XXXX-XXXX/x is /mnt/media_rw/XXXX-XXXX/x.
     * Root can always reach these, whatever mount namespace `su` puts it in and
     * however the shared-storage (FUSE) layer treats other apps' Android/data folders.
     */
    fun lowerPaths(path: String): List<String> {
        val out = mutableListOf(path)
        Regex("^/(?:storage|sdcard)/emulated/(\\d+)/(.*)$").find(path)?.let { m ->
            out += "/data/media/${m.groupValues[1]}/${m.groupValues[2]}"
        }
        if (path.startsWith("/sdcard/")) out += "/data/media/0/" + path.removePrefix("/sdcard/")
        Regex("^/storage/([0-9A-Fa-f]{4}-[0-9A-Fa-f]{4})/(.*)$").find(path)?.let { m ->
            out += "/mnt/media_rw/${m.groupValues[1]}/${m.groupValues[2]}"
        }
        return out.distinct()
    }

    /** Runs [cmd] as root and returns its exit code and raw stdout bytes. */
    fun execBytes(cmd: String, timeoutSec: Long = 20): Pair<Int, ByteArray> {
        ready()
        return try {
            val p = ProcessBuilder(*rootArgv(cmd)).start()
            var bytes = ByteArray(0)
            val tOut = Thread { bytes = runCatching { p.inputStream.readBytes() }.getOrDefault(ByteArray(0)) }
            val tErr = Thread { runCatching { p.errorStream.readBytes() } } // drain so it can't block
            tOut.start(); tErr.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly()
                Pair(-2, ByteArray(0))
            } else {
                tOut.join(2000)
                Pair(p.exitValue(), bytes)
            }
        } catch (e: Exception) {
            Log.w(TAG, "execBytes failed", e)
            Pair(-1, ByteArray(0))
        }
    }

    /**
     * Reads a file as root, byte for byte, trying [lowerPaths] if the normal path
     * can't be read. It is read in 32 KB pieces and the total is checked against
     * the file's size, so a cut-off read can never be mistaken for the whole file
     * (and written back, losing the rest).
     */
    fun readBytes(path: String): ByteArray? {
        for (p in lowerPaths(path)) {
            val size = exec("wc -c < ${q(p)}").takeIf { it.ok }?.out?.trim()?.toLongOrNull() ?: continue
            repeat(2) {
                val buf = java.io.ByteArrayOutputStream()
                var i = 0L
                while (buf.size() < size) {
                    val (code, chunk) = execBytes("dd if=${q(p)} bs=32768 skip=$i count=1 2>/dev/null")
                    if (code != 0 || chunk.isEmpty()) break
                    buf.write(chunk); i++
                }
                if (buf.size().toLong() == size) return buf.toByteArray()
                Log.w(TAG, "read $p: got ${buf.size()} of $size bytes")
            }
        }
        return null
    }

    /** [readBytes] as UTF-8 text. */
    fun readFile(path: String): String? = readBytes(path)?.toString(Charsets.UTF_8)

    data class WriteResult(val ok: Boolean, val error: String = "", val writtenTo: String? = null)

    /**
     * Writes [content] to [path] as root and keeps the file's owner and permissions
     * (`cat >` over the existing file), so the owning app can still change it.
     *
     * MainUI writes the new content to its own folder on shared storage; root then
     * copies it over the target in one short command. Both ends are tried at every
     * equivalent path ([lowerPaths]): depending on how `su` is set up (mount
     * namespace, storage isolation, SELinux), the normal /storage path of another
     * app's Android/data folder may not be writable or MainUI's files may not be
     * visible, but the underlying /data/media path always is.
     */
    fun writeFile(path: String, content: String, backup: Boolean = false): WriteResult {
        val app = io.github.marsinsanity.mainui.MainUI.app
        val dir = (app.externalCacheDir ?: app.cacheDir).apply { mkdirs() }
        val tmp = File(dir, "root-write-${System.nanoTime()}")
        try {
            tmp.writeText(content)
            tmp.setReadable(true, false)
            val size = tmp.length()
            val sources = lowerPaths(tmp.absolutePath) +
                listOfNotNull(tmp.absolutePath.takeIf { it.startsWith("/data/user/0/") }?.replaceFirst("/data/user/0/", "/data/data/"))
            val script = buildString {
                append("src=''; for s in ${sources.joinToString(" ") { q(it) }}; do if [ -r \"\$s\" ]; then src=\"\$s\"; break; fi; done\n")
                append("if [ -z \"\$src\" ]; then echo 'root cannot read MainUI temporary file' >&2; exit 3; fi\n")
                append("err=''\n")
                append("for t in ${lowerPaths(path).joinToString(" ") { q(it) }}; do\n")
                // First change only: keep the original next to it (same owner and mode).
                if (backup) append("  [ -f \"\$t\" ] && [ ! -e \"\$t.mainui.bak\" ] && cp -p \"\$t\" \"\$t.mainui.bak\" 2>/dev/null\n")
                append("  if out=\$(cat \"\$src\" 2>&1 > \"\$t\") && [ \"\$(wc -c < \"\$t\" | tr -d ' ')\" = '$size' ]; then echo \"OK \$t\"; exit 0; fi\n")
                append("  err=\"\$out\"\n")
                append("done\n")
                append("echo \"\${err:-write failed}\" >&2; exit 4\n")
            }
            val r = exec(script, timeoutSec = 30)
            val ok = r.ok && r.out.contains("OK ")
            if (ok) {
                val to = r.out.lines().firstOrNull { it.startsWith("OK ") }?.removePrefix("OK ")?.trim()
                if (to != path) Log.i(TAG, "wrote $path via $to")
                return WriteResult(true, writtenTo = to)
            }
            val why = (r.err.ifBlank { r.out }).trim().lines().lastOrNull { it.isNotBlank() } ?: "exit ${r.code}"
            Log.w(TAG, "write $path failed: $why")
            return WriteResult(false, why.take(160))
        } catch (e: java.io.IOException) {
            return WriteResult(false, "temporary file: ${e.message}")
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
