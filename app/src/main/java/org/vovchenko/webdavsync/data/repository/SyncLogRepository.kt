package org.vovchenko.webdavsync.data.repository

import kotlinx.coroutines.flow.Flow
import org.vovchenko.webdavsync.data.local.SyncLogDao
import org.vovchenko.webdavsync.data.local.SyncLogEntity
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
}
