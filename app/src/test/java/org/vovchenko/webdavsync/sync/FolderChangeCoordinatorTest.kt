package org.vovchenko.webdavsync.sync

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.AppDatabase
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.FolderChangeDebouncer
import org.vovchenko.webdavsync.data.local.saf.LocalTreeFingerprint
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.worker.SyncScheduler

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderChangeCoordinatorTest {

    private lateinit var context: Context
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var workManager: WorkManager
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (!WorkManager.isInitialized()) {
            WorkManagerTestInitHelper.initializeTestWorkManager(context)
        }
        workManager = WorkManager.getInstance(context)
        settingsRepository = SettingsRepository(SettingsDataStore(context))
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `cheap poll does not run on the old 4s cadence`() = runTest {
        val fingerprint = CountingFingerprint(context)
        val job = SupervisorJob()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
        val pair = watchedPair(id = 3L)
        val pairs = MutableStateFlow(listOf(pair))
        val settings = MutableStateFlow(
            AppSettings(autoSyncEnabled = true, syncImmediatelyOnLocalChange = true),
        )

        try {
            coordinator(scope, fingerprint).start(pairs, settings)
            runCurrent()

            val seeded = fingerprint.scans.get()
            assertTrue("seed fingerprint expected, got $seeded", seeded >= 1)

            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("must not scan again at 4s", seeded, fingerprint.scans.get())

            advanceTimeBy(FolderChangeCoordinator.CHEAP_POLL_MS)
            runCurrent()
            assertEquals(
                "healthy observer does not keep polling",
                seeded,
                fingerprint.scans.get(),
            )

            val watches = workManager.getWorkInfosForUniqueWork(SyncScheduler.contentWatchName(pair.id)).get()
            assertTrue(watches.any { it.state == WorkInfo.State.ENQUEUED })
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `poller stops when nothing is watched`() = runTest {
        val fingerprint = CountingFingerprint(context)
        val job = SupervisorJob()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler) + job)
        val pair = watchedPair(id = 4L)
        val pairs = MutableStateFlow(listOf(pair))
        val settings = MutableStateFlow(
            AppSettings(autoSyncEnabled = false, syncImmediatelyOnLocalChange = false),
        )

        try {
            coordinator(scope, fingerprint).start(pairs, settings)
            runCurrent()

            assertEquals(0, fingerprint.scans.get())
            advanceTimeBy(FolderChangeCoordinator.CHEAP_POLL_MS)
            runCurrent()
            assertEquals(0, fingerprint.scans.get())

            val watches = workManager.getWorkInfosForUniqueWork(SyncScheduler.contentWatchName(pair.id)).get()
            assertTrue(watches.none { it.state == WorkInfo.State.ENQUEUED })
        } finally {
            job.cancel()
        }
    }

    private fun coordinator(scope: CoroutineScope, fingerprint: LocalTreeFingerprint): FolderChangeCoordinator {
        val logger = DiagnosticLogger(context, settingsRepository, scope)
        val syncControl = SyncControl(InFlightCallRegistry())
        val scheduler = SyncScheduler(context, settingsRepository, syncControl, logger)
        return FolderChangeCoordinator(
            context = context,
            folderPairRepository = FolderPairRepository(db.folderPairDao(), db),
            settingsRepository = settingsRepository,
            debouncer = FolderChangeDebouncer(scope),
            syncScheduler = scheduler,
            syncControl = syncControl,
            treeFingerprint = fingerprint,
            diagnosticLogger = logger,
            scope = scope,
        )
    }

    private fun watchedPair(id: Long) = FolderPairEntity(
        id = id,
        accountId = 1L,
        name = "Photos",
        remoteFolderPath = "/Photos",
        localFolderUri = TREE_URI,
        enabled = true,
        instantUpload = false,
    )

    private class CountingFingerprint(context: Context) : LocalTreeFingerprint(context) {
        val scans = AtomicInteger(0)
        override fun of(treeUri: Uri): Long {
            scans.incrementAndGet()
            return 1L
        }
    }

    private companion object {
        const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3APhotos"
    }
}
