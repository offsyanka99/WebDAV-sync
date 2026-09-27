package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.FollowUpRequest
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
        enqueuePeriodic(ExistingPeriodicWorkPolicy.UPDATE)
    }

    /**
     * Ensures a periodic job exists without resetting its timer.
     * Used on process start. Settings changes go through [reschedulePeriodicSync].
     */
    suspend fun ensurePeriodicSyncPresent() {
        enqueuePeriodic(ExistingPeriodicWorkPolicy.KEEP)
    }

    /** Drops the periodic job. Boot uses this when auto-start after reboot is off. */
    fun cancelPeriodicSync() {
        workManager.cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC_WORK_NAME)
    }

    private suspend fun enqueuePeriodic(policy: ExistingPeriodicWorkPolicy) {
        val settings = settingsRepository.settings.first()
        if (!settings.autoSyncEnabled) {
            cancelPeriodicSync()
            return
        }

        val intervalMinutes = settings.autoSyncIntervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val flexMinutes = flexIntervalMinutes(intervalMinutes)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(
            intervalMinutes.toLong(),
            TimeUnit.MINUTES,
            flexMinutes,
            TimeUnit.MINUTES,
        )
            .setConstraints(buildConstraints(settings, requireBatteryNotLow = !settings.syncEvenWhenBatteryLow))
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC_WORK_NAME,
            policy,
            request,
        )
    }

    /**
     * Triggers an immediate sync — either one folder pair or all enabled pairs.
     *
     * Never [ExistingWorkPolicy.REPLACE]s a running/queued pass: that cancelled mid-download
     * transfers, left partial files, and the replacement pass treated them as local-only uploads
     * (duplicates / conflicted copies). While busy we record a follow-up instead.
     *
     * Also coalesces when the **periodic** worker is running — those are separate unique works and
     * would otherwise start a second concurrent pass.
     */
    suspend fun enqueueImmediateSync(folderPairId: Long? = null) {
        if (syncControl.isSessionActive) {
            syncControl.requestFollowUpSync(folderPairId)
            diagnosticLogger.i(
                TAG,
                "Coalesce immediate sync during active session pairId=${folderPairId ?: "all"}",
            )
            return
        }
        if (hasActiveManualWork()) {
            // The queued pass has not scanned yet, so it will see this edit.
            // Recording a follow-up here used to run every enabled pair a second time.
            diagnosticLogger.i(
                TAG,
                "Skip follow-up; manual sync already queued pairId=${folderPairId ?: "all"}",
            )
            return
        }

        val inputData = folderPairId?.let {
            Data.Builder().putLong(SyncWorker.KEY_FOLDER_PAIR_ID, it).build()
        } ?: Data.EMPTY

        // Security audit finding #7: manual syncs must respect the same Wi-Fi-only/charging
        // constraints as scheduled syncs, otherwise "Wi-Fi only" can be silently bypassed.
        val settings = settingsRepository.settings.first()
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_MANUAL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            buildManualSyncRequest(settings, inputData),
        )
    }

    /**
     * Chains one full sync after the current unique manual work finishes.
     * Must not go through [enqueueImmediateSync] — that would see this worker still RUNNING and
     * only set the follow-up flag again (infinite deferral).
     */
    suspend fun enqueueFollowUpIfNeeded(followUp: FollowUpRequest) {
        if (followUp.isEmpty) return
        diagnosticLogger.i(
            TAG,
            "Enqueueing follow-up sync after completed pass allPairs=${followUp.allPairs} pairs=${followUp.pairIds}",
        )
        val settings = settingsRepository.settings.first()
        // APPEND runs after the finishing worker; APPEND_OR_REPLACE if the prior work was cancelled.
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_MANUAL_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildManualSyncRequest(settings, followUp.toWorkInput()),
        )
    }

    /**
     * Durable instant-upload wakeup: one-time WorkManager jobs with content-URI triggers.
     * Periodic work cannot use content-URI triggers; each fire is consumed, so the watch is
     * re-armed by [ContentWatchWorker] via [ExistingWorkPolicy.APPEND].
     */
    fun reconcileContentWatches(toWatch: Map<Long, FolderPairEntity>) {
        cancelStaleContentWatches(toWatch.keys)
        for (pair in toWatch.values) {
            armContentWatch(pair, ExistingWorkPolicy.REPLACE)
        }
    }

    /**
     * While any pair is watched, wake every [INSTANT_POLL_DELAY_MINUTES] and compare the root
     * snapshot. SAF often never delivers a content notification for a file copied by another app.
     */
    fun reconcileInstantPoll(enabled: Boolean) {
        if (!enabled) {
            workManager.cancelUniqueWork(INSTANT_POLL_WORK_NAME)
            return
        }
        workManager.enqueueUniqueWork(
            INSTANT_POLL_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            instantPollRequest(),
        )
    }

    fun scheduleNextInstantPoll() {
        // APPEND, not REPLACE: REPLACE would cancel this worker before it can finish.
        workManager.enqueueUniqueWork(
            INSTANT_POLL_WORK_NAME,
            ExistingWorkPolicy.APPEND,
            instantPollRequest(),
        )
    }

    private fun instantPollRequest() =
        OneTimeWorkRequestBuilder<InstantRootPollWorker>()
            .setInitialDelay(INSTANT_POLL_DELAY_MINUTES, TimeUnit.MINUTES)
            .build()

    fun cancelContentWatch(pairId: Long) {
        workManager.cancelUniqueWork(contentWatchName(pairId))
    }

    /**
     * Enqueues (or chains) a content-URI watch for [pair].
     *
     * Use [ExistingWorkPolicy.APPEND] from a running [ContentWatchWorker] so REPLACE does not
     * cancel the worker that is still in [androidx.work.ListenableWorker.doWork].
     */
    fun armContentWatch(
        pair: FolderPairEntity,
        policy: ExistingWorkPolicy,
    ) {
        val treeUri = runCatching { Uri.parse(pair.localFolderUri) }.getOrNull() ?: return
        if (treeUri.scheme != ContentResolverScheme) return

        val request = OneTimeWorkRequestBuilder<ContentWatchWorker>()
            .setInputData(Data.Builder().putLong(ContentWatchWorker.KEY_FOLDER_PAIR_ID, pair.id).build())
            .setConstraints(buildContentWatchConstraints(treeUri))
            .addTag(CONTENT_WATCH_TAG)
            .build()

        workManager.enqueueUniqueWork(contentWatchName(pair.id), policy, request)
        diagnosticLogger.i(TAG, "Armed content-URI watch pairId=${pair.id} policy=$policy")
    }

    private fun cancelStaleContentWatches(keepIds: Set<Long>) {
        val infos = runCatching { workManager.getWorkInfosByTag(CONTENT_WATCH_TAG).get() }
            .getOrDefault(emptyList())
        for (info in infos) {
            if (info.state.isFinished) continue
            val pairId = pairIdFromContentWatchTags(info.tags)
            if (pairId == null || pairId !in keepIds) {
                workManager.cancelWorkById(info.id)
            }
        }
    }

    private fun hasActiveManualWork(): Boolean {
        val infos = workInfos(SyncWorker.UNIQUE_MANUAL_WORK_NAME)
        return infos.any {
            it.state == WorkInfo.State.ENQUEUED ||
                it.state == WorkInfo.State.RUNNING ||
                it.state == WorkInfo.State.BLOCKED
        }
    }

    private fun hasRunningPeriodicWork(): Boolean =
        workInfos(SyncWorker.UNIQUE_PERIODIC_WORK_NAME).any { it.state == WorkInfo.State.RUNNING }

    /**
     * True only while a sync worker is **RUNNING** (blocking snapshot for widget paint).
     * Same rule as [observeIsSyncActive].
     */
    fun isSyncWorkerRunning(): Boolean =
        workInfos(SyncWorker.UNIQUE_MANUAL_WORK_NAME).any { it.state == WorkInfo.State.RUNNING } ||
            workInfos(SyncWorker.UNIQUE_PERIODIC_WORK_NAME).any { it.state == WorkInfo.State.RUNNING }

    /**
     * True only while a sync worker is **RUNNING**.
     *
     * Manual work left ENQUEUED/BLOCKED on unmet constraints (Wi‑Fi only, charging) must not keep
     * Overview stuck on "Sync in process..." after a finished pass already wrote Last sync / Duration
     * and the widget shows OK. Queued follow-ups still start when constraints are met.
     */
    fun observeIsSyncActive(): Flow<Boolean> = combine(
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_MANUAL_WORK_NAME),
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_PERIODIC_WORK_NAME),
    ) { manual, periodic ->
        manual.any { it.state == WorkInfo.State.RUNNING } ||
            periodic.any { it.state == WorkInfo.State.RUNNING }
    }

    private fun workInfos(uniqueName: String): List<WorkInfo> =
        runCatching { workManager.getWorkInfosForUniqueWork(uniqueName).get() }
            .getOrDefault(emptyList())

    private fun buildManualSyncRequest(
        settings: AppSettings,
        inputData: Data = Data.EMPTY,
    ) = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInputData(inputData)
        // Manual / follow-up is user-initiated: do not block on battery-low (periodic still does).
        .setConstraints(buildConstraints(settings, requireBatteryNotLow = false))
        .build()

    /**
     * Content-URI only — no network/charging here. The watch must fire while offline so we can
     * re-arm it; [enqueueImmediateSync] still applies Wi‑Fi / charging constraints to the transfer.
     */
    private fun buildContentWatchConstraints(treeUri: Uri): Constraints {
        val builder = Constraints.Builder()
            .addContentUriTrigger(treeUri, /* triggerForDescendants = */ true)
            .setTriggerContentUpdateDelay(CONTENT_TRIGGER_UPDATE_DELAY_SECONDS, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(CONTENT_TRIGGER_MAX_DELAY_SECONDS, TimeUnit.SECONDS)
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            builder.addContentUriTrigger(children, true)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "SyncScheduler"
        const val MIN_INTERVAL_MINUTES = 15 // androidx.work.PeriodicWorkRequest's enforced floor
        const val CONTENT_WATCH_TAG = "content_watch"
        const val INSTANT_POLL_WORK_NAME = "instant_root_poll"
        const val INSTANT_POLL_DELAY_MINUTES = 2L
        private const val CONTENT_WATCH_NAME_PREFIX = "content_watch_"
        private const val ContentResolverScheme = "content"
        /** Batch a burst of file notifications into one wake. */
        private const val CONTENT_TRIGGER_UPDATE_DELAY_SECONDS = 60L
        /** A long copy should not re-wake the device every half minute. */
        private const val CONTENT_TRIGGER_MAX_DELAY_SECONDS = 10L * 60L
        /** Floor for the flex window so JobScheduler can still batch (must stay < interval). */
        private const val MIN_FLEX_MINUTES = 5

        fun contentWatchName(pairId: Long): String = "$CONTENT_WATCH_NAME_PREFIX$pairId"

        /**
         * Flex window at the end of each period so Android can batch with other jobs.
         * Always strictly less than [intervalMinutes].
         */
        fun flexIntervalMinutes(intervalMinutes: Int): Long {
            val interval = intervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
            val flex = (interval / 4).coerceAtLeast(MIN_FLEX_MINUTES)
            return flex.coerceAtMost(interval - 1).toLong()
        }

        fun buildConstraints(settings: AppSettings, requireBatteryNotLow: Boolean): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresCharging(settings.onlyWhileCharging)
                .setRequiresBatteryNotLow(requireBatteryNotLow)
                .build()

        internal fun pairIdFromContentWatchTags(tags: Set<String>): Long? =
            tags.firstNotNullOfOrNull { tag ->
                if (tag.startsWith(CONTENT_WATCH_NAME_PREFIX) && tag != CONTENT_WATCH_TAG) {
                    tag.removePrefix(CONTENT_WATCH_NAME_PREFIX).toLongOrNull()
                } else {
                    null
                }
            }
    }
}
