package org.vovchenko.webdavsync.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.AppDatabase
import org.vovchenko.webdavsync.data.local.FolderPairDao
import org.vovchenko.webdavsync.data.local.push.PushDao
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.push.PushRegistrationPlanner
import javax.inject.Inject
import javax.inject.Singleton

/** One desired server subscription: an account and a sanitized remote root. */
data class PushRegistrationKey(val accountId: Long, val remotePath: String)

/** Persistence and routing for WebDAV-Push registrations and UnifiedPush endpoints. No network I/O. */
@Singleton
class PushRepository @Inject constructor(
    private val dao: PushDao,
    private val database: AppDatabase,
    private val folderPairDao: FolderPairDao,
    private val settingsRepository: SettingsRepository,
) {
    fun observeRegistrations(): Flow<List<PushRegistrationEntity>> = dao.observeRegistrations()

    fun observeEndpoints(): Flow<List<PushEndpointEntity>> = dao.observeEndpoints()

    suspend fun getRegistrations(): List<PushRegistrationEntity> = dao.getRegistrations()

    suspend fun getRegistrationsForAccount(accountId: Long): List<PushRegistrationEntity> =
        dao.getRegistrationsForAccount(accountId)

    suspend fun getRegistration(id: Long): PushRegistrationEntity? = dao.getRegistration(id)

    suspend fun updateRegistration(registration: PushRegistrationEntity) = dao.updateRegistration(registration)

    suspend fun deleteRegistration(id: Long) = dao.deleteRegistration(id)

    suspend fun activeRegistrationsForTopics(accountId: Long, topics: Collection<String>): List<PushRegistrationEntity> =
        if (topics.isEmpty()) emptyList() else dao.getActiveByTopics(accountId, topics)

    suspend fun getEndpoint(accountId: Long): PushEndpointEntity? = dao.getEndpoint(accountId)

    suspend fun getEndpoints(): List<PushEndpointEntity> = dao.getEndpoints()

    suspend fun upsertEndpoint(endpoint: PushEndpointEntity) = dao.upsertEndpoint(endpoint)

    suspend fun deleteEndpoint(accountId: Long) = dao.deleteEndpoint(accountId)

    /** After a distributor is (re)chosen: every account requests a fresh endpoint, skipping old backoffs. */
    suspend fun clearEndpoints() = dao.deleteAllEndpoints()

    /** The user asked to try again: failed, unsupported, and waiting rows are retried on the next run. */
    suspend fun clearBackoff() = dao.clearBackoff()

    /** Desired registrations from the current pairs and settings. */
    suspend fun desired(): Map<PushRegistrationKey, Set<Long>> =
        PushRegistrationPlanner.desired(folderPairDao.getEnabled(), settingsRepository.settings.first())

    /** `Push-Dont-Notify` URLs for [pairId]; empty (and no DB reads) while the feature is off. */
    suspend fun suppressionUrlsFor(pairId: Long): List<String> {
        if (!settingsRepository.settings.first().instantDownloadEnabled) return emptyList()
        return PushRegistrationPlanner.suppressionUrls(pairId, dao.getRegistrations(), desired())
    }

    /** Pairs served by ACTIVE registrations of [accountId] with one of [topics]. */
    suspend fun pairIdsForTopics(accountId: Long, topics: Collection<String>): Set<Long> {
        val rows = activeRegistrationsForTopics(accountId, topics)
        if (rows.isEmpty()) return emptySet()
        val desired = desired()
        return rows.flatMap { desired[PushRegistrationPlanner.keyOf(it)].orEmpty() }.toSet()
    }

    /**
     * Writes [after] only if the row is still in [before]'s state. Coordinator and push callbacks
     * change states concurrently with network work; a stale write must not undo them.
     */
    suspend fun updateIfUnchanged(before: PushRegistrationEntity, after: PushRegistrationEntity): Boolean =
        database.withTransaction {
            val current = dao.getRegistration(before.id)
            if (current == null || current.state != before.state) {
                false
            } else {
                dao.updateRegistration(after)
                true
            }
        }

    /** Drops the row unless it was revived since it was read. */
    suspend fun deleteIfPendingDelete(id: Long): Boolean = dao.deletePendingDelete(id) > 0

    /** The endpoint is gone: ACTIVE rows must register again once a new one arrives. */
    suspend fun markEndpointLost(accountId: Long) =
        dao.moveActive(accountId, PushRegistrationState.WAITING_FOR_ENDPOINT)

    /** The server's VAPID key changed: re-read capabilities, then re-request the endpoint. */
    suspend fun markForRediscovery(accountId: Long) =
        dao.moveActive(accountId, PushRegistrationState.DISCOVER)

    /**
     * Makes the rows match [desired]: new keys start at DISCOVER, revived keys leave
     * PENDING_DELETE, and surplus keys become PENDING_DELETE (or are dropped outright when the
     * server never issued a registration URL). Returns true when any row changed.
     */
    suspend fun applyDesired(desired: Set<PushRegistrationKey>): Boolean = database.withTransaction {
        var changed = false
        val existing = dao.getRegistrations()
        val existingKeys = HashSet<PushRegistrationKey>()
        for (row in existing) {
            val key = PushRegistrationKey(row.accountId, row.remotePath)
            existingKeys += key
            val wanted = key in desired
            when {
                wanted && row.state == PushRegistrationState.PENDING_DELETE -> {
                    dao.updateRegistration(row.copy(state = PushRegistrationState.DISCOVER, lastError = null, nextAttemptAt = null))
                    changed = true
                }
                !wanted && row.state != PushRegistrationState.PENDING_DELETE -> {
                    if (row.registrationUrl == null) {
                        dao.deleteRegistration(row.id)
                    } else {
                        dao.updateRegistration(
                            row.copy(state = PushRegistrationState.PENDING_DELETE, lastError = null, nextAttemptAt = null),
                        )
                    }
                    changed = true
                }
            }
        }
        for (key in desired - existingKeys) {
            dao.insertRegistration(
                PushRegistrationEntity(
                    accountId = key.accountId,
                    remotePath = key.remotePath,
                    state = PushRegistrationState.DISCOVER,
                ),
            )
            changed = true
        }
        changed
    }
}
