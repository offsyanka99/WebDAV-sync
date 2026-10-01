package org.vovchenko.webdavsync.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.AppDatabase
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: PushRepository
    private var accountId = 0L

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = PushRepository(
            db.pushDao(),
            db,
            db.folderPairDao(),
            SettingsRepository(SettingsDataStore(ApplicationProvider.getApplicationContext())),
        )
        accountId = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "Cloud", baseUrl = "https://example.com/dav/"))
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `applyDesired inserts, keeps, and retires rows`() = runTest {
        val a = PushRegistrationKey(accountId, "Photos")
        val b = PushRegistrationKey(accountId, "Docs")
        assertTrue(repository.applyDesired(setOf(a, b)))
        assertFalse(repository.applyDesired(setOf(a, b)))
        assertEquals(setOf(PushRegistrationState.DISCOVER), repository.getRegistrations().map { it.state }.toSet())

        // A registered row goes PENDING_DELETE; an unregistered one is dropped.
        val photos = repository.getRegistrations().single { it.remotePath == "Photos" }
        repository.updateRegistration(photos.copy(state = PushRegistrationState.ACTIVE, registrationUrl = "https://example.com/r/1"))
        assertTrue(repository.applyDesired(emptySet()))
        val rows = repository.getRegistrations()
        assertEquals(listOf("Photos"), rows.map { it.remotePath })
        assertEquals(PushRegistrationState.PENDING_DELETE, rows.single().state)

        // Wanted again before the DELETE ran: back to DISCOVER, registration URL kept.
        assertTrue(repository.applyDesired(setOf(a)))
        val revived = repository.getRegistrations().single()
        assertEquals(PushRegistrationState.DISCOVER, revived.state)
        assertEquals("https://example.com/r/1", revived.registrationUrl)
    }

    @Test
    fun `active topic lookup is scoped to the account`() = runTest {
        val other = db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "Other", baseUrl = "https://other.example/dav/"))
        repository.applyDesired(setOf(PushRegistrationKey(accountId, "A"), PushRegistrationKey(other, "A")))
        repository.getRegistrations().forEach { row ->
            repository.updateRegistration(row.copy(state = PushRegistrationState.ACTIVE, topic = "same-topic"))
        }
        val found = repository.activeRegistrationsForTopics(accountId, listOf("same-topic", "unknown"))
        assertEquals(listOf(accountId), found.map { it.accountId })
        assertTrue(repository.activeRegistrationsForTopics(accountId, emptyList()).isEmpty())
    }

    @Test
    fun `account delete cascades push rows`() = runTest {
        repository.applyDesired(setOf(PushRegistrationKey(accountId, "A")))
        repository.upsertEndpoint(
            PushEndpointEntity(
                accountId = accountId,
                instance = PushEndpointEntity.instanceFor(accountId),
                state = PushEndpointState.READY,
                vapidPublicKey = "BVapid",
                endpointUrl = "https://push.example/x",
                updatedAt = 1L,
            ),
        )
        db.webDavAccountDao().delete(WebDavAccountEntity(id = accountId, displayName = "Cloud", baseUrl = "https://example.com/dav/"))
        assertTrue(repository.getRegistrations().isEmpty())
        assertEquals(null, repository.getEndpoint(accountId))
    }
}
