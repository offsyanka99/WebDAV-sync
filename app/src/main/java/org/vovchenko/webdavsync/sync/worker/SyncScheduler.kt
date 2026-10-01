package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.BackoffPolicy
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
import org.vovchenko.webdavsync.sync.control.UserSyncResult
import org.vovchenko.webdavsync.sync.control.manualSyncWaitReasons
import org.vovchenko.webdavsync.util.BatteryStatus
import org.vovchenko.webdavsync.util.NetworkStatus
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
     * Sync button and home-screen widget.
     *
     * A running pass is not cancelled: the tap becomes a follow-up. A `manual_sync` or `push_sync`
     * that has not started is replaced with one request built from the current settings, so a job
     * queued under older Wi-Fi or charging rules cannot block the button. `remoteChangePendingAt`
     * is left set. Content watches, push registration, and push maintenance are not touched.
     *
     * [bypassUnmetered] is only for the explicit "sync now on mobile data" choice.
     */
    suspend fun enqueueUserSync(bypassUnmetered: Boolean): UserSyncResult {
        if (syncControl.isSessionActive || isSyncWorkerRunning()) {
            syncControl.requestFollowUpSync(null)
            diagnosticLogger.i(TAG, "User sync during active session → follow-up")
            return UserSyncResult.AlreadyRunning
        }

        val waitingPush = workInfos(SyncWorker.UNIQUE_PUSH_WORK_NAME).any { it.state.isWaiting() }
        if (waitingPush) {
            workManager.cancelUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME)
        }
        val replacedManual = hasActiveManualWork()
        val settings = settingsRepository.settings.first()
        workManager.enqueueUniqueWork(
            SyncWorker.UNIQUE_MANUAL_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            buildManualSyncRequest(settings, bypassUnmetered = bypassUnmetered),
        )
        val reasons = manualSyncWaitReasons(
            settings = settings,
            onUnmeteredNetwork = NetworkStatus.hasUnmeteredInternet(context),
            charging = BatteryStatus.isCharging(context),
            bypassUnmetered = bypassUnmetered,
        )
        diagnosticLogger.i(
            TAG,
            "User sync queued replacedManual=$replacedManual replacedPush=$waitingPush " +
                "bypassUnmetered=$bypassUnmetered waiting=$reasons",
        )
        return if (reasons.isEmpty()) UserSyncResult.Started else UserSyncResult.Waiting(reasons)
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

    /**
     * A server change arrived by WebDAV-Push for [pairIds] (flags already persisted). During a
     * session the pairs become follow-ups; otherwise one delayed `push_sync` collapses a burst.
     * It follows the periodic battery rule and never bypasses Wi-Fi only or charging.
     */
    suspend fun enqueuePushSync(pairIds: Collection<Long>) {
        if (syncControl.isSessionActive) {
            pairIds.forEach { syncControl.requestFollowUpSync(it) }
            // Re-check: a session that ended in between drops the follow-up, so enqueue instead.
            if (syncControl.isSessionActive) {
                diagnosticLogger.i(TAG, "Push change during active session → follow-up pairs=$pairIds")
                return
            }
        }
        val settings = settingsRepository.settings.first()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(Data.Builder().putBoolean(SyncWorker.KEY_PUSH_PENDING, true).build())
            .setInitialDelay(PUSH_SYNC_DELAY_SECONDS, TimeUnit.SECONDS)
            .setConstraints(buildConstraints(settings, requireBatteryNotLow = !settings.syncEvenWhenBatteryLow))
            .build()
        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        diagnosticLogger.i(TAG, "Push sync queued pairs=$pairIds delay=${PUSH_SYNC_DELAY_SECONDS}s")
    }

    /**
     * Queues `push_reconcile`. A queued run reads fresh state when it starts, so it absorbs this
     * request; a running one gets exactly one run appended after it.
     */
    suspend fun enqueuePushReconcile() {
        val infos = workInfos(PUSH_RECONCILE_WORK_NAME)
        if (infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }) return
        val policy = if (infos.any { it.state == WorkInfo.State.RUNNING }) {
            ExistingWorkPolicy.APPEND_OR_REPLACE
        } else {
            ExistingWorkPolicy.KEEP
        }
        val settings = settingsRepository.settings.first()
        val request = OneTimeWorkRequestBuilder<PushReconcileWorker>()
            .setConstraints(pushNetworkConstraints(settings, requireBatteryNotLow = false))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, PUSH_RECONCILE_BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(PUSH_RECONCILE_WORK_NAME, policy, request)
    }

    /** Daily push upkeep while the feature is on; UPDATE keeps the timer but refreshes constraints. */
    suspend fun ensurePushMaintenance() {
        val settings = settingsRepository.settings.first()
        val request = PeriodicWorkRequestBuilder<PushMaintenanceWorker>(
            PUSH_MAINTENANCE_INTERVAL_HOURS,
            TimeUnit.HOURS,
            PUSH_MAINTENANCE_FLEX_HOURS,
            TimeUnit.HOURS,
        )
            .setConstraints(pushNetworkConstraints(settings, requireBatteryNotLow = true))
            .build()
        workManager.enqueueUniquePeriodicWork(PUSH_MAINTENANCE_WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancelPushMaintenance() {
        workManager.cancelUniqueWork(PUSH_MAINTENANCE_WORK_NAME)
    }

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

    private fun hasActiveManualWork(): Boolean =
        workInfos(SyncWorker.UNIQUE_MANUAL_WORK_NAME).any { it.state.isWaiting() || it.state == WorkInfo.State.RUNNING }

    private fun WorkInfo.State.isWaiting(): Boolean =
        this == WorkInfo.State.ENQUEUED || this == WorkInfo.State.BLOCKED

    private fun hasRunningPeriodicWork(): Boolean =
        workInfos(SyncWorker.UNIQUE_PERIODIC_WORK_NAME).any { it.state == WorkInfo.State.RUNNING }

    /**
     * True only while a sync worker is **RUNNING** (blocking snapshot for widget paint).
     * Same rule as [observeIsSyncActive].
     */
    fun isSyncWorkerRunning(): Boolean =
        workInfos(SyncWorker.UNIQUE_MANUAL_WORK_NAME).any { it.state == WorkInfo.State.RUNNING } ||
            workInfos(SyncWorker.UNIQUE_PERIODIC_WORK_NAME).any { it.state == WorkInfo.State.RUNNING } ||
            workInfos(SyncWorker.UNIQUE_PUSH_WORK_NAME).any { it.state == WorkInfo.State.RUNNING }

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
        workManager.getWorkInfosForUniqueWorkFlow(SyncWorker.UNIQUE_PUSH_WORK_NAME),
    ) { manual, periodic, push ->
        manual.any { it.state == WorkInfo.State.RUNNING } ||
            periodic.any { it.state == WorkInfo.State.RUNNING } ||
            push.any { it.state == WorkInfo.State.RUNNING }
    }

    private fun workInfos(uniqueName: String): List<WorkInfo> =
        runCatching { workManager.getWorkInfosForUniqueWork(uniqueName).get() }
            .getOrDefault(emptyList())

    private fun buildManualSyncRequest(
        settings: AppSettings,
        inputData: Data = Data.EMPTY,
        bypassUnmetered: Boolean = false,
    ) = OneTimeWorkRequestBuilder<SyncWorker>()
        .setInputData(inputData)
        // Manual / follow-up is user-initiated: do not block on battery-low (periodic still does).
        .setConstraints(
            buildConstraints(
                settings,
                requireBatteryNotLow = false,
                bypassUnmetered = bypassUnmetered,
            ),
        )
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
        const val PUSH_RECONCILE_WORK_NAME = "push_reconcile"
        const val PUSH_MAINTENANCE_WORK_NAME = "push_maintenance"
        /** Collapses pushes that arrive seconds apart (e.g. nested roots queued separately). */
        const val PUSH_SYNC_DELAY_SECONDS = 10L
        private const val PUSH_RECONCILE_BACKOFF_MINUTES = 2L
        private const val PUSH_MAINTENANCE_INTERVAL_HOURS = 24L
        private const val PUSH_MAINTENANCE_FLEX_HOURS = 6L
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

        fun buildConstraints(
            settings: AppSettings,
            requireBatteryNotLow: Boolean,
            bypassUnmetered: Boolean = false,
        ): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(
                    if (settings.wifiOnly && !bypassUnmetered) NetworkType.UNMETERED else NetworkType.CONNECTED,
                )
                .setRequiresCharging(settings.onlyWhileCharging)
                .setRequiresBatteryNotLow(requireBatteryNotLow)
                .build()

        /** Registration traffic follows Wi-Fi only but not the charging rule (plan D14). */
        fun pushNetworkConstraints(settings: AppSettings, requireBatteryNotLow: Boolean): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
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
