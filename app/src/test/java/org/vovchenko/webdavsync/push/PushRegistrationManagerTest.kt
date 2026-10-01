package org.vovchenko.webdavsync.push

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.data.remote.SardineWebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.remote.WebDavClientSession
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushRegistrationManagerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var repository: PushRepository
    private lateinit var distributor: FakeDistributor
    private lateinit var accounts: FakeAccounts
    private lateinit var manager: PushRegistrationManager
    private var accountId = 0L

    private val registrationUrl = AngaraDavFixtures.registrationUrl()
    private val vapid = AngaraDavFixtures.VAPID

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer().apply { start() }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        settings = SettingsRepository(SettingsDataStore(context))
        settings.update { it.copy(instantDownloadEnabled = true) }
        repository = PushRepository(db.pushDao(), db, db.folderPairDao(), settings)
        distributor = FakeDistributor()
        val http = OkHttpClient.Builder().followRedirects(false).build()
        accounts = FakeAccounts(SardineWebDavClient(http, http, server.url("/dav/").toString(), File("build/tmp")))
        manager = PushRegistrationManager(
            repository,
            accounts,
            WebDavClientFactory(context, InFlightCallRegistry()),
            distributor,
            settings,
            DiagnosticLogger(context, settings, CoroutineScope(Dispatchers.Unconfined)),
        )
        accountId = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "Cloud", baseUrl = server.url("/dav/").toString()))
        db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Sync", remoteFolderPath = "Sync", localFolderUri = "content://sync"),
        )
        Unit
    }

    @After
    fun tearDown() = runBlocking {
        server.shutdown()
        db.close()
        settings.update { it.copy(instantDownloadEnabled = false) }
    }

    @Test
    fun `discover, request endpoint, then register`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        manager.reconcile()

        val discovered = repository.getRegistrations().single()
        assertEquals(PushRegistrationState.WAITING_FOR_ENDPOINT, discovered.state)
        assertEquals("topic-1", discovered.topic)
        assertEquals("PROPFIND", server.takeRequest().method)
        assertEquals(listOf("account-$accountId" to vapid), distributor.registered)
        assertEquals(PushEndpointState.REQUESTED, repository.getEndpoint(accountId)!!.state)

        readyEndpoint("https://push.example/up/1")
        server.enqueue(registered())
        manager.reconcile()

        val active = repository.getRegistrations().single()
        assertEquals(PushRegistrationState.ACTIVE, active.state)
        assertEquals(registrationUrl, active.registrationUrl)
        assertEquals("https://push.example/up/1", active.endpointUrl)
        val post = server.takeRequest()
        assertEquals("POST", post.method)
        assertEquals("/dav/Sync/", post.path)
        assertTrue(post.body.readUtf8().contains("<P:push-resource>https://push.example/up/1</P:push-resource>"))

        // Nothing due: no network at all.
        manager.reconcile()
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `missing root waits and is re-discovered after the pair syncs`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        manager.reconcile()
        assertEquals(PushRegistrationState.WAITING_FOR_ROOT, repository.getRegistrations().single().state)

        // Not due yet: a reconcile alone does not PROPFIND again.
        manager.reconcile()
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        val pairId = db.folderPairDao().getEnabled().single().id
        WebDavClientFactory(context, InFlightCallRegistry()).openSession().use { session ->
            manager.afterSyncPass(setOf(accountId), listOf(pairId), session)
        }
        assertEquals(PushRegistrationState.WAITING_FOR_ENDPOINT, repository.getRegistrations().single().state)
    }

    @Test
    fun `rate limit stops the account and records Retry-After`() = runTest {
        db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Docs", remoteFolderPath = "Docs", localFolderUri = "content://docs"),
        )
        repository.applyDesired(repository.desired().keys)
        repository.getRegistrations().forEach {
            repository.updateRegistration(it.copy(state = PushRegistrationState.WAITING_FOR_ENDPOINT, topic = "t", vapidPublicKey = vapid))
        }
        readyEndpoint("https://push.example/up/1")
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))

        val before = System.currentTimeMillis()
        manager.reconcile()

        assertEquals(1, server.requestCount)
        val failed = repository.getRegistrations().single { it.state == PushRegistrationState.FAILED }
        assertEquals(PushRegistrationManager.ERR_RATE_LIMITED, failed.lastError)
        assertTrue(failed.nextAttemptAt!! >= before + 120_000)
        assertEquals(1, repository.getRegistrations().count { it.state == PushRegistrationState.WAITING_FOR_ENDPOINT })
    }

    @Test
    fun `new endpoint URL replaces the registration`() = runTest {
        repository.applyDesired(repository.desired().keys)
        val row = repository.getRegistrations().single()
        val now = System.currentTimeMillis()
        repository.updateRegistration(
            row.copy(
                state = PushRegistrationState.ACTIVE,
                topic = "t",
                vapidPublicKey = vapid,
                registrationUrl = registrationUrl,
                endpointUrl = "https://push.example/old",
                registeredAt = now,
                expiresAt = now + 7L * 24 * 60 * 60 * 1000,
            ),
        )
        readyEndpoint("https://push.example/new")
        server.enqueue(registered())

        manager.reconcile()

        // The old registration is on another origin than the account, so no DELETE was sent.
        assertEquals(1, server.requestCount)
        assertEquals("POST", server.takeRequest().method)
        assertEquals("https://push.example/new", repository.getRegistrations().single().endpointUrl)
    }

    @Test
    fun `switching off retires rows and releases the distributor instance`() = runTest {
        repository.applyDesired(repository.desired().keys)
        val row = repository.getRegistrations().single()
        repository.updateRegistration(row.copy(state = PushRegistrationState.ACTIVE, registrationUrl = registrationUrl))
        readyEndpoint("https://push.example/up/1")

        settings.update { it.copy(instantDownloadEnabled = false) }
        manager.reconcile()

        assertTrue(repository.getRegistrations().isEmpty())
        assertNull(repository.getEndpoint(accountId))
        assertEquals(listOf("account-$accountId"), distributor.unregistered)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `no saved distributor fails the endpoint without registering`() = runTest {
        distributor.saved = null
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus()))
        manager.reconcile()

        val endpoint = repository.getEndpoint(accountId)!!
        assertEquals(PushEndpointState.FAILED, endpoint.state)
        assertEquals(PushRegistrationManager.ERR_NO_DISTRIBUTOR, endpoint.lastError)
        assertTrue(distributor.registered.isEmpty())
    }

    @Test
    fun `at most ten new registrations per run`() = runTest {
        repeat(11) { i ->
            db.folderPairDao().insert(
                FolderPairEntity(accountId = accountId, name = "F$i", remoteFolderPath = "F$i", localFolderUri = "content://f$i"),
            )
        }
        repository.applyDesired(repository.desired().keys)
        repository.getRegistrations().forEach {
            repository.updateRegistration(it.copy(state = PushRegistrationState.WAITING_FOR_ENDPOINT, topic = "t", vapidPublicKey = vapid))
        }
        readyEndpoint("https://push.example/up/1")
        repeat(12) { server.enqueue(registered()) }

        manager.reconcile()

        assertEquals(PushRegistrationPlanner.MAX_NEW_REGISTRATIONS_PER_RUN, server.requestCount)
        assertEquals(10, repository.getRegistrations().count { it.state == PushRegistrationState.ACTIVE })
        assertEquals(2, repository.getRegistrations().count { it.state == PushRegistrationState.WAITING_FOR_ENDPOINT })
    }

    @Test
    fun `server without file push leaves the folder unsupported`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(AngaraDavFixtures.multistatusWithoutPush()))
        manager.reconcile()

        val row = repository.getRegistrations().single()
        assertEquals(PushRegistrationState.UNSUPPORTED, row.state)
        assertTrue(row.nextAttemptAt!! > System.currentTimeMillis() + PushRegistrationPlanner.UNSUPPORTED_RECHECK_MS - 60_000)
        assertTrue(distributor.registered.isEmpty())
    }

    @Test
    fun `push-not-available on register marks the folder unsupported`() = runTest {
        repository.applyDesired(repository.desired().keys)
        val row = repository.getRegistrations().single()
        repository.updateRegistration(row.copy(state = PushRegistrationState.WAITING_FOR_ENDPOINT, topic = "t", vapidPublicKey = vapid))
        readyEndpoint("https://push.example/up/1")
        server.enqueue(AngaraDavFixtures.rejected("push-not-available"))

        manager.reconcile()

        assertEquals(PushRegistrationState.UNSUPPORTED, repository.getRegistrations().single().state)
    }

    @Test
    fun `failures name the HTTP status or the failing check`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        manager.reconcile()
        assertEquals("Server error (HTTP 500)", repository.getRegistrations().single().lastError)

        val row = repository.getRegistrations().single()
        repository.updateRegistration(row.copy(state = PushRegistrationState.WAITING_FOR_ENDPOINT, nextAttemptAt = null, topic = "t", vapidPublicKey = vapid))
        readyEndpoint("https://push.example/up/1")
        // A server with an http push_external_url hands out an http Location.
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Location", "http://dav.example.com/push-subscriptions/x"))
        manager.reconcile()
        val failed = repository.getRegistrations().single()
        assertEquals(PushRegistrationState.FAILED, failed.state)
        assertEquals(PushRegistrationManager.ERR_NO_LOCATION, failed.lastError)
    }

    @Test
    fun `cancellation propagates`() = runTest {
        accounts.cancel = true
        try {
            manager.reconcile()
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("stop", e.message)
        }
    }

    private suspend fun readyEndpoint(url: String) {
        repository.upsertEndpoint(
            PushEndpointEntity(
                accountId = accountId,
                instance = "account-$accountId",
                state = PushEndpointState.READY,
                vapidPublicKey = vapid,
                endpointUrl = url,
                pubKey = "BPub",
                authSecret = "Auth",
                updatedAt = System.currentTimeMillis() - 60_000,
            ),
        )
    }

    private fun registered() = AngaraDavFixtures.registered(registrationUrl)

    private fun multistatus() = AngaraDavFixtures.multistatus("topic-1")

    private class FakeDistributor : PushDistributor {
        var saved: String? = "org.example.distributor"
        val registered = mutableListOf<Pair<String, String?>>()
        val unregistered = mutableListOf<String>()
        override fun ackDistributor(): String? = saved
        override fun savedDistributor(): String? = saved
        override fun distributors(): List<String> = listOfNotNull(saved)
        override fun register(instance: String, messageForDistributor: String, vapid: String?): Boolean {
            registered += instance to vapid
            return true
        }
        override fun unregister(instance: String) {
            unregistered += instance
        }
        override fun options(): List<PushServiceOption> =
            listOfNotNull(saved?.let { PushServiceOption(it, it, isGooglePlay = false) })
        override fun select(packageName: String) {
            saved = packageName
        }
    }

    private class FakeAccounts(private val client: WebDavClient) : PushAccountClients {
        var cancel = false
        override suspend fun displayName(accountId: Long): String = "Cloud"
        override suspend fun clientFor(session: WebDavClientSession, accountId: Long): WebDavClient {
            if (cancel) throw CancellationException("stop")
            return client
        }
    }
}
