package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.local.saf.LocalNameSanitizer
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.domain.model.SyncAction

/**
 * SAF providers that always report mtime 0 hide same-size edits from the diff.
 * When a stored content hash disagrees with a fresh hash, emit a real transfer.
 */
object ZeroMtimeReconciler {
    fun apply(
        actions: List<SyncAction>,
        syncMethod: SyncMethod,
        localEntries: List<LocalFileEntry>,
        remoteEntries: List<RemoteFileEntry>,
        baseline: List<SyncFileStateEntity>,
        hashOf: (relativePath: String) -> String?,
    ): List<SyncAction> {
        val localByPath = localEntries.filterNot { it.isDirectory }.associateBy { it.relativePath }
        val remoteByPath = remoteEntries.filterNot { it.isDirectory }
            .associateBy { LocalNameSanitizer.sanitizeRelativePath(it.relativePath) }
        val baselineByPath = baseline.filterNot { it.isDirectory }.associateBy { it.relativePath }
        val result = actions.toMutableList()

        for ((path, local) in localByPath) {
            if (local.lastModifiedEpochMillis != 0L) continue
            val row = baselineByPath[path] ?: continue
            if (row.lastSyncedMtime != 0L || row.lastSyncedSize != local.sizeBytes) continue
            val storedHash = row.lastSyncedHash ?: continue
            val existingIndex = result.indexOfFirst { it.relativePath == path }
            if (existingIndex >= 0 && result[existingIndex] !is SyncAction.RememberInSync) continue
            val liveHash = hashOf(path) ?: continue
            if (liveHash == storedHash) continue
            val remote = remoteByPath[path]
            val replacement: SyncAction = when (syncMethod) {
                SyncMethod.TO_DEVICE -> SyncAction.DownloadFile(
                    relativePath = path,
                    remoteSizeBytes = remote?.sizeBytes ?: 0L,
                    remoteRelativePath = remote?.relativePath ?: path,
                    remoteEtag = remote?.etag,
                )
                SyncMethod.TWO_WAY, SyncMethod.TO_CLOUD -> SyncAction.UploadFile(
                    relativePath = path,
                    remoteRelativePath = remote?.relativePath ?: path,
                )
            }
            if (existingIndex >= 0) result[existingIndex] = replacement else result += replacement
        }
        return result
    }
}
