package org.vovchenko.webdavsync.data.repository

import kotlinx.coroutines.flow.Flow
import org.vovchenko.webdavsync.data.local.SyncLogDao
import org.vovchenko.webdavsync.data.local.SyncLogEntity
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.domain.sync.RecentChangesCalculator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncLogRepository @Inject constructor(
    private val dao: SyncLogDao,
) {
    fun observeForFolderPair(folderPairId: Long): Flow<List<SyncLogEntity>> = dao.observeForFolderPair(folderPairId)

    fun observeRecent(limit: Int = 100): Flow<List<SyncLogEntity>> = dao.observeRecent(limit)

    suspend fun log(entry: SyncLogEntity): Long = dao.insert(entry)

    suspend fun pruneOlderThan(timestampMillis: Long) = dao.deleteOlderThan(timestampMillis)

    /**
     * Writes one complete counter batch for the whole worker pass (all folder pairs).
     * Overview / widget read this as "Recent changes" so multi-pair totals are not lost.
     */
    suspend fun logSessionSummary(
        folderPairId: Long,
        uploaded: Int,
        downloaded: Int,
        deletedLocal: Int,
        deletedRemote: Int,
    ) {
        val timestamp = System.currentTimeMillis()
        // Always write all four types (including zeros) so a quiet pass clears prior UI counts.
        log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.UPLOAD,
                fileCount = uploaded,
            ),
        )
        log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.DOWNLOAD,
                fileCount = downloaded,
            ),
        )
        log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.DELETE_DEVICE,
                fileCount = deletedLocal,
            ),
        )
        log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.DELETE_CLOUD,
                fileCount = deletedRemote,
            ),
        )
        val total = uploaded + downloaded + deletedLocal + deletedRemote
        log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.SYNC_END,
                fileCount = total,
                message = RecentChangesCalculator.SESSION_MESSAGE,
            ),
        )
    }
}
