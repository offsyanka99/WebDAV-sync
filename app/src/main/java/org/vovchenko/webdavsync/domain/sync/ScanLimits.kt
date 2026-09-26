package org.vovchenko.webdavsync.domain.sync

/**
 * A partial tree must never be diffed: missing entries look like deletions.
 * Scanners fail the pair instead of returning a truncated listing.
 */
class TreeScanLimitException(message: String) : IllegalStateException(message)

object ScanLimits {
    const val MAX_DEPTH = 40
    const val MAX_ENTRIES = 100_000

    fun check(depth: Int, entryCount: Int) {
        if (depth > MAX_DEPTH) {
            throw TreeScanLimitException("Folder is nested more than $MAX_DEPTH levels deep")
        }
        if (entryCount > MAX_ENTRIES) {
            throw TreeScanLimitException("Folder has more than $MAX_ENTRIES files and directories")
        }
    }
}
