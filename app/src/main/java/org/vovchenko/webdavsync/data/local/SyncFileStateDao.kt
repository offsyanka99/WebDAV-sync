package org.vovchenko.webdavsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncFileStateDao {
    @Query("SELECT * FROM sync_file_state WHERE folderPairId = :folderPairId")
    suspend fun getForFolderPair(folderPairId: Long): List<SyncFileStateEntity>

    @Query("SELECT * FROM sync_file_state WHERE folderPairId = :folderPairId AND relativePath = :relativePath")
    suspend fun getForPath(folderPairId: Long, relativePath: String): SyncFileStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: SyncFileStateEntity)

    @Query("DELETE FROM sync_file_state WHERE folderPairId = :folderPairId AND relativePath = :relativePath")
    suspend fun deleteForPath(folderPairId: Long, relativePath: String)

    @Query("DELETE FROM sync_file_state WHERE folderPairId = :folderPairId")
    suspend fun deleteForFolderPair(folderPairId: Long)
}
