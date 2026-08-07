package org.vovchenko.webdavsync.domain.model

/** Aggregate result of one sync pass, feeding `SyncLogEntity` and the Overview "Recent changes" card. */
data class SyncOutcome(
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedLocal: Int = 0,
    val deletedRemote: Int = 0,
    val conflicts: Int = 0,
    val errors: Int = 0,
    val durationMs: Long = 0,
    /** True when the user cancelled via notification action mid-pass. */
    val cancelled: Boolean = false,
) {
    val hasErrors: Boolean get() = errors > 0 && !cancelled
    val totalChanges: Int get() = uploaded + downloaded + deletedLocal + deletedRemote
}
