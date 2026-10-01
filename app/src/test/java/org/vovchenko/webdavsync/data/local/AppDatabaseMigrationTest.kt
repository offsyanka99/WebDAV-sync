package org.vovchenko.webdavsync.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Builds a v4 database from the exported `4.json`, then lets Room open it at v5. Room validates
 * every table against the v5 schema after [AppDatabase.MIGRATION_4_5] and throws on any drift.
 * (MigrationTestHelper cannot see test assets under Robolectric.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AppDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        context.deleteDatabase(TEST_DB)
    }

    @Test
    fun `migrate 4 to 5 keeps rows and adds push tables`() = runTest {
        createVersion4 { db ->
            db.execSQL("INSERT INTO webdav_accounts (id, displayName, baseUrl) VALUES (1, 'Cloud', 'https://example.com/dav/')")
            db.execSQL(
                "INSERT INTO folder_pairs (id, accountId, name, remoteFolderPath, localFolderUri, syncMethod, " +
                    "excludeHiddenFiles, excludedSubfolders, deleteEmptyFolders, instantUpload, enabled, lastRemoteScanAt) " +
                    "VALUES (7, 1, 'Photos', 'Photos', 'content://photos', 'TWO_WAY', 1, '', 0, 0, 1, 123)",
            )
        }

        val room = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(AppDatabase.MIGRATION_4_5)
            .allowMainThreadQueries()
            .build()
        try {
            val pair = room.folderPairDao().getEnabled().single()
            assertEquals(7L, pair.id)
            assertEquals("Photos", pair.name)
            assertEquals(123L, pair.lastRemoteScanAt)
            assertNull(pair.remoteChangePendingAt)
            assertTrue(room.pushDao().getRegistrations().isEmpty())
            assertNull(room.pushDao().getEndpoint(1))
            room.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'push_registrations'")
                .use { c ->
                    val names = buildList { while (c.moveToNext()) add(c.getString(0)) }
                    assertTrue(names.contains("index_push_registrations_accountId_remotePath"))
                    assertTrue(names.contains("index_push_registrations_accountId_topic"))
                }
        } finally {
            room.close()
        }
    }

    private fun createVersion4(seed: (SQLiteDatabase) -> Unit) {
        val schema = JSONObject(File(SCHEMA_DIR, "4.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(TEST_DB).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices") ?: continue
                for (j in 0 until indices.length()) {
                    db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.version = 4
            seed(db)
        }
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val SCHEMA_DIR = "schemas/org.vovchenko.webdavsync.data.local.AppDatabase"
    }
}
