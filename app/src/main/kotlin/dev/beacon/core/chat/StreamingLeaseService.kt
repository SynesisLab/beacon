package dev.beacon.core.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import dev.beacon.R

/**
 * The streaming lease (agent.md §6.3/§12): a foreground service of type
 * `dataSync` holding the stream alive while the app is backgrounded. Started
 * by [ChatEngine] on send, stopped on finalize/stop. Data-min notification:
 * no message content leaves into the notification surface (§11).
 */
class StreamingLeaseService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        return START_NOT_STICKY
    }

    private fun buildNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(getString(R.string.streaming_lease_title))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .build()

    companion object {
        private const val CHANNEL = "streaming"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            runCatching {
                context.startForegroundService(Intent(context, StreamingLeaseService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, StreamingLeaseService::class.java)) }
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.streaming_lease_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
    }
}