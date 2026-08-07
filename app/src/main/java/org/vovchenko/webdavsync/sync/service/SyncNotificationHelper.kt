package org.vovchenko.webdavsync.sync.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import org.vovchenko.webdavsync.MainActivity
import org.vovchenko.webdavsync.R
import org.vovchenko.webdavsync.sync.worker.SyncActionReceiver
import javax.inject.Inject
import javax.inject.Singleton

/** Builds the persistent notification shown while [org.vovchenko.webdavsync.sync.worker.SyncWorker] is active. */
@Singleton
class SyncNotificationHelper @Inject constructor(
    private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sync_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.sync_notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun foregroundInfo(progressText: String, isPaused: Boolean = false): ForegroundInfo {
        val notification = buildNotification(progressText, isPaused = isPaused, showActions = true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    /** Updates the existing sync notification (used by pause/resume/cancel actions). */
    fun notifyProgress(progressText: String, isPaused: Boolean, showActions: Boolean = true) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(progressText, isPaused = isPaused, showActions = showActions),
        )
    }

    private fun buildNotification(
        progressText: String,
        isPaused: Boolean,
        showActions: Boolean,
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            REQUEST_OPEN_APP,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.sync_notification_title))
            .setContentText(progressText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)

        if (showActions) {
            if (isPaused) {
                builder.addAction(
                    0,
                    context.getString(R.string.sync_notification_action_resume),
                    actionPendingIntent(REQUEST_RESUME, SyncActionReceiver.ACTION_RESUME),
                )
            } else {
                builder.addAction(
                    0,
                    context.getString(R.string.sync_notification_action_pause),
                    actionPendingIntent(REQUEST_PAUSE, SyncActionReceiver.ACTION_PAUSE),
                )
            }
            builder.addAction(
                0,
                context.getString(R.string.sync_notification_action_cancel),
                actionPendingIntent(REQUEST_CANCEL, SyncActionReceiver.ACTION_CANCEL),
            )
        }

        return builder.build()
    }

    private fun actionPendingIntent(requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, SyncActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val CHANNEL_ID = "sync_progress"
        const val NOTIFICATION_ID = 1001

        private const val REQUEST_OPEN_APP = 0
        private const val REQUEST_PAUSE = 1
        private const val REQUEST_RESUME = 2
        private const val REQUEST_CANCEL = 3
    }
}
