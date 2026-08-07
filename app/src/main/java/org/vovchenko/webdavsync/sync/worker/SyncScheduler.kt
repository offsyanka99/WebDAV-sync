package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.SyncControl
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** (Re)schedules the periodic sync work and enqueues one-off manual/instant syncs (plan Phase 5). */
@Singleton
class SyncScheduler @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val syncControl: SyncControl,
    private val diagnosticLogger: DiagnosticLogger,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Call whenever auto-sync settings change (autoSyncEnabled, interval, wifiOnly, onlyWhileCharging). */
    suspend fun reschedulePeriodicSync() {
        val settings = settingsRepository.settings.first()
        if (!settings.autoSyncEnabled) {
            workManager.cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC_WORK_NAME)
            return
        }

        val intervalMinutes = settings.autoSyncIntervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(buildConstraints(settings))
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /**
     * Triggers an immediate sync — either one folder pair or all enabled pairs.
     *
     * Never [ExistingWorkPolicy.REPLACE]s a running/queued pass: that cancelled mid-download
     * transfers, left partial files, and the replacement pass treated them as local-only uploads
     * (duplicates / conflicted copies). While busy we record a follow-up instead.
     */
    suspend fun enqueueImmediateSync(folderPairId: Long? = null) {
        if (syncControl.isSessionActive || hasActiveManualWork()) {
            syncControl.requestFollowUpSync()
            diagnosticLogger.i(
                TAG,
                "Coalesce immediate sync (sessionActive=${syncControl.isSessionActive}) pairId=${folderPairId ?: "all"}",
            )
            return
        }

        val inputData = folderPairId?.let {
            Data.Builder().putLong(SyncWorker.KEY_FOLDER_PAIR_ID, it).build()
        } ?: Data.EMPTY

        // Security audit finding #7: manual syncs must respect the same Wi-Fi-only/charging
        // constraints as scheduled syncs, otherwise "Wi-Fi only" can be silently bypassed.
        val settings = settingsRepository.settings.first()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setConstraints(buildConstraints(settings))
            .build()

        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_MANUAL_WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Chains one full sync after the current unique manual work finishes.
     * Must not go through [enqueueImmediateSync] — that would see this worker still RUNNING and
     * only set the follow-up flag again (infinite deferral).
     */
    suspend fun enqueueFollowUpIfNeeded(requested: Boolean) {
        if (!requested) return
        diagnosticLogger.i(TAG, "Enqueueing follow-up sync after completed pass")
        val settings = settingsRepository.settings.first()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(buildConstraints(settings))
            .build()
        // APPEND runs after the finishing worker; APPEND_OR_REPLACE if the prior work was cancelled.
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_MANUAL_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    private fun hasActiveManualWork(): Boolean {
        val infos = runCatching {
            workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_MANUAL_WORK_NAME).get()
        }.getOrDefault(emptyList())
        return infos.any {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
    }

    /**
     * True while a sync worker is running, or a manual sync is queued/blocked on constraints.
     * Periodic work sitting ENQUEUED until the next interval is ignored (that is idle waiting).
     */
    fun observeIsSyncActive(): Flow<Boolean> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_MANUAL_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_PERIODIC_WORK_NAME),
    ) { manual, periodic ->
        manual.any { it.state.isActiveForManual } || periodic.any { it.state == WorkInfo.State.RUNNING }
    }

    private val WorkInfo.State.isActiveForManual: Boolean
        get() = this == WorkInfo.State.ENQUEUED ||
            this == WorkInfo.State.RUNNING ||
            this == WorkInfo.State.BLOCKED

    private fun buildConstraints(settings: AppSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.onlyWhileCharging)
        .build()

    private companion object {
        private const val TAG = "SyncScheduler"
        const val MIN_INTERVAL_MINUTES = 15 // androidx.work.PeriodicWorkRequest's enforced floor
    }
}
