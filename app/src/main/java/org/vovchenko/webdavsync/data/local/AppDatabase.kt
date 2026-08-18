package org.vovchenko.webdavsync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        WebDavAccountEntity::class,
        FolderPairEntity::class,
        SyncFileStateEntity::class,
        SyncLogEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webDavAccountDao(): WebDavAccountDao
    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncFileStateDao(): SyncFileStateDao
    abstract fun syncLogDao(): SyncLogDao

    companion object {
        const val DATABASE_NAME = "webdav_sync.db"

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN lastLocalFingerprint INTEGER")
            }
        }
    }
}
