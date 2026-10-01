package org.vovchenko.webdavsync.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderPairDao {
    @Query("SELECT * FROM folder_pairs ORDER BY name")
    fun observeAll(): Flow<List<FolderPairEntity>>

    @Query("SELECT * FROM folder_pairs WHERE accountId = :accountId ORDER BY name")
    fun observeByAccount(accountId: Long): Flow<List<FolderPairEntity>>

    @Query("SELECT * FROM folder_pairs WHERE id = :id")
    fun observeById(id: Long): Flow<FolderPairEntity?>

    @Query("SELECT * FROM folder_pairs WHERE enabled = 1")
    suspend fun getEnabled(): List<FolderPairEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(folderPair: FolderPairEntity): Long

    @Update
    suspend fun update(folderPair: FolderPairEntity)

    @Delete
    suspend fun delete(folderPair: FolderPairEntity)

    @Query("DELETE FROM folder_pairs")
    suspend fun deleteAll()

    @Query("SELECT remoteChangePendingAt FROM folder_pairs WHERE id = :id")
    suspend fun getRemoteChangePendingAt(id: Long): Long?

    @Query("UPDATE folder_pairs SET remoteChangePendingAt = :at WHERE id IN (:ids)")
    suspend fun markRemoteChangePending(ids: Collection<Long>, at: Long)

    /** Clears only pushes that arrived before the remote walk started. */
    @Query(
        "UPDATE folder_pairs SET remoteChangePendingAt = NULL " +
            "WHERE id = :id AND remoteChangePendingAt <= :scanStartedAt",
    )
    suspend fun clearRemoteChangePending(id: Long, scanStartedAt: Long)

    @Query("SELECT * FROM folder_pairs WHERE remoteChangePendingAt IS NOT NULL AND enabled = 1")
    suspend fun getRemoteChangePending(): List<FolderPairEntity>
}
