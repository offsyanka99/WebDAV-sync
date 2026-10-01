package org.vovchenko.webdavsync.sync.worker

import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.robolectric.shadows.ShadowLooper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.control.SyncWaitReason
import org.vovchenko.webdavsync.sync.control.UserSyncResult

/** Verifies WorkManager scheduling behavior driven by [AppSettings] (plan Phase 5/10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SyncSchedulerTest {

    private lateinit var scheduler: SyncScheduler
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workManager: WorkManager
    private lateinit var syncControl: SyncControl

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        if (WorkManager.isInitialized()) {
            runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
        }
        context.getSystemService(JobScheduler::class.java)?.cancelAll()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        settingsRepository = SettingsRepository(SettingsDataStore(context))
        syncControl = SyncControl(InFlightCallRegistry())
        val logger = DiagnosticLogger(context, settingsRepository, CoroutineScope(Dispatchers.Unconfined))
        scheduler = SyncScheduler(context, settingsRepository, syncControl, logger)
    }

    @Test
    fun `disabling auto-sync cancels the periodic work`() = runTest {
        settingsRepository.update { it.copy(autoSyncEnabled = true) }
        scheduler.reschedulePeriodicSync()

        settingsRepository.update { it.copy(autoSyncEnabled = false) }
        scheduler.reschedulePeriodicSync()

        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PERIODIC_WORK_NAME).get()
        assertTrue(infos.all { it.state == WorkInfo.State.CANCELLED })
    }

    @Test
    fun `enabling auto-sync enqueues periodic work`() = runTest {
        settingsRepository.update { it.copy(autoSyncEnabled = true, autoSyncIntervalMinutes = 30) }
        scheduler.reschedulePeriodicSync()

        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PERIODIC_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos.first().state == WorkInfo.State.ENQUEUED)
    }

    @Test
    fun `manual sync enqueues a one-time work request`() = runTest {
        scheduler.enqueueImmediateSync(folderPairId = 42L)

        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_MANUAL_WORK_NAME).get()
        assertEquals(1, infos.size)
    }

    @Test
    fun `user sync replaces a queued manual sync with the current constraints`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = true, onlyWhileCharging = true) }
        scheduler.enqueueImmediateSync()
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = false) }

        val result = scheduler.enqueueUserSync(bypassUnmetered = false)

        assertEquals(UserSyncResult.Started, result)
        val enqueued = enqueued(SyncWorker.UNIQUE_MANUAL_WORK_NAME)
        assertEquals(enqueued.toString(), 1, enqueued.size)
        assertEquals(NetworkType.CONNECTED, enqueued.single().constraints.requiredNetworkType)
        assertTrue(!enqueued.single().constraints.requiresCharging())
    }

    @Test
    fun `user sync on mobile data drops the wifi requirement and keeps charging`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = true, onlyWhileCharging = true) }

        val result = scheduler.enqueueUserSync(bypassUnmetered = true)

        val waiting = result as UserSyncResult.Waiting
        assertEquals(listOf(SyncWaitReason.CHARGING), waiting.reasons)
        val enqueued = enqueued(SyncWorker.UNIQUE_MANUAL_WORK_NAME)
        assertEquals(1, enqueued.size)
        assertEquals(NetworkType.CONNECTED, enqueued.single().constraints.requiredNetworkType)
        assertTrue(enqueued.single().constraints.requiresCharging())
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = false) }
    }

    @Test
    fun `user sync cancels a waiting push sync and leaves other push work`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = false) }
        scheduler.enqueuePushSync(listOf(1L))
        scheduler.enqueuePushReconcile()
        scheduler.ensurePushMaintenance()

        assertEquals(UserSyncResult.Started, scheduler.enqueueUserSync(bypassUnmetered = false))

        assertTrue(
            workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME).get()
                .all { it.state == WorkInfo.State.CANCELLED },
        )
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_RECONCILE_WORK_NAME).get()
                .single().state,
        )
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_MAINTENANCE_WORK_NAME).get()
                .single().state,
        )
        assertEquals(1, enqueued(SyncWorker.UNIQUE_MANUAL_WORK_NAME).size)
    }

    @Test
    fun `user sync during a running session is a follow-up`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = false) }
        syncControl.beginSession()

        assertEquals(UserSyncResult.AlreadyRunning, scheduler.enqueueUserSync(bypassUnmetered = false))

        assertTrue(enqueued(SyncWorker.UNIQUE_MANUAL_WORK_NAME).isEmpty())
        val followUp = syncControl.endSession()
        assertTrue(followUp.allPairs)
    }

    @Test
    fun `second automatic sync stays behind the user sync`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = false) }
        scheduler.enqueueUserSync(bypassUnmetered = false)
        scheduler.enqueueImmediateSync(folderPairId = 3L)

        assertEquals(1, enqueued(SyncWorker.UNIQUE_MANUAL_WORK_NAME).size)
    }

    @Test
    fun `second immediate sync while session active is coalesced not replaced`() = runTest {
        syncControl.beginSession()
        scheduler.enqueueImmediateSync(folderPairId = 1L)
        // No work should be enqueued; follow-up is recorded on SyncControl.
        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_MANUAL_WORK_NAME).get()
        assertTrue(infos.isEmpty() || infos.none { it.state == WorkInfo.State.RUNNING })
        val followUp = syncControl.endSession()
        assertEquals(setOf(1L), followUp.pairIds)
    }

    @Test
    fun `content-URI watch is armed for a pair and cancelled when no longer watched`() {
        val pair = FolderPairEntity(
            id = 7L,
            accountId = 1L,
            name = "Photos",
            remoteFolderPath = "/Photos",
            localFolderUri = "content://com.android.externalstorage.documents/tree/primary%3APhotos",
        )
        scheduler.reconcileContentWatches(mapOf(pair.id to pair))

        val armed = awaitWork(SyncScheduler.contentWatchName(7L), WorkInfo.State.ENQUEUED)
        assertEquals(armed.toString(), 1, armed.size)
        assertEquals(armed.toString(), WorkInfo.State.ENQUEUED, armed.first().state)
        assertTrue(armed.first().tags.contains(SyncScheduler.CONTENT_WATCH_TAG))

        scheduler.reconcileContentWatches(emptyMap())

        val after = awaitWork(SyncScheduler.contentWatchName(7L), WorkInfo.State.CANCELLED)
        assertEquals(after.toString(), 1, after.size)
        assertEquals(after.toString(), WorkInfo.State.CANCELLED, after.first().state)
    }

    private fun enqueued(name: String): List<WorkInfo> =
        workManager.getWorkInfosForUniqueWork(name).get().filter { it.state == WorkInfo.State.ENQUEUED }

    private fun awaitWork(name: String, want: WorkInfo.State): List<WorkInfo> {
        var last = emptyList<WorkInfo>()
        repeat(20) {
            ShadowLooper.runUiThreadTasks()
            last = workManager.getWorkInfosForUniqueWork(name).get()
            if (last.any { it.state == want }) return last
            Thread.sleep(25)
        }
        return last
    }

    @Test
    fun `armContentWatch ignores non-content uris`() {
        val pair = FolderPairEntity(
            id = 8L,
            accountId = 1L,
            name = "Bad",
            remoteFolderPath = "/Bad",
            localFolderUri = "file:///sdcard/Photos",
        )
        scheduler.armContentWatch(pair, ExistingWorkPolicy.KEEP)
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.contentWatchName(8L)).get()
        assertTrue(infos.isEmpty())
    }

    @Test
    fun `pairIdFromContentWatchTags reads unique-work tag`() {
        assertEquals(
            42L,
            SyncScheduler.pairIdFromContentWatchTags(
                setOf(SyncScheduler.CONTENT_WATCH_TAG, SyncScheduler.contentWatchName(42L)),
            ),
        )
        assertNull(SyncScheduler.pairIdFromContentWatchTags(setOf(SyncScheduler.CONTENT_WATCH_TAG)))
    }

    @Test
    fun `flex window is a quarter of the interval and stays below it`() {
        assertEquals(5L, SyncScheduler.flexIntervalMinutes(15))
        assertEquals(15L, SyncScheduler.flexIntervalMinutes(60))
        assertEquals(45L, SyncScheduler.flexIntervalMinutes(AppSettings.BATTERY_SAVER_INTERVAL_MINUTES))
        val flex = SyncScheduler.flexIntervalMinutes(20)
        assertTrue(flex < 20)
        assertTrue(flex >= 5)
    }

    @Test
    fun `periodic constraints require battery not low unless user overrides`() {
        val conservative = SyncScheduler.buildConstraints(AppSettings(), requireBatteryNotLow = true)
        assertTrue(conservative.requiresBatteryNotLow())

        val override = SyncScheduler.buildConstraints(
            AppSettings(syncEvenWhenBatteryLow = true),
            requireBatteryNotLow = false,
        )
        assertTrue(!override.requiresBatteryNotLow())
    }

    @Test
    fun `push sync is one delayed KEEP job with the sync constraints`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = true, onlyWhileCharging = true, syncEvenWhenBatteryLow = false) }
        scheduler.enqueuePushSync(listOf(1L))
        scheduler.enqueuePushSync(listOf(2L))

        val infos = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME).get()
        assertEquals(1, infos.size)
        val info = infos.single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertTrue(info.initialDelayMillis >= SyncScheduler.PUSH_SYNC_DELAY_SECONDS * 1000)
        assertEquals(NetworkType.UNMETERED, info.constraints.requiredNetworkType)
        assertTrue(info.constraints.requiresCharging())
        assertTrue(info.constraints.requiresBatteryNotLow())
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = false) }
    }

    @Test
    fun `push during a session is a follow-up, not a second worker`() = runTest {
        syncControl.beginSession()
        scheduler.enqueuePushSync(listOf(5L, 6L))

        assertTrue(workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME).get().isEmpty())
        assertEquals(setOf(5L, 6L), syncControl.endSession().pairIds)
    }

    @Test
    fun `push reconcile is coalesced while queued`() = runTest {
        settingsRepository.update { it.copy(wifiOnly = false, onlyWhileCharging = true) }
        scheduler.enqueuePushReconcile()
        scheduler.enqueuePushReconcile()
        val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_RECONCILE_WORK_NAME).get()
        assertEquals(1, infos.size)
        assertEquals(NetworkType.CONNECTED, infos.single().constraints.requiredNetworkType)
        assertTrue(!infos.single().constraints.requiresCharging())
        settingsRepository.update { it.copy(onlyWhileCharging = false) }
    }

    @Test
    fun `push maintenance is enqueued and cancelled`() = runTest {
        scheduler.ensurePushMaintenance()
        assertEquals(
            WorkInfo.State.ENQUEUED,
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_MAINTENANCE_WORK_NAME).get().single().state,
        )
        scheduler.cancelPushMaintenance()
        assertTrue(
            workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_MAINTENANCE_WORK_NAME).get()
                .all { it.state == WorkInfo.State.CANCELLED },
        )
    }
}
