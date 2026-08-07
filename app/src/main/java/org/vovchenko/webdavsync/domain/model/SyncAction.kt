package org.vovchenko.webdavsync.domain.model

/** A single computed sync operation (output of the three-way diff, Phase 4). */
sealed class SyncAction {
    abstract val relativePath: String

    data class UploadFile(override val relativePath: String) : SyncAction()
    data class DownloadFile(override val relativePath: String) : SyncAction()
    data class DeleteLocalFile(override val relativePath: String) : SyncAction()
    data class DeleteRemoteFile(override val relativePath: String) : SyncAction()
    data class CreateLocalDirectory(override val relativePath: String) : SyncAction()
    data class CreateRemoteDirectory(override val relativePath: String) : SyncAction()

    /**
     * Both sides changed since the last sync baseline (plan §4.2). [winningSide] is applied as
     * the canonical version; the other side's current content is preserved as a renamed
     * "conflicted copy" before being overwritten — never silently discarded.
     */
    data class Conflict(override val relativePath: String, val winningSide: Side) : SyncAction()

    enum class Side { LOCAL, REMOTE }
}
