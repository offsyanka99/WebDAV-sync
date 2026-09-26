package org.vovchenko.webdavsync.sync.worker

import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
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
}
