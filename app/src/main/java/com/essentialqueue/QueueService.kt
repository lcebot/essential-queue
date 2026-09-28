package com.essentialqueue

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service that exists only while a queue is running.
 *
 * While the editor is in front, this app sits in the background, where HyperOS freezes or kills
 * processes aggressively. A frozen process misses its scheduled launches, and a killed one also takes
 * the accessibility service down with it. A foreground service keeps the process alive for exactly
 * as long as the user has a queue running, and its notification shows the progress.
 */
class QueueService : Service() {

    companion object {
        private const val CHANNEL_ID = "queue"
        private const val NOTIFICATION_ID = 1

        private val RUNNING = setOf(Queue.State.WAITING, Queue.State.OPENING, Queue.State.CLICKED)

        @Volatile private var active = false

        /**
         * Starts, updates or stops the service to match the queue state. Safe to call often.
         * The service is started only once per run, because a second start from the background can be
         * refused. Progress updates go straight to the notification instead.
         */
        fun sync(ctx: Context) {
            val app = ctx.applicationContext
            val intent = Intent(app, QueueService::class.java)
            when {
                Queue.state in RUNNING && !active -> try {
                    app.startForegroundService(intent)
                } catch (_: Exception) {
                    // Refused while in the background. The next sync from the foreground tries again.
                }
                Queue.state in RUNNING -> {
                    app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(app))
                }
                active -> app.stopService(intent)
            }
        }

        private fun build(ctx: Context): Notification {
            val open = PendingIntent.getActivity(
                ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val total = Queue.items.size
            val done = minOf(Queue.index, total)
            return Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_queue)
                .setContentTitle(ctx.getString(R.string.notif_title))
                .setContentText(ctx.getString(R.string.progress_submitted, done, total))
                .setProgress(total, done, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .build()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        // startForeground comes first on every path: a service started with startForegroundService that
        // stops without it crashes the process, and the queue may have stopped since sync() was called.
        try {
            startForeground(NOTIFICATION_ID, build(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            active = true
        } catch (_: Exception) {
            // The system can still refuse at this point. Running without the service is only less robust.
            stopSelf()
            return START_NOT_STICKY
        }
        if (Queue.state !in RUNNING) stopSelf()
        // A restarted process has lost the in-memory queue, so there is nothing to resume.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        active = false
        super.onDestroy()
    }
}
