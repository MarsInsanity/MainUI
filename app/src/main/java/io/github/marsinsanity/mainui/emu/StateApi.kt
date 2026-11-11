package io.github.marsinsanity.mainui.emu

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * MainUI's side of the MainUI State API: the emulators MainUI-Android builds ("<Name> (MainUI)",
 * github.com/MainUI-Android) save and load states when MainUI asks, with a broadcast, instead of
 * hotkeys pressed with root. Their side is tools/stateapi/MainUIStateApi.java (the same file in
 * every one of them); an app has it when its manifest says so ([META_DATA]).
 *
 * A command waits, in the emulator, for the best moment: a game booted and running, not paused,
 * not loading, not in the emulator's own menu (each emulator tells what it can), steadily for
 * settle_ms. So the game must be in front and running (not frozen) while a command is under way:
 * a frozen emulator can't take the broadcast, and Android counts that as not responding.
 */
object StateApi {
    private const val TAG = "MainUI.StateApi"

    const val ACTION_COMMAND = "io.github.mainui.stateapi.COMMAND"
    const val ACTION_RESULT = "io.github.mainui.stateapi.RESULT"
    const val META_DATA = "io.github.mainui.STATE_API"

    /** MainUI's own "continue where I left off" state, apart from the user's slots. */
    const val RESUME_SLOT = -1

    /** What an emulator answered. [error] is in words for the user. */
    class Result(val ok: Boolean, val error: String?, val extras: Bundle? = null) {
        val path: String? get() = extras?.getString("path")
    }

    /** The API version [pkg] has (its manifest's meta-data), 0 when it has none or isn't installed. */
    fun version(pkg: String): Int = runCatching {
        val pm = MainUI.app.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33) pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            else @Suppress("DEPRECATION") pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
        info.metaData?.get(META_DATA)?.toString()?.trim()?.toIntOrNull() ?: 0
    }.getOrDefault(0)

    private val pending = ConcurrentHashMap<String, Pair<CountDownLatch, Array<Bundle?>>>()

    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getStringExtra("request") ?: return
            val p = pending[id] ?: return
            p.second[0] = intent.extras ?: Bundle()
            p.first.countDown()
        }
    }

    private fun register() {
        if (registered) return
        synchronized(this) {
            if (registered) return
            val f = IntentFilter(ACTION_RESULT)
            // The answers come from the emulators: other apps.
            if (Build.VERSION.SDK_INT >= 33) MainUI.app.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
            else MainUI.app.registerReceiver(receiver, f)
            registered = true
        }
    }

    /**
     * Sends [command] ("save", "load" or "status") for [slot] to [pkg] and waits for its answer.
     * The emulator waits up to [waitMs] for the game to be ready, [settleMs] of it steady; with
     * [notBefore] (uptime ms) only a game started at or after it counts. Blocks (never on the UI
     * thread) for at most [timeoutMs].
     */
    fun call(
        pkg: String, command: String, slot: Int = 0,
        waitMs: Long = 15_000, settleMs: Long = 300, notBefore: Long = 0,
        timeoutMs: Long = waitMs + 90_000,
    ): Result {
        register()
        val id = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<Bundle>(1)
        pending[id] = latch to box
        try {
            val i = Intent(ACTION_COMMAND).setPackage(pkg)
                // The foreground queue: the background one can hold a broadcast for many seconds.
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("command", command)
                .putExtra("slot", slot)
                .putExtra("request", id)
                .putExtra("reply_to", MainUI.app.packageName)
                .putExtra("wait_ms", waitMs)
                .putExtra("settle_ms", settleMs)
                .putExtra("not_before", notBefore)
            MainUI.app.sendBroadcast(i)
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "$pkg $command $slot: no answer in $timeoutMs ms")
                return Result(false, "the emulator didn't answer")
            }
            val b = box[0] ?: Bundle()
            val ok = b.getBoolean("ok", false)
            val err = b.getString("error")
            Log.i(TAG, "$pkg $command $slot: ${if (ok) "ok" else "failed: $err"}")
            return Result(ok, if (ok) null else err ?: "the emulator couldn't", b)
        } catch (e: Exception) {
            Log.w(TAG, "$pkg $command $slot", e)
            return Result(false, e.message ?: e.javaClass.simpleName)
        } finally {
            pending.remove(id)
        }
    }
}
