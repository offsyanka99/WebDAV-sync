package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.R
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.domain.sync.SyncEngine
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.service.SyncNotificationHelper
import org.vovchenko.webdavsync.widget.SyncWidgetProvider

/** Runs one or all enabled folder pairs' sync, promoting itself to a foreground-service notification (plan Phase 5). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderPairRepository: FolderPairRepository,
    private val syncEngine: SyncEngine,
    private val notificationHelper: SyncNotificationHelper,
    private val syncControl: SyncControl,
    private val diagnosticLogger: DiagnosticLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        syncControl.reset()
        val targetId = inputData.getLong(KEY_FOLDER_PAIR_ID, -1L).takeIf { it >= 0 }
        diagnosticLogger.i(TAG, "Worker start targetPairId=${targetId ?: "all"} runAttempt=$runAttemptCount")
        setForeground(
            notificationHelper.foregroundInfo(applicationContext.getString(R.string.sync_notification_starting)),
        )
        SyncWidgetProvider.showSyncing(applicationContext)

        return try {
            runSyncPass(targetId)
        } finally {
            diagnosticLogger.i(TAG, "Worker finished cancelled=${syncControl.isCancelled} stopped=$isStopped")
            syncControl.reset()
            // Refresh status + recent-change counters after the pass.
            SyncWidgetProvider.requestUpdate(applicationContext)
        }
    }

    private suspend fun runSyncPass(targetId: Long?): Result {
        val pairs = if (targetId != null) {
            listOfNotNull(folderPairRepository.observeById(targetId).first())
        } else {
            folderPairRepository.getEnabled()
        }.filter { it.enabled }

        diagnosticLogger.i(TAG, "Will sync ${pairs.size} folder pair(s)")

        var hadErrors = false
        for (pair in pairs) {
            if (shouldAbort()) {
                diagnosticLogger.i(TAG, "Aborting remaining pairs (cancelled or stopped)")
                break
            }

            syncControl.awaitWhilePaused { isStopped }
            if (shouldAbort()) break

            setForeground(
                notificationHelper.foregroundInfo(
                    applicationContext.getString(R.string.sync_notification_syncing, pair.name),
                    isPaused = syncControl.isPaused,
                ),
            )

            val outcome = syncEngine.sync(pair.id)
            if (outcome.hasErrors && !syncControl.isCancelled) hadErrors = true
        }

        return when {
            syncControl.isCancelled || isStopped -> {
                diagnosticLogger.i(TAG, "Worker result=success (cancelled/stopped)")
                Result.success()
            }
            hadErrors -> {
                diagnosticLogger.w(TAG, "Worker result=retry (errors during sync)")
                Result.retry()
            }
            else -> {
                diagnosticLogger.i(TAG, "Worker result=success")
                Result.success()
            }
        }
    }

    private fun shouldAbort(): Boolean = isStopped || syncControl.shouldStop()

    companion object {
        private const val TAG = "SyncWorker"
        const val UNIQUE_PERIODIC_WORK_NAME = "periodic_sync"
        const val UNIQUE_MANUAL_WORK_NAME = "manual_sync"
        const val KEY_FOLDER_PAIR_ID = "folder_pair_id"
    }
}
