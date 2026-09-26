package org.vovchenko.webdavsync.domain.model

/** A single computed sync operation (output of the three-way diff, Phase 4). */
sealed class SyncAction {
    abstract val relativePath: String

    /**
     * [relativePath] is the local-storage-safe path used for local I/O and the sync baseline.
     * [remoteRelativePath] is the actual path on the server, which can differ when the remote
     * name contains characters a local SAF provider rewrites (see [org.vovchenko.webdavsync.data.local.saf.LocalNameSanitizer]);
     * it defaults to [relativePath] for the common case where the two are identical.
     */
    data class UploadFile(
        override val relativePath: String,
        val remoteRelativePath: String = relativePath,
        val remoteEtag: String? = null,
    ) : SyncAction()
    /** [remoteSizeBytes] from PROPFIND; used to enforce download size limits before streaming. */
    data class DownloadFile(
        override val relativePath: String,
        val remoteSizeBytes: Long = 0L,
        val remoteRelativePath: String = relativePath,
        val remoteEtag: String? = null,
    ) : SyncAction()
    data class DeleteLocalFile(override val relativePath: String, val sizeBytes: Long = -1L) : SyncAction()
    data class DeleteRemoteFile(
        override val relativePath: String,
        val remoteRelativePath: String = relativePath,
        val sizeBytes: Long = -1L,
    ) : SyncAction()
    data class CreateLocalDirectory(override val relativePath: String) : SyncAction()
    data class CreateRemoteDirectory(override val relativePath: String) : SyncAction()
    data class DeleteLocalDirectory(override val relativePath: String) : SyncAction()
    data class DeleteRemoteDirectory(
        override val relativePath: String,
        val remoteRelativePath: String = relativePath,
    ) : SyncAction()

    /**
     * Both sides already agree. Writes a baseline row when one is missing or stale so later
     * deletes are real deletes, without transferring the file.
     */
    data class RememberInSync(
        override val relativePath: String,
        val sizeBytes: Long,
        val lastModifiedEpochMillis: Long,
        val remoteEtag: String? = null,
    ) : SyncAction()

    data class RememberDirectory(override val relativePath: String) : SyncAction()

    /** Baseline row for a path that is gone on both sides. */
    data class DropBaseline(override val relativePath: String) : SyncAction()

    /**
     * Remote rename detected in this pass. [relativePath] is the new path.
     * The executor MOVEs the old remote object, and uploads then deletes only if MOVE fails.
     */
    data class MoveRemote(
        override val relativePath: String,
        val fromRelativePath: String,
        val remoteFromRelativePath: String,
        val remoteToRelativePath: String,
    ) : SyncAction()

    /**
     * Both sides changed since the last sync baseline (plan §4.2). [winningSide] is applied as
     * the canonical version; the other side's current content is preserved as a renamed
     * "conflicted copy" before being overwritten — never silently discarded.
     */
    data class Conflict(
        override val relativePath: String,
        val winningSide: Side,
        val remoteRelativePath: String = relativePath,
        val localSizeBytes: Long = 0L,
        val remoteSizeBytes: Long = 0L,
        val remoteEtag: String? = null,
    ) : SyncAction()

    enum class Side { LOCAL, REMOTE }
}
