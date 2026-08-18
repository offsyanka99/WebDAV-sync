package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.model.SyncMethod

/**
 * Cheap idle-pass decisions: skip remote work when a to-cloud pair's local tree has not changed,
 * skip remote-root MKCOL after a successful pass, and clean only ancestors of deleted files.
 */
object IdleSyncPolicy {
    const val STATUS_OK = "OK"

    fun fingerprint(entries: List<LocalFileEntry>): Long {
        var count = 0
        var totalSize = 0L
        var maxMtime = 0L
        var pathHash = 0L
        for (e in entries) {
            count++
            pathHash = 31L * pathHash + e.relativePath.hashCode()
            if (e.isDirectory) continue
            totalSize += e.sizeBytes
            if (e.lastModifiedEpochMillis > maxMtime) maxMtime = e.lastModifiedEpochMillis
        }
        return 31L * count + 17L * totalSize + maxMtime + pathHash
    }

    /**
     * To-the-cloud only: if the last pass was OK and the full local snapshot is unchanged,
     * there is nothing to upload and no reason to PROPFIND the remote tree.
     */
    fun canSkipRemoteScan(
        method: SyncMethod,
        lastSyncStatus: String?,
        lastFingerprint: Long?,
        currentFingerprint: Long,
    ): Boolean =
        method == SyncMethod.TO_CLOUD &&
            lastSyncStatus == STATUS_OK &&
            lastFingerprint != null &&
            lastFingerprint == currentFingerprint

    fun canSkipEnsureRemote(lastSyncStatus: String?): Boolean = lastSyncStatus == STATUS_OK

    /** Parent directories of [filePaths], deepest first, for empty-folder cleanup. */
    fun ancestorDirectories(filePaths: Collection<String>): List<String> {
        val dirs = linkedSetOf<String>()
        for (path in filePaths) {
            var rest = path.trimEnd('/')
            while (true) {
                val slash = rest.lastIndexOf('/')
                if (slash <= 0) break
                rest = rest.substring(0, slash)
                if (rest.isNotEmpty()) dirs.add(rest)
            }
        }
        return dirs.sortedByDescending { path -> path.count { it == '/' } }
    }
}
