package org.vovchenko.webdavsync.data.repository

import org.vovchenko.webdavsync.data.local.SyncFileStateDao
import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Per-file three-way sync baseline (plan §4.2, §3), read/written once per sync pass. */
@Singleton
class SyncFileStateRepository @Inject constructor(
    private val dao: SyncFileStateDao,
) {
    suspend fun getForFolderPair(folderPairId: Long): List<SyncFileStateEntity> = dao.getForFolderPair(folderPairId)

    suspend fun upsert(state: SyncFileStateEntity) = dao.upsert(state)

    suspend fun deleteForPath(folderPairId: Long, relativePath: String) = dao.deleteForPath(folderPairId, relativePath)

    suspend fun deleteForFolderPair(folderPairId: Long) = dao.deleteForFolderPair(folderPairId)
}
