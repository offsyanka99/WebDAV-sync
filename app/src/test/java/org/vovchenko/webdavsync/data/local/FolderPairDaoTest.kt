package org.vovchenko.webdavsync.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Instrumented-style Room DAO test, run on the JVM via Robolectric instead of a device/emulator (plan Phase 10). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderPairDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertAccount(): Long =
        db.webDavAccountDao().insert(WebDavAccountEntity(displayName = "Test account", baseUrl = "https://example.com/dav/"))

    @Test
    fun `insert and read back a folder pair`() = runTest {
        val accountId = insertAccount()
        val id = db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Photos", remoteFolderPath = "/Photos", localFolderUri = "content://tree/photos"),
        )

        val stored = db.folderPairDao().observeById(id).first()
        assertEquals("Photos", stored?.name)
    }

    @Test
    fun `getEnabled excludes disabled pairs`() = runTest {
        val accountId = insertAccount()
        db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Enabled", remoteFolderPath = "/a", localFolderUri = "content://a", enabled = true),
        )
        db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Disabled", remoteFolderPath = "/b", localFolderUri = "content://b", enabled = false),
        )

        val enabled = db.folderPairDao().getEnabled()

        assertEquals(1, enabled.size)
        assertEquals("Enabled", enabled.first().name)
    }

    @Test
    fun `deleting an account cascades to its folder pairs`() = runTest {
        val accountId = insertAccount()
        val account = WebDavAccountEntity(id = accountId, displayName = "Test account", baseUrl = "https://example.com/dav/")
        db.folderPairDao().insert(
            FolderPairEntity(accountId = accountId, name = "Photos", remoteFolderPath = "/Photos", localFolderUri = "content://tree/photos"),
        )

        db.webDavAccountDao().delete(account)

        assertEquals(0, db.folderPairDao().getEnabled().size)
    }

    @Test
    fun `lastLocalFingerprint persists`() = runTest {
        val accountId = insertAccount()
        val id = db.folderPairDao().insert(
            FolderPairEntity(
                accountId = accountId,
                name = "Photos",
                remoteFolderPath = "/Photos",
                localFolderUri = "content://tree/photos",
                lastLocalFingerprint = 99L,
            ),
        )
        val stored = db.folderPairDao().observeById(id).first()
        assertEquals(99L, stored?.lastLocalFingerprint)
    }
}
