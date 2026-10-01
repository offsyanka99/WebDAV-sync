package org.vovchenko.webdavsync.push

import android.app.job.JobScheduler
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.AppDatabase
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLog
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.data.remote.SardineWebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.remote.WebDavClientSession
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import java.io.File

/** Runs a full push flow with the diagnostic log on and checks no push secret reaches the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushLogPrivacyTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var logger: DiagnosticLogger

    private val token = "TOKENSECRET" + "x".repeat(32)
    private val registrationUrl = "https://dav.example.com/dav.php/push-subscriptions/$token"
    private val endpointUrl = "https://push.example/upENDPOINTSECRET"
    private val topic = "TOPICSECRET1234567890ab"

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        if (WorkManager.isInitialized()) runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
        context.getSystemService(JobScheduler::class.java)?.cancelAll()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(SettingsDataStore(context))
        settings.update { it.copy(instantDownloadEnabled = true, diagnosticLogEnabled = true) }
        logger = DiagnosticLogger(context, settings, CoroutineScope(Dispatchers.Unconfined))
        logger.setEnabled(true)
        logger.clear()
    }

    @After
    fun tearDown() = runBlocking {
        logger.setEnabled(false)
        logger.clear()
        server.shutdown()
        db.close()
        settings.update { it.copy(instantDownloadEnabled = false, diagnosticLogEnabled = false) }
    }

    @Test
    fun `diagnostic log holds no push secrets`() = runTest {
        val accountId = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "Cloud", baseUrl = server.url("/dav/").toString()))
        val pairId = db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Sync", remoteFolderPath = "Sync", localFolderUri = "content://sync"),
        )
        val repository = PushRepository(db.pushDao(), db, db.folderPairDao(), settings)
        val http = OkHttpClient.Builder().build()
        val client = SardineWebDavClient(http, http, server.url("/dav/").toString(), File("build/tmp"))
        val manager = PushRegistrationManager(
            repository,
            object : PushAccountClients {
                override suspend fun displayName(accountId: Long) = "Cloud"
                override suspend fun clientFor(session: WebDavClientSession, accountId: Long): WebDavClient = client
            },
            WebDavClientFactory(context, InFlightCallRegistry()),
            AlwaysReadyDistributor(),
            settings,
            logger,
        )
        val handler = PushEventHandler(
            repository,
            FolderPairRepository(db.folderPairDao(), db),
            SyncScheduler(context, settings, SyncControl(InFlightCallRegistry()), logger),
            logger,
        )

        server.enqueue(AngaraDavFixtures.discovered(topic))
        manager.reconcile()
        handler.onNewEndpoint("account-$accountId", endpointUrl, "PUBKEYSECRET", "AUTHSECRET", temporary = false)
        server.enqueue(AngaraDavFixtures.registered(registrationUrl))
        manager.reconcile()
        handler.onMessage("account-$accountId", AngaraDavFixtures.contentUpdateMessage(topic), decrypted = true)
        repository.suppressionUrlsFor(pairId)
        settings.update { it.copy(instantDownloadEnabled = false) }
        manager.reconcile()

        val log = DiagnosticLog.file(context).readText()
        assertTrue("flow should have logged", log.contains("Push message routed"))
        listOf("TOKENSECRET", "ENDPOINTSECRET", "PUBKEYSECRET", "AUTHSECRET", "TOPICSECRET1").forEach { secret ->
            assertFalse("log leaks $secret:\n$log", log.contains(secret))
        }
    }

    private class AlwaysReadyDistributor : PushDistributor {
        override fun ackDistributor(): String = "org.example.distributor"
        override fun savedDistributor(): String = "org.example.distributor"
        override fun distributors(): List<String> = listOf("org.example.distributor")
        override fun register(instance: String, messageForDistributor: String, vapid: String?): Boolean = true
        override fun unregister(instance: String) = Unit
        override fun options(): List<PushServiceOption> = emptyList()
        override fun select(packageName: String) = Unit
    }
}
