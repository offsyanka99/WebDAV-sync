package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.model.SyncMethod

/**
 * Cheap idle-pass decisions: skip remote work when a to-cloud pair's local tree has not changed,
 * skip remote-root MKCOL after a successful pass, and clean only ancestors of deleted files.
 */
object IdleSyncPolicy {
    const val STATUS_OK = "OK"

    /** How long any method may trust the local fingerprint before it must PROPFIND again. */
    const val FULL_REMOTE_SCAN_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /**
     * How long an unchanged cheap root fingerprint may stand in for a full SAF walk.
     * Nested edits that do not change the root listing are still walked on this cadence.
     */
    const val FULL_LOCAL_WALK_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    /** How long a stored content hash of an mtime-0 file is trusted when the size is unchanged. */
    const val CONTENT_HASH_SWEEP_MAX_AGE_MS = 24L * 60L * 60L * 1000L

    /**
     * Order-independent fingerprint of every entry (path, directory bit, size, mtime).
     * A linear mix of totals can cancel out; this mixes each file on its own.
     */
    fun fingerprint(entries: List<LocalFileEntry>): Long {
        var hash = FNV_OFFSET
        for (entry in entries.sortedBy { it.relativePath }) {
            hash = fnv(hash, entry.relativePath)
            hash = fnv(hash, if (entry.isDirectory) 1L else 0L)
            hash = fnv(hash, entry.sizeBytes)
            hash = fnv(hash, entry.lastModifiedEpochMillis)
        }
        return hash
    }

    /** False when any file reports mtime 0 — those trees must not skip the remote scan. */
    fun mtimeReliable(entries: List<LocalFileEntry>): Boolean =
        entries.none { entry -> !entry.isDirectory && entry.lastModifiedEpochMillis == 0L }

    /**
     * Skip PROPFIND when the last pass was OK, the local snapshot is unchanged,
     * mtimes are real, and a full remote scan happened within [FULL_REMOTE_SCAN_MAX_AGE_MS].
     * Applies to two-way, to-device, and to-cloud. A remote-only edit waits until the cap.
     */
    fun canSkipRemoteScan(
        method: SyncMethod,
        lastSyncStatus: String?,
        lastFingerprint: Long?,
        currentFingerprint: Long,
        lastRemoteScanAt: Long?,
        nowMillis: Long,
        mtimeReliable: Boolean,
    ): Boolean {
        if (!mtimeReliable) return false
        if (lastRemoteScanAt == null) return false
        if (nowMillis - lastRemoteScanAt >= FULL_REMOTE_SCAN_MAX_AGE_MS) return false
        if (lastSyncStatus != STATUS_OK) return false
        if (lastFingerprint == null || lastFingerprint != currentFingerprint) return false
        return method == SyncMethod.TO_CLOUD ||
            method == SyncMethod.TO_DEVICE ||
            method == SyncMethod.TWO_WAY
    }

    /**
     * Skip the full SAF walk when the cheap root snapshot is unchanged, the last full walk
     * and the last remote scan are both still inside their caps, and the previous pass was OK.
     * [mtimeKnownReliable] is true only when the stored cheap fingerprint was taken from a
     * scan whose file mtimes were real.
     */
    fun canSkipFullLocalWalk(
        lastCheapFingerprint: Long?,
        currentCheapFingerprint: Long,
        lastFullLocalScanAt: Long?,
        nowMillis: Long,
        lastLocalFingerprint: Long?,
        lastSyncStatus: String?,
        lastRemoteScanAt: Long?,
        mtimeKnownReliable: Boolean,
    ): Boolean {
        if (!mtimeKnownReliable) return false
        if (lastCheapFingerprint == null || lastCheapFingerprint == 0L) return false
        if (currentCheapFingerprint == 0L || lastCheapFingerprint != currentCheapFingerprint) return false
        if (lastLocalFingerprint == null || lastSyncStatus != STATUS_OK) return false
        if (lastFullLocalScanAt == null) return false
        if (nowMillis - lastFullLocalScanAt >= FULL_LOCAL_WALK_MAX_AGE_MS) return false
        if (lastRemoteScanAt == null) return false
        if (nowMillis - lastRemoteScanAt >= FULL_REMOTE_SCAN_MAX_AGE_MS) return false
        return true
    }

    fun hashSweepIsFresh(lastSweepAt: Long?, nowMillis: Long): Boolean =
        lastSweepAt != null && nowMillis - lastSweepAt < CONTENT_HASH_SWEEP_MAX_AGE_MS

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

    private const val FNV_OFFSET = -3750763034362895579L
    private const val FNV_PRIME = 1099511628211L

    private fun fnv(hash: Long, value: Long): Long {
        var mixed = hash
        var remaining = value
        repeat(8) {
            mixed = mixed xor (remaining and 0xFF)
            mixed *= FNV_PRIME
            remaining = remaining ushr 8
        }
        return mixed
    }

    private fun fnv(hash: Long, value: String): Long {
        var mixed = hash
        for (char in value) {
            mixed = mixed xor char.code.toLong()
            mixed *= FNV_PRIME
        }
        mixed = mixed xor 0xFF
        mixed *= FNV_PRIME
        return mixed
    }
}
