package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.saf.PathFilters
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncFileStateRepository
import javax.inject.Inject

/** Decides when a folder-pair edit invalidates the three-way baseline. */
object FolderPairEditPolicy {
    fun resetsBaseline(previous: FolderPairEntity, updated: FolderPairEntity): Boolean =
        previous.accountId != updated.accountId ||
            previous.localFolderUri != updated.localFolderUri ||
            previous.remoteFolderPath != updated.remoteFolderPath ||
            previous.syncMethod != updated.syncMethod

    fun forSave(previous: FolderPairEntity, draft: FolderPairEntity): FolderPairEntity {
        val reset = resetsBaseline(previous, draft)
        return draft.copy(
            lastSyncAt = if (reset) null else previous.lastSyncAt,
            lastSyncDurationMs = if (reset) null else previous.lastSyncDurationMs,
            lastSyncStatus = if (reset) null else previous.lastSyncStatus,
            lastLocalFingerprint = if (reset) null else previous.lastLocalFingerprint,
            lastRemoteScanAt = if (reset) null else previous.lastRemoteScanAt,
            lastCheapFingerprint = if (reset) null else previous.lastCheapFingerprint,
            lastFullLocalScanAt = if (reset) null else previous.lastFullLocalScanAt,
            lastContentHashSweepAt = if (reset) null else previous.lastContentHashSweepAt,
        )
    }
}

/**
 * Persists a folder-pair edit and drops baseline rows that would otherwise be
 * interpreted as deletions on the next pass.
 */
class FolderPairEditor @Inject constructor(
    private val folderPairRepository: FolderPairRepository,
    private val syncFileStateRepository: SyncFileStateRepository,
) {
    suspend fun save(previous: FolderPairEntity?, draft: FolderPairEntity) {
        if (previous == null) {
            folderPairRepository.add(draft)
            return
        }
        val persisted = FolderPairEditPolicy.forSave(previous, draft)
        if (FolderPairEditPolicy.resetsBaseline(previous, draft)) {
            syncFileStateRepository.deleteForFolderPair(previous.id)
        } else if (draft.excludeHiddenFiles && !previous.excludeHiddenFiles) {
            val hidden = syncFileStateRepository.getForFolderPair(previous.id)
                .filter { PathFilters.isHidden(it.relativePath) }
            hidden.forEach { row ->
                syncFileStateRepository.deleteForPath(previous.id, row.relativePath)
            }
        }
        folderPairRepository.update(persisted)
    }
}
