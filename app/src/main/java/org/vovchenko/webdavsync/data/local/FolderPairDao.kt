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
}
