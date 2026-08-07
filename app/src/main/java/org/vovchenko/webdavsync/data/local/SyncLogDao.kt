package org.vovchenko.webdavsync.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncLogDao {
    @Query("SELECT * FROM sync_log WHERE folderPairId = :folderPairId ORDER BY timestamp DESC, id DESC")
    fun observeForFolderPair(folderPairId: Long): Flow<List<SyncLogEntity>>

    // id DESC breaks ties when several rows share one millisecond (common in logOutcome batches).
    @Query("SELECT * FROM sync_log ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SyncLogEntity>>

    @Insert
    suspend fun insert(entry: SyncLogEntity): Long

    @Query("DELETE FROM sync_log WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
