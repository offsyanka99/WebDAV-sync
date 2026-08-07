package org.vovchenko.webdavsync.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        WebDavAccountEntity::class,
        FolderPairEntity::class,
        SyncFileStateEntity::class,
        SyncLogEntity::class,
    ],
    version = 1,
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
    }
}
