package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.domain.model.SyncAction

/** How a file changed since the last synced baseline. */
private enum class ChangeState { UNCHANGED, NEW, MODIFIED, DELETED }

/** mtimes only need to be "close enough" — SAF/WebDAV timestamp precision differs across providers. */
private const val MTIME_TOLERANCE_MS = 2000L

private fun mtimesClose(a: Long?, b: Long?): Boolean {
    if (a == null || b == null) return a == b
    return kotlin.math.abs(a - b) <= MTIME_TOLERANCE_MS
}

private fun localState(local: LocalFileEntry?, baseline: SyncFileStateEntity?): ChangeState = when {
    baseline == null -> if (local != null) ChangeState.NEW else ChangeState.UNCHANGED
    local == null -> ChangeState.DELETED
    local.sizeBytes != baseline.lastSyncedSize || !mtimesClose(local.lastModifiedEpochMillis, baseline.lastSyncedMtime) ->
        ChangeState.MODIFIED
    else -> ChangeState.UNCHANGED
}

/**
 * Remote change detection. Size is the stable signal. A stored ETag that disagrees with the
 * live ETag is also a modification, including when the size stayed the same.
 * Missing ETags on either side are not treated as a change (first seed fills the column).
 */
private fun remoteState(remote: RemoteFileEntry?, baseline: SyncFileStateEntity?): ChangeState = when {
    baseline == null -> if (remote != null) ChangeState.NEW else ChangeState.UNCHANGED
    remote == null -> ChangeState.DELETED
    remote.sizeBytes != baseline.lastSyncedSize -> ChangeState.MODIFIED
    etagDiffers(remote, baseline) -> ChangeState.MODIFIED
    else -> ChangeState.UNCHANGED
}

private fun etagDiffers(remote: RemoteFileEntry, baseline: SyncFileStateEntity): Boolean {
    val live = remote.etag?.takeIf { it.isNotBlank() } ?: return false
    val stored = baseline.lastSyncedEtag?.takeIf { it.isNotBlank() } ?: return false
    return live != stored
}

/** Picks the "winner" for a true conflict — the more recently modified side wins, ties go to local. */
private fun pickWinner(local: LocalFileEntry?, remote: RemoteFileEntry?): SyncAction.Side {
    val localMtime = local?.lastModifiedEpochMillis ?: return SyncAction.Side.REMOTE
    val remoteMtime = remote?.lastModifiedEpochMillis ?: return SyncAction.Side.LOCAL
    return if (localMtime >= remoteMtime) SyncAction.Side.LOCAL else SyncAction.Side.REMOTE
}

/** True for paths we already minted as conflict copies — never nest another layer. */
internal fun isConflictedCopyPath(relativePath: String): Boolean =
    relativePath.substringAfterLast('/').contains(CONFLICTED_COPY_MARKER, ignoreCase = false)

private const val CONFLICTED_COPY_MARKER = "(conflicted copy,"

private fun remember(relativePath: String, local: LocalFileEntry, remote: RemoteFileEntry?): SyncAction.RememberInSync =
    SyncAction.RememberInSync(
        relativePath = relativePath,
        sizeBytes = local.sizeBytes,
        lastModifiedEpochMillis = local.lastModifiedEpochMillis,
        remoteEtag = remote?.etag,
    )

/** Baseline is missing or does not yet record the current size, local mtime, or ETag. */
private fun needsRemember(local: LocalFileEntry, remote: RemoteFileEntry?, baseline: SyncFileStateEntity?): Boolean {
    if (baseline == null) return true
    if (baseline.lastSyncedSize != local.sizeBytes) return true
    if (!mtimesClose(local.lastModifiedEpochMillis, baseline.lastSyncedMtime)) return true
    val etag = remote?.etag?.takeIf { it.isNotBlank() } ?: return false
    return baseline.lastSyncedEtag != etag
}

/**
 * Computes the file-level (non-directory) sync action for one relative path, per sync method.
 * Directory create/delete is handled separately by [SyncDiffCalculator].
 */
sealed interface SyncMethodStrategy {

    fun computeFileAction(
        relativePath: String,
        local: LocalFileEntry?,
        remote: RemoteFileEntry?,
        baseline: SyncFileStateEntity?,
    ): SyncAction?

    /** Two-way: propagate whichever side changed; true conflicts (both changed) get resolved. */
    object TwoWay : SyncMethodStrategy {
        override fun computeFileAction(
            relativePath: String,
            local: LocalFileEntry?,
            remote: RemoteFileEntry?,
            baseline: SyncFileStateEntity?,
        ): SyncAction? {
            val lState = localState(local, baseline)
            val rState = remoteState(remote, baseline)
            val remotePath = remote?.relativePath ?: relativePath
            return when {
                lState == ChangeState.UNCHANGED && rState == ChangeState.UNCHANGED ->
                    if (local != null && needsRemember(local, remote, baseline)) remember(relativePath, local, remote) else null
                lState != ChangeState.UNCHANGED && rState == ChangeState.UNCHANGED -> when (lState) {
                    ChangeState.DELETED -> SyncAction.DeleteRemoteFile(
                        relativePath,
                        remotePath,
                        remote?.sizeBytes ?: -1L,
                    )
                    else -> SyncAction.UploadFile(relativePath, remotePath, remote?.etag)
                }
                lState == ChangeState.UNCHANGED && rState != ChangeState.UNCHANGED -> when (rState) {
                    ChangeState.DELETED -> SyncAction.DeleteLocalFile(relativePath, local?.sizeBytes ?: -1L)
                    else -> SyncAction.DownloadFile(relativePath, remote?.sizeBytes ?: 0L, remotePath, remote?.etag)
                }
                lState == ChangeState.DELETED && rState == ChangeState.DELETED -> null
                lState == ChangeState.DELETED -> SyncAction.DownloadFile(relativePath, remote?.sizeBytes ?: 0L, remotePath, remote?.etag)
                rState == ChangeState.DELETED -> SyncAction.UploadFile(relativePath, remotePath, remote?.etag)
                local != null && remote != null && local.sizeBytes == remote.sizeBytes ->
                    if (needsRemember(local, remote, baseline)) remember(relativePath, local, remote) else null
                local != null && remote != null && shouldRepairIncompleteTransfer(
                    relativePath, local, remote, baseline,
                ) -> if (local.sizeBytes >= remote.sizeBytes) {
                    SyncAction.UploadFile(relativePath, remotePath, remote.etag)
                } else {
                    SyncAction.DownloadFile(relativePath, remote.sizeBytes, remotePath, remote.etag)
                }
                else -> SyncAction.Conflict(
                    relativePath = relativePath,
                    winningSide = pickWinner(local, remote),
                    remoteRelativePath = remotePath,
                    localSizeBytes = local?.sizeBytes ?: 0L,
                    remoteSizeBytes = remote?.sizeBytes ?: 0L,
                    remoteEtag = remote?.etag,
                )
            }
        }

        /**
         * Incomplete transfer repair. Only when a baseline already exists (or the path is already
         * a conflict copy). Two brand-new copies of the same name with different sizes are a
         * conflict, not a license to keep the larger file.
         */
        private fun shouldRepairIncompleteTransfer(
            relativePath: String,
            local: LocalFileEntry,
            remote: RemoteFileEntry,
            baseline: SyncFileStateEntity?,
        ): Boolean {
            if (local.sizeBytes == remote.sizeBytes) return false
            if (isConflictedCopyPath(relativePath)) return true
            if (baseline != null) {
                val larger = maxOf(local.sizeBytes, remote.sizeBytes)
                val smaller = minOf(local.sizeBytes, remote.sizeBytes)
                if (baseline.lastSyncedSize == larger && smaller < larger) return true
            }
            return false
        }
    }

    /**
     * Mirrors remote → local. "In sync" is "remote size and ETag match the baseline and the
     * local size matches", not "local mtime is close to the server's Last-Modified".
     */
    object ToDevice : SyncMethodStrategy {
        override fun computeFileAction(
            relativePath: String,
            local: LocalFileEntry?,
            remote: RemoteFileEntry?,
            baseline: SyncFileStateEntity?,
        ): SyncAction? = when {
            remote == null -> if (baseline != null && local != null) {
                SyncAction.DeleteLocalFile(relativePath, local.sizeBytes)
            } else {
                null
            }
            local == null -> SyncAction.DownloadFile(relativePath, remote.sizeBytes, remote.relativePath, remote.etag)
            baseline == null && local.sizeBytes == remote.sizeBytes -> remember(relativePath, local, remote)
            baseline == null -> SyncAction.DownloadFile(relativePath, remote.sizeBytes, remote.relativePath, remote.etag)
            remote.sizeBytes == baseline.lastSyncedSize &&
                !etagDiffers(remote, baseline) &&
                local.sizeBytes == remote.sizeBytes ->
                if (needsRemember(local, remote, baseline)) remember(relativePath, local, remote) else null
            else -> SyncAction.DownloadFile(relativePath, remote.sizeBytes, remote.relativePath, remote.etag)
        }
    }

    /**
     * Mirrors local → remote. Compares the local file to the baseline, not to the server mtime.
     */
    object ToCloud : SyncMethodStrategy {
        override fun computeFileAction(
            relativePath: String,
            local: LocalFileEntry?,
            remote: RemoteFileEntry?,
            baseline: SyncFileStateEntity?,
        ): SyncAction? = when {
            local == null -> if (baseline != null && remote != null) {
                SyncAction.DeleteRemoteFile(relativePath, remote.relativePath, remote.sizeBytes)
            } else {
                null
            }
            remote == null -> SyncAction.UploadFile(relativePath)
            baseline == null && local.sizeBytes == remote.sizeBytes -> remember(relativePath, local, remote)
            baseline == null -> SyncAction.UploadFile(relativePath, remote.relativePath, remote.etag)
            localState(local, baseline) == ChangeState.UNCHANGED &&
                remote.sizeBytes == baseline.lastSyncedSize &&
                !etagDiffers(remote, baseline) ->
                if (needsRemember(local, remote, baseline)) remember(relativePath, local, remote) else null
            else -> SyncAction.UploadFile(relativePath, remote.relativePath, remote.etag)
        }
    }

    companion object {
        fun forMethod(method: SyncMethod): SyncMethodStrategy = when (method) {
            SyncMethod.TWO_WAY -> TwoWay
            SyncMethod.TO_DEVICE -> ToDevice
            SyncMethod.TO_CLOUD -> ToCloud
        }
    }
}
