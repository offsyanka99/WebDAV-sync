package org.vovchenko.webdavsync.domain.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncFileStateRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FolderPairEditorTest {

    private lateinit var db: AppDatabase
    private lateinit var editor: FolderPairEditor

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        editor = FolderPairEditor(
            FolderPairRepository(db.folderPairDao(), db),
            SyncFileStateRepository(db.syncFileStateDao()),
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `changing the local folder clears the baseline`() = runTest {
        val accountId = db.webDavAccountDao().insert(
            WebDavAccountEntity(displayName = "Cloud", baseUrl = "https://example.com/dav"),
        )
        val pair = FolderPairEntity(
            accountId = accountId,
            name = "Photos",
            remoteFolderPath = "Photos",
            localFolderUri = "content://old",
        )
        val id = db.folderPairDao().insert(pair)
        db.syncFileStateDao().upsert(
            SyncFileStateEntity(
                folderPairId = id,
                relativePath = "a.jpg",
                lastSyncedMtime = 1,
                lastSyncedSize = 10,
            ),
        )
        val existing = db.folderPairDao().observeById(id).first()!!
        editor.save(existing, existing.copy(localFolderUri = "content://new"))
        assertTrue(db.syncFileStateDao().getForFolderPair(id).isEmpty())
        assertEquals("content://new", db.folderPairDao().observeById(id).first()!!.localFolderUri)
    }
}
