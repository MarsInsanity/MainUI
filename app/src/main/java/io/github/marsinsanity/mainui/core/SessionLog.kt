package io.github.marsinsanity.mainui.core

import android.util.Log
import io.github.marsinsanity.mainui.MainUI
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainUI/logs/session.txt: what MainUI did around games (launch, resume, in-game menu, save /
 * load keys, auto load, controller hand-back, RetroArch configuration), for working out what
 * happened on a device from a file the user can send. Kept under ~256 KB (the older half goes).
 */
object SessionLog {
    private const val MAX_BYTES = 256 * 1024
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(msg: String) {
        Log.i("MainUI.Session", msg)
        runCatching {
            val f = File(MainUI.storage.dataDir("logs"), "session.txt")
            if (f.length() > MAX_BYTES) {
                val keep = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(keep.substringAfter('\n'))
            }
            f.appendText("${time.format(Date())}  $msg\n")
        }
    }
}
