package org.vovchenko.webdavsync.data.repository

import kotlinx.coroutines.flow.Flow
import org.vovchenko.webdavsync.data.local.FolderPairDao
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderPairRepository @Inject constructor(
    private val dao: FolderPairDao,
) {
    fun observeAll(): Flow<List<FolderPairEntity>> = dao.observeAll()

    fun observeByAccount(accountId: Long): Flow<List<FolderPairEntity>> = dao.observeByAccount(accountId)

    fun observeById(id: Long): Flow<FolderPairEntity?> = dao.observeById(id)

    suspend fun getEnabled(): List<FolderPairEntity> = dao.getEnabled()

    suspend fun add(folderPair: FolderPairEntity): Long = dao.insert(folderPair)

    suspend fun update(folderPair: FolderPairEntity) = dao.update(folderPair)

    suspend fun delete(folderPair: FolderPairEntity) = dao.delete(folderPair)

    /** Removes every folder pair (cascades sync state/log rows). */
    suspend fun deleteAll() = dao.deleteAll()
}
