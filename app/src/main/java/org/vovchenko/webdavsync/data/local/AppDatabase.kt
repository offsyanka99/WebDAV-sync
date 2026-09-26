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
    version = 4,
    exportSchema = true,
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

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN lastRemoteScanAt INTEGER")
                db.execSQL("ALTER TABLE sync_file_state ADD COLUMN lastSyncedEtag TEXT")
                db.execSQL("ALTER TABLE sync_file_state ADD COLUMN isDirectory INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN lastCheapFingerprint INTEGER")
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN lastFullLocalScanAt INTEGER")
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN lastContentHashSweepAt INTEGER")
            }
        }
    }
}
