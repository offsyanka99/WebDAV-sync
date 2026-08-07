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
    val totalBytes: Long? get() = if (availableBytes != null && usedBytes != null) availableBytes + usedBytes else null
}
