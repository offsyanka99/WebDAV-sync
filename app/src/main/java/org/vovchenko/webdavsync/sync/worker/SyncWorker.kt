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
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.data.repository.WebDavConnectionRepository
import org.vovchenko.webdavsync.domain.sync.SyncEngine
import org.vovchenko.webdavsync.sync.FolderChangeCoordinator
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.service.SyncNotificationHelper
import org.vovchenko.webdavsync.widget.SyncWidgetProvider

/** Runs one or all enabled folder pairs' sync, promoting itself to a foreground-service notification (plan Phase 5). */
@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderPairRepository: FolderPairRepository,
    private val accountRepository: WebDavAccountRepository,
    private val connectionRepository: WebDavConnectionRepository,
    private val syncEngine: SyncEngine,
    private val syncLogRepository: SyncLogRepository,
    private val notificationHelper: SyncNotificationHelper,
    private val syncControl: SyncControl,
    private val diagnosticLogger: DiagnosticLogger,
    private val folderChangeCoordinator: FolderChangeCoordinator,
    private val syncScheduler: SyncScheduler,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        syncControl.beginSession()
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
            val followUp = syncControl.endSession()
            // Refresh status + recent-change counters after the pass.
            SyncWidgetProvider.requestUpdate(applicationContext)
            // Coalesce mid-pass local changes into one follow-up instead of REPLACE mid-download.
            if (followUp && !isStopped) {
                syncScheduler.enqueueFollowUpIfNeeded(requested = true)
            }
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
        val accountIds = mutableSetOf<Long>()
        val syncedPairIds = mutableListOf<Long>()
        // Session totals for Overview / widget "Recent changes" (sum across every pair in this pass).
        var sessionUploaded = 0
        var sessionDownloaded = 0
        var sessionDeletedLocal = 0
        var sessionDeletedRemote = 0
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
            accountIds += pair.accountId
            syncedPairIds += pair.id
            sessionUploaded += outcome.uploaded
            sessionDownloaded += outcome.downloaded
            sessionDeletedLocal += outcome.deletedLocal
            sessionDeletedRemote += outcome.deletedRemote
            if (outcome.hasErrors && !syncControl.isCancelled) hadErrors = true
        }

        // One authoritative counter batch for the whole worker pass. Without this, Overview only
        // saw the last pair's SYNC_END timestamp — quiet last pairs wiped earlier uploads/downloads.
        val summaryPairId = syncedPairIds.lastOrNull()
        if (summaryPairId != null) {
            diagnosticLogger.i(
                TAG,
                "Session recent-changes summary: up=$sessionUploaded down=$sessionDownloaded " +
                    "delDevice=$sessionDeletedLocal delCloud=$sessionDeletedRemote pairs=${syncedPairIds.size}",
            )
            syncLogRepository.logSessionSummary(
                folderPairId = summaryPairId,
                uploaded = sessionUploaded,
                downloaded = sessionDownloaded,
                deletedLocal = sessionDeletedLocal,
                deletedRemote = sessionDeletedRemote,
            )
        }

        // Avoid instant-upload poll treating post-sync local tree changes as a new user edit.
        folderChangeCoordinator.reseedAfterSync(syncedPairIds)

        // Refresh cloud storage quotas shown on Overview after transfers complete.
        for (accountId in accountIds) {
            val account = accountRepository.observeById(accountId).first() ?: continue
            connectionRepository.refreshQuota(account).onFailure {
                diagnosticLogger.w(TAG, "Quota refresh failed accountId=$accountId: ${it.message}")
            }
        }

        // Do not Result.retry() on logical sync errors — that left WorkManager in ENQUEUED forever
        // so the Overview status stayed "Sync in process..." while the widget already showed ERROR.
        return when {
            syncControl.isCancelled || isStopped -> {
                diagnosticLogger.i(TAG, "Worker result=success (cancelled/stopped)")
                Result.success()
            }
            hadErrors -> {
                diagnosticLogger.w(TAG, "Worker result=success (completed with errors)")
                Result.success()
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
