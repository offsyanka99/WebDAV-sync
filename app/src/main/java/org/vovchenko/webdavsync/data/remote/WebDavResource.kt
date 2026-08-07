package org.vovchenko.webdavsync.data.remote

/** A single remote file/folder entry returned by `PROPFIND` (list). */
data class WebDavResource(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long?,
    val etag: String?,
)

/** Server storage quota, backs the Overview "Cloud Storage" card (requirements.md). */
data class WebDavQuota(
    val availableBytes: Long?,
    val usedBytes: Long?,
) {
    /**
     * Total capacity. RFC 4331 only defines available + used; some servers return sentinel
     * values (-1 / -2 / -3) which we treat as unknown.
     */
    val totalBytes: Long?
        get() {
            val free = availableBytes?.takeIf { it >= 0 }
            val used = usedBytes?.takeIf { it >= 0 }
            return if (free != null && used != null) free + used else null
        }
}
