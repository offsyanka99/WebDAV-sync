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

/** True for paths we already minted as conflict copies — never nest another layer. */
internal fun isConflictedCopyPath(relativePath: String): Boolean =
    relativePath.substringAfterLast('/').contains(CONFLICTED_COPY_MARKER, ignoreCase = false)

private const val CONFLICTED_COPY_MARKER = "(conflicted copy,"

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

            // Prefer the entry's own remote path (the server's actual name) over the canonical/local-safe path.
            val remotePath = remote?.relativePath ?: relativePath
            return when {
                lState == ChangeState.UNCHANGED && rState == ChangeState.UNCHANGED -> null
                lState != ChangeState.UNCHANGED && rState == ChangeState.UNCHANGED -> when (lState) {
                    ChangeState.DELETED -> SyncAction.DeleteRemoteFile(relativePath, remotePath)
                    else -> SyncAction.UploadFile(relativePath, remotePath)
                }
                lState == ChangeState.UNCHANGED && rState != ChangeState.UNCHANGED -> when (rState) {
                    ChangeState.DELETED -> SyncAction.DeleteLocalFile(relativePath)
                    else -> SyncAction.DownloadFile(relativePath, remote?.sizeBytes ?: 0L, remotePath)
                }
                lState == ChangeState.DELETED && rState == ChangeState.DELETED -> null
                // One side deleted, the other modified → resurrect the modified side (§4.2), not a rename-conflict.
                lState == ChangeState.DELETED -> SyncAction.DownloadFile(relativePath, remote?.sizeBytes ?: 0L, remotePath)
                rState == ChangeState.DELETED -> SyncAction.UploadFile(relativePath, remotePath)
                // Both created/modified independently: size match → same content for our purposes.
                // Do NOT require mtimesClose: after a download SAF stamps local mtime as "now" while
                // remote keeps the original Last-Modified, which falsely looked like a conflict and
                // triggered upload + "conflicted copy" duplicates on the next pass.
                local != null && remote != null && local.sizeBytes == remote.sizeBytes -> null
                // Incomplete PUT/GET (timeout mid-body) leaves both sides present with different
                // sizes and no matching baseline. Treat as repair, not a true dual-edit conflict —
                // otherwise we nest "(conflicted copy)" names every pass (see diagnostic logs).
                local != null && remote != null && shouldRepairIncompleteTransfer(
                    relativePath, local, remote, baseline, lState, rState,
                ) -> if (local.sizeBytes >= remote.sizeBytes) {
                    SyncAction.UploadFile(relativePath, remotePath)
                } else {
                    SyncAction.DownloadFile(relativePath, remote.sizeBytes, remotePath)
                }
                else -> SyncAction.Conflict(relativePath, pickWinner(local, remote), remotePath)
            }
        }

        /**
         * Incomplete transfer / cascade repair:
         * - Already a conflicted-copy path → never nest another conflict; overwrite smaller side.
         * - Both NEW (no baseline) with different sizes → almost always a partial PUT, not two
         *   independent full creates of the same name.
         */
        private fun shouldRepairIncompleteTransfer(
            relativePath: String,
            local: LocalFileEntry,
            remote: RemoteFileEntry,
            baseline: SyncFileStateEntity?,
            lState: ChangeState,
            rState: ChangeState,
        ): Boolean {
            if (local.sizeBytes == remote.sizeBytes) return false
            if (isConflictedCopyPath(relativePath)) return true
            if (baseline == null && lState == ChangeState.NEW && rState == ChangeState.NEW) return true
            // Baseline matches the larger side → smaller side is a truncated residual.
            if (baseline != null) {
                val larger = maxOf(local.sizeBytes, remote.sizeBytes)
                val smaller = minOf(local.sizeBytes, remote.sizeBytes)
                if (baseline.lastSyncedSize == larger && smaller < larger) return true
            }
            return false
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
            local == null -> SyncAction.DownloadFile(relativePath, remote.sizeBytes, remote.relativePath)
            local.sizeBytes == remote.sizeBytes && mtimesClose(local.lastModifiedEpochMillis, remote.lastModifiedEpochMillis) -> null
            else -> SyncAction.DownloadFile(relativePath, remote.sizeBytes, remote.relativePath)
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
            local == null -> if (baseline != null && remote != null) SyncAction.DeleteRemoteFile(relativePath, remote.relativePath) else null
            remote == null -> SyncAction.UploadFile(relativePath)
            local.sizeBytes == remote.sizeBytes && mtimesClose(local.lastModifiedEpochMillis, remote.lastModifiedEpochMillis) -> null
            else -> SyncAction.UploadFile(relativePath, remote.relativePath)
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
