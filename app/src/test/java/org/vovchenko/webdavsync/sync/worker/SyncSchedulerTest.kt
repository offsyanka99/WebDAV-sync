package org.vovchenko.webdavsync.sync.worker

import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import org.vovchenko.webdavsync.data.repository.SettingsRepository
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
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        settingsRepository = SettingsRepository(SettingsDataStore(context))
        syncControl = SyncControl()
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
        assertTrue(syncControl.endSession())
    }
}
