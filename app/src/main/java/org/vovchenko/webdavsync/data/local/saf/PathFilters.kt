package org.vovchenko.webdavsync.data.local.saf

/**
 * Paths the sync engine must ignore on **both** sides.
 *
 * Applying a filter only to the local scan makes the diff treat those remote files as
 * locally deleted and remove them from the server.
 */
object PathFilters {
    private val TEMP_SUFFIXES = listOf(
        ".crdownload",
        ".partial",
        ".tmp",
        ".part",
        ".download",
        ".temp",
    )

    fun isHidden(relativePath: String): Boolean =
        relativePath.split('/').any { segment -> segment.startsWith('.') }

    /** Browser/download leftovers that are not finished files yet. */
    fun isTemporary(relativePath: String): Boolean {
        val name = relativePath.substringAfterLast('/').lowercase()
        return TEMP_SUFFIXES.any { suffix -> name.endsWith(suffix) }
    }

    fun excludedFromSync(relativePath: String, excludeHiddenFiles: Boolean): Boolean =
        isTemporary(relativePath) || (excludeHiddenFiles && isHidden(relativePath))
}
