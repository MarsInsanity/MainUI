package io.github.marsinsanity.mainui.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import io.github.marsinsanity.mainui.R
import io.github.marsinsanity.mainui.scraper.ScrapeState
import io.github.marsinsanity.mainui.scraper.Scraper

/** Keeps a scrape running while the user plays or browses elsewhere. */
class ScraperService : Service() {
    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { Scraper.cancel(); return START_NOT_STICKY }
        if (worker?.isAlive == true) return START_NOT_STICKY
        val systems = intent?.getStringArrayExtra(EXTRA_SYSTEMS)?.toList()
        startForeground(NOTIF_ID, notification("Starting..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        worker = Thread {
            val updater = Thread {
                try {
                    while (true) {
                        Thread.sleep(2000)
                        val st = ScrapeState
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIF_ID, notification("${st.done.get()}/${st.total}  ${st.current}"))
                    }
                } catch (_: InterruptedException) {}
            }.apply { isDaemon = true; start() }
            Scraper.run(systems)
            updater.interrupt()
            getSystemService(NotificationManager::class.java).notify(DONE_ID, notification(
                "${ScrapeState.message}: ${ScrapeState.found.get()} found, ${ScrapeState.missing.get()} not found", ongoing = false))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }.apply { name = "mainui-scraper"; start() }
        return START_NOT_STICKY
    }

    private fun notification(text: String, ongoing: Boolean = true): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Scraper", NotificationManager.IMPORTANCE_LOW))
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("MainUI scraper")
            .setContentText(text)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "scraper"
        private const val NOTIF_ID = 42
        private const val DONE_ID = 43
        const val ACTION_STOP = "stop"
        const val EXTRA_SYSTEMS = "systems"

        fun start(ctx: Context, systems: List<String>?) {
            val i = Intent(ctx, ScraperService::class.java)
            if (systems != null) i.putExtra(EXTRA_SYSTEMS, systems.toTypedArray())
            ctx.startForegroundService(i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, ScraperService::class.java).setAction(ACTION_STOP))
        }
    }
}
