package org.vovchenko.webdavsync.data.local.saf

/** One local file/folder entry discovered by [LocalTreeScanner], relative to the folder-pair root. */
data class LocalFileEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long,
)
