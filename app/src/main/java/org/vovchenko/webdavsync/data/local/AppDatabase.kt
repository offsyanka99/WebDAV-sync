package org.vovchenko.webdavsync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.vovchenko.webdavsync.data.local.push.PushDao
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity

@Database(
    entities = [
        WebDavAccountEntity::class,
        FolderPairEntity::class,
        SyncFileStateEntity::class,
        SyncLogEntity::class,
        PushRegistrationEntity::class,
        PushEndpointEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun webDavAccountDao(): WebDavAccountDao
    abstract fun folderPairDao(): FolderPairDao
    abstract fun syncFileStateDao(): SyncFileStateDao
    abstract fun syncLogDao(): SyncLogDao
    abstract fun pushDao(): PushDao

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

        // CREATE statements copied from schemas/…/5.json.
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE folder_pairs ADD COLUMN remoteChangePendingAt INTEGER")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `push_registrations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`accountId` INTEGER NOT NULL, `remotePath` TEXT NOT NULL, `state` TEXT NOT NULL, `topic` TEXT, " +
                        "`advertisedDepth` TEXT, `vapidPublicKey` TEXT, `registrationUrl` TEXT, `endpointUrl` TEXT, " +
                        "`expiresAt` INTEGER, `registeredAt` INTEGER, `lastError` TEXT, `nextAttemptAt` INTEGER, " +
                        "`lastCheckedAt` INTEGER, " +
                        "FOREIGN KEY(`accountId`) REFERENCES `webdav_accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_push_registrations_accountId_remotePath` " +
                        "ON `push_registrations` (`accountId`, `remotePath`)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_push_registrations_accountId_topic` " +
                        "ON `push_registrations` (`accountId`, `topic`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `push_endpoints` (`accountId` INTEGER NOT NULL, `instance` TEXT NOT NULL, " +
                        "`state` TEXT NOT NULL, `vapidPublicKey` TEXT, `endpointUrl` TEXT, `pubKey` TEXT, `authSecret` TEXT, " +
                        "`temporary` INTEGER NOT NULL, `lastError` TEXT, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`), " +
                        "FOREIGN KEY(`accountId`) REFERENCES `webdav_accounts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }
    }
}
