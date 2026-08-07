package org.vovchenko.webdavsync.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WebDavAccountDao {
    @Query("SELECT * FROM webdav_accounts ORDER BY displayName")
    fun observeAll(): Flow<List<WebDavAccountEntity>>

    @Query("SELECT * FROM webdav_accounts WHERE id = :id")
    fun observeById(id: Long): Flow<WebDavAccountEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: WebDavAccountEntity): Long

    @Update
    suspend fun update(account: WebDavAccountEntity)

    @Delete
    suspend fun delete(account: WebDavAccountEntity)

    @Query("SELECT COUNT(*) FROM folder_pairs WHERE accountId = :accountId")
    suspend fun folderPairCountForAccount(accountId: Long): Int
}
