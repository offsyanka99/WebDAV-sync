package org.vovchenko.webdavsync.push

import android.app.job.JobScheduler
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.AppDatabase
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import org.vovchenko.webdavsync.sync.worker.SyncWorker

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushEventHandlerTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var workManager: WorkManager
    private lateinit var pushRepository: PushRepository
    private lateinit var syncControl: SyncControl
    private lateinit var handler: PushEventHandler
    private var account = 0L
    private var otherAccount = 0L
    private var pairA = 0L
    private var pairB = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        if (WorkManager.isInitialized()) runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
        context.getSystemService(JobScheduler::class.java)?.cancelAll()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val settings = SettingsRepository(SettingsDataStore(context))
        settings.update { it.copy(instantDownloadEnabled = true) }
        val logger = DiagnosticLogger(context, settings, CoroutineScope(Dispatchers.Unconfined))
        pushRepository = PushRepository(db.pushDao(), db, db.folderPairDao(), settings)
        syncControl = SyncControl(InFlightCallRegistry())
        handler = PushEventHandler(
            pushRepository,
            FolderPairRepository(db.folderPairDao(), db),
            SyncScheduler(context, settings, syncControl, logger),
            logger,
        )

        account = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "A", baseUrl = "https://a.example/dav/"))
        otherAccount = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "B", baseUrl = "https://b.example/dav/"))
        // Two pairs on one root share a registration; a to-cloud pair is not served.
        pairA = insertPair(account, "Sync", SyncMethod.TWO_WAY)
        pairB = insertPair(account, "Sync", SyncMethod.TO_DEVICE)
        insertPair(account, "Sync", SyncMethod.TO_CLOUD)
        insertPair(otherAccount, "Other", SyncMethod.TWO_WAY)
        activeRow(account, "Sync", "topic-a")
        activeRow(otherAccount, "Other", "topic-b")
    }

    @After
    fun tearDown() {
        db.close()
        settingsOff()
    }

    @Test
    fun `content update marks the served pairs and queues one delayed push sync`() = runTest {
        handler.onMessage("account-$account", message("topic-a"), decrypted = true)

        assertEquals(setOf(pairA, pairB), db.folderPairDao().getRemoteChangePending().map { it.id }.toSet())
        val work = workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME).get()
        assertEquals(1, work.size)
        assertEquals(WorkInfo.State.ENQUEUED, work.single().state)
    }

    @Test
    fun `untrusted or unroutable messages change nothing`() = runTest {
        handler.onMessage("account-$account", message("topic-a"), decrypted = false)
        handler.onMessage("account-999", message("topic-a"), decrypted = true)
        handler.onMessage("other-$account", message("topic-a"), decrypted = true)
        handler.onMessage("account-$account", message("unknown"), decrypted = true)
        // Another account's topic is not routed through this instance.
        handler.onMessage("account-$account", message("topic-b"), decrypted = true)
        handler.onMessage("account-$account", "garbage".toByteArray(), decrypted = true)

        assertTrue(db.folderPairDao().getRemoteChangePending().isEmpty())
        assertTrue(workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME).get().isEmpty())
    }

    @Test
    fun `push during an active session becomes a follow-up`() = runTest {
        syncControl.beginSession()
        handler.onMessage("account-$account", message("topic-a"), decrypted = true)

        assertTrue(workManager.getWorkInfosForUniqueWork(SyncWorker.UNIQUE_PUSH_WORK_NAME).get().isEmpty())
        assertEquals(setOf(pairA, pairB), syncControl.endSession().pairIds)
        assertEquals(2, db.folderPairDao().getRemoteChangePending().size)
    }

    @Test
    fun `key rotation sends the account back to discovery`() = runTest {
        val rotation = """<push-message xmlns="https://bitfire.at/webdav-push" xmlns:D="DAV:"><topic>topic-a</topic>
<property-update><D:prop><transports/></D:prop></property-update></push-message>""".toByteArray()
        handler.onMessage("account-$account", rotation, decrypted = true)

        val states = pushRepository.getRegistrations().associate { it.accountId to it.state }
        assertEquals(PushRegistrationState.DISCOVER, states[account])
        assertEquals(PushRegistrationState.ACTIVE, states[otherAccount])
        assertTrue(workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_RECONCILE_WORK_NAME).get().isNotEmpty())
    }

    @Test
    fun `new endpoint is stored once and queues a reconcile`() = runTest {
        handler.onNewEndpoint("account-$account", "https://push.example/up/1", "BPub", "Auth", temporary = false)
        val stored = pushRepository.getEndpoint(account)!!
        assertEquals(PushEndpointState.READY, stored.state)
        assertEquals("https://push.example/up/1", stored.endpointUrl)
        assertTrue(workManager.getWorkInfosForUniqueWork(SyncScheduler.PUSH_RECONCILE_WORK_NAME).get().isNotEmpty())

        // A repeated, identical endpoint must not look newer than existing registrations.
        Thread.sleep(5)
        handler.onNewEndpoint("account-$account", "https://push.example/up/1", "BPub", "Auth", temporary = false)
        assertEquals(stored.updatedAt, pushRepository.getEndpoint(account)!!.updatedAt)
    }

    @Test
    fun `endpoint without keys or https fails, unknown instance is ignored`() = runTest {
        handler.onNewEndpoint("account-$account", "https://push.example/up/1", null, null, temporary = false)
        assertEquals(PushEndpointState.FAILED, pushRepository.getEndpoint(account)!!.state)

        handler.onNewEndpoint("account-$otherAccount", "http://push.example/up/2", "BPub", "Auth", temporary = false)
        assertEquals(PushEventHandler.ERR_NOT_HTTPS, pushRepository.getEndpoint(otherAccount)!!.lastError)

        val stranger = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "C", baseUrl = "https://c.example/"))
        handler.onNewEndpoint("account-$stranger", "https://push.example/up/3", "BPub", "Auth", temporary = false)
        assertNull(pushRepository.getEndpoint(stranger))
    }

    @Test
    fun `unregistered endpoint sends active rows back to waiting`() = runTest {
        pushRepository.upsertEndpoint(
            PushEndpointEntity(account, "account-$account", PushEndpointState.READY, "V", "https://push/1", "k", "s", updatedAt = 1),
        )
        handler.onUnregistered("account-$account")

        assertEquals(PushEndpointState.UNREGISTERED, pushRepository.getEndpoint(account)!!.state)
        val row = pushRepository.getRegistrationsForAccount(account).single()
        assertEquals(PushRegistrationState.WAITING_FOR_ENDPOINT, row.state)
        assertEquals("https://a.example/dav/push-subscriptions/topic-a", row.registrationUrl)
    }

    private fun message(topic: String) =
        """<push-message xmlns="https://bitfire.at/webdav-push"><topic>$topic</topic><content-update/></push-message>"""
            .toByteArray()

    private suspend fun insertPair(accountId: Long, remote: String, method: SyncMethod): Long =
        db.folderPairDao().insert(
            FolderPairEntity(
                accountId = accountId,
                name = "$remote-$method",
                remoteFolderPath = remote,
                localFolderUri = "content://$remote/$method",
                syncMethod = method,
            ),
        )

    private suspend fun activeRow(accountId: Long, path: String, topic: String) {
        db.pushDao().insertRegistration(
            PushRegistrationEntity(
                accountId = accountId,
                remotePath = path,
                state = PushRegistrationState.ACTIVE,
                topic = topic,
                registrationUrl = "https://${if (accountId == account) "a" else "b"}.example/dav/push-subscriptions/$topic",
            ),
        )
    }

    private fun settingsOff() = runBlocking {
        SettingsRepository(SettingsDataStore(context)).update { it.copy(instantDownloadEnabled = false) }
        SettingsRepository(SettingsDataStore(context)).settings.first()
    }
}
