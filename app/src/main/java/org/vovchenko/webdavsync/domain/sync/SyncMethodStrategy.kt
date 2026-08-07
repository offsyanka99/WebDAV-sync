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
 * Remote change detection. WebDAV `Last-Modified` is often unreliable vs the mtime we stored at
 * last sync (server rounding, timezone, or local vs remote clocks). Treating mtime skew as a
 * remote modification caused two-way sync to *re-download* files the user deleted locally.
 * Size is the stable signal for "did remote content change?".
 */
private fun remoteState(remote: RemoteFileEntry?, baseline: SyncFileStateEntity?): ChangeState = when {
    baseline == null -> if (remote != null) ChangeState.NEW else ChangeState.UNCHANGED
    remote == null -> ChangeState.DELETED
    remote.sizeBytes != baseline.lastSyncedSize -> ChangeState.MODIFIED
    else -> ChangeState.UNCHANGED
}

/** Picks the "winner" for a true conflict — the more recently modified side wins, ties go to local. */
private fun pickWinner(local: LocalFileEntry?, remote: RemoteFileEntry?): SyncAction.Side {
    val localMtime = local?.lastModifiedEpochMillis ?: return SyncAction.Side.REMOTE
    val remoteMtime = remote?.lastModifiedEpochMillis ?: return SyncAction.Side.LOCAL
    return if (localMtime >= remoteMtime) SyncAction.Side.LOCAL else SyncAction.Side.REMOTE
}

/**
 * Computes the file-level (non-directory) sync action for one relative path, per sync method
 * (plan Phase 4 "Strategy per sync method"). Directory create/delete is handled separately by
 * [SyncDiffCalculator] since directories have no three-way baseline of their own.
 */
sealed interface SyncMethodStrategy {

    fun computeFileAction(
        relativePath: String,
        local: LocalFileEntry?,
        remote: RemoteFileEntry?,
        baseline: SyncFileStateEntity?,
    ): SyncAction?

    /** Two-way: propagate whichever side changed; true conflicts (both changed) get resolved (§4.2). */
    object TwoWay : SyncMethodStrategy {
        override fun computeFileAction(
            relativePath: String,
            local: LocalFileEntry?,
            remote: RemoteFileEntry?,
            baseline: SyncFileStateEntity?,
        ): SyncAction? {
            val lState = localState(local, baseline)
            val rState = remoteState(remote, baseline)

            return when {
                lState == ChangeState.UNCHANGED && rState == ChangeState.UNCHANGED -> null
                lState != ChangeState.UNCHANGED && rState == ChangeState.UNCHANGED -> when (lState) {
                    ChangeState.DELETED -> SyncAction.DeleteRemoteFile(relativePath)
                    else -> SyncAction.UploadFile(relativePath)
                }
                lState == ChangeState.UNCHANGED && rState != ChangeState.UNCHANGED -> when (rState) {
                    ChangeState.DELETED -> SyncAction.DeleteLocalFile(relativePath)
                    else -> SyncAction.DownloadFile(relativePath)
                }
                lState == ChangeState.DELETED && rState == ChangeState.DELETED -> null
                // One side deleted, the other modified → resurrect the modified side (§4.2), not a rename-conflict.
                lState == ChangeState.DELETED -> SyncAction.DownloadFile(relativePath)
                rState == ChangeState.DELETED -> SyncAction.UploadFile(relativePath)
                // Both created/modified independently: if they happen to match, just adopt — else true conflict.
                local != null && remote != null && local.sizeBytes == remote.sizeBytes &&
                    mtimesClose(local.lastModifiedEpochMillis, remote.lastModifiedEpochMillis) -> null
                else -> SyncAction.Conflict(relativePath, pickWinner(local, remote))
            }
        }
    }

    /** Mirrors remote → local; local-only edits are never pushed, local-only files are left alone. */
    object ToDevice : SyncMethodStrategy {
        override fun computeFileAction(
            relativePath: String,
            local: LocalFileEntry?,
            remote: RemoteFileEntry?,
            baseline: SyncFileStateEntity?,
        ): SyncAction? = when {
            remote == null -> if (baseline != null && local != null) SyncAction.DeleteLocalFile(relativePath) else null
            local == null -> SyncAction.DownloadFile(relativePath)
            local.sizeBytes == remote.sizeBytes && mtimesClose(local.lastModifiedEpochMillis, remote.lastModifiedEpochMillis) -> null
            else -> SyncAction.DownloadFile(relativePath)
        }
    }

    /** Mirrors local → remote; remote-only edits are never pulled, remote-only files are left alone. */
    object ToCloud : SyncMethodStrategy {
        override fun computeFileAction(
            relativePath: String,
            local: LocalFileEntry?,
            remote: RemoteFileEntry?,
            baseline: SyncFileStateEntity?,
        ): SyncAction? = when {
            local == null -> if (baseline != null && remote != null) SyncAction.DeleteRemoteFile(relativePath) else null
            remote == null -> SyncAction.UploadFile(relativePath)
            local.sizeBytes == remote.sizeBytes && mtimesClose(local.lastModifiedEpochMillis, remote.lastModifiedEpochMillis) -> null
            else -> SyncAction.UploadFile(relativePath)
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
