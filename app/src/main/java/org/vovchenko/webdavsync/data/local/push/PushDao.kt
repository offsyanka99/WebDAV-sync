package org.vovchenko.webdavsync.data.local.push

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PushDao {
    @Query("SELECT * FROM push_registrations ORDER BY accountId, remotePath")
    fun observeRegistrations(): Flow<List<PushRegistrationEntity>>

    @Query("SELECT * FROM push_registrations ORDER BY accountId, remotePath")
    suspend fun getRegistrations(): List<PushRegistrationEntity>

    @Query("SELECT * FROM push_registrations WHERE accountId = :accountId ORDER BY remotePath")
    suspend fun getRegistrationsForAccount(accountId: Long): List<PushRegistrationEntity>

    @Query("SELECT * FROM push_registrations WHERE id = :id")
    suspend fun getRegistration(id: Long): PushRegistrationEntity?

    @Query(
        "SELECT * FROM push_registrations " +
            "WHERE accountId = :accountId AND state = 'ACTIVE' AND topic IN (:topics)",
    )
    suspend fun getActiveByTopics(accountId: Long, topics: Collection<String>): List<PushRegistrationEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRegistration(registration: PushRegistrationEntity): Long

    @Update
    suspend fun updateRegistration(registration: PushRegistrationEntity)

    @Query("DELETE FROM push_registrations WHERE id = :id")
    suspend fun deleteRegistration(id: Long)

    @Query("DELETE FROM push_registrations WHERE id = :id AND state = 'PENDING_DELETE'")
    suspend fun deletePendingDelete(id: Long): Int

    @Query("UPDATE push_registrations SET nextAttemptAt = NULL")
    suspend fun clearBackoff()

    @Query(
        "UPDATE push_registrations SET state = :to " +
            "WHERE accountId = :accountId AND state = 'ACTIVE'",
    )
    suspend fun moveActive(accountId: Long, to: PushRegistrationState): Int

    @Query("SELECT * FROM push_endpoints ORDER BY accountId")
    fun observeEndpoints(): Flow<List<PushEndpointEntity>>

    @Query("SELECT * FROM push_endpoints ORDER BY accountId")
    suspend fun getEndpoints(): List<PushEndpointEntity>

    @Query("SELECT * FROM push_endpoints WHERE accountId = :accountId")
    suspend fun getEndpoint(accountId: Long): PushEndpointEntity?

    @Upsert
    suspend fun upsertEndpoint(endpoint: PushEndpointEntity)

    @Query("DELETE FROM push_endpoints WHERE accountId = :accountId")
    suspend fun deleteEndpoint(accountId: Long)

    @Query("DELETE FROM push_endpoints")
    suspend fun deleteAllEndpoints()
}
