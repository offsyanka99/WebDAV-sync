package org.vovchenko.webdavsync.sync.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.service.SyncNotificationHelper
import javax.inject.Inject

/**
 * Handles Pause / Resume / Cancel actions on the sync foreground notification (plan Phase 11).
 */
@AndroidEntryPoint
class SyncActionReceiver : BroadcastReceiver() {

    @Inject lateinit var syncControl: SyncControl
    @Inject lateinit var notificationHelper: SyncNotificationHelper
    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_PAUSE -> {
                diagnosticLogger.i(TAG, "Notification action: pause")
                syncControl.pause()
                notificationHelper.notifyProgress(
                    context.getString(org.vovchenko.webdavsync.R.string.sync_notification_paused),
                    isPaused = true,
                )
            }
            ACTION_RESUME -> {
                diagnosticLogger.i(TAG, "Notification action: resume")
                syncControl.resume()
                notificationHelper.notifyProgress(
                    context.getString(org.vovchenko.webdavsync.R.string.sync_notification_resuming),
                    isPaused = false,
                )
            }
            ACTION_CANCEL -> {
                diagnosticLogger.i(TAG, "Notification action: cancel")
                syncControl.cancel()
                notificationHelper.notifyProgress(
                    context.getString(org.vovchenko.webdavsync.R.string.sync_notification_cancelling),
                    isPaused = false,
                    showActions = false,
                )
            }
        }
    }

    companion object {
        private const val TAG = "SyncActionReceiver"
        const val ACTION_PAUSE = "org.vovchenko.webdavsync.action.PAUSE_SYNC"
        const val ACTION_RESUME = "org.vovchenko.webdavsync.action.RESUME_SYNC"
        const val ACTION_CANCEL = "org.vovchenko.webdavsync.action.CANCEL_SYNC"
    }
}
