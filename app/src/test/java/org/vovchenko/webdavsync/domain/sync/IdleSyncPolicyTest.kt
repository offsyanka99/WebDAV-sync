package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.model.SyncMethod

class IdleSyncPolicyTest {

    @Test
    fun `fingerprint is stable and changes when a file is added or renamed`() {
        val a = file("photos/a.jpg", size = 10, mtime = 100)
        val b = file("photos/b.jpg", size = 20, mtime = 200)
        val first = IdleSyncPolicy.fingerprint(listOf(dir("photos"), a, b))
        val same = IdleSyncPolicy.fingerprint(listOf(dir("photos"), a, b))
        val added = IdleSyncPolicy.fingerprint(listOf(dir("photos"), a, b, file("photos/c.jpg", 5, 300)))
        val renamed = IdleSyncPolicy.fingerprint(listOf(dir("photos"), a, file("photos/b2.jpg", 20, 200)))
        assertEquals(first, same)
        assertNotEquals(first, added)
        assertNotEquals(first, renamed)
    }

    @Test
    fun `to-cloud skips remote only after an OK pass with the same fingerprint`() {
        val recent = 1_000L
        assertTrue(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = recent, nowMillis = recent + 60_000, mtimeReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 43L,
                lastRemoteScanAt = recent, nowMillis = recent, mtimeReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "OK", lastFingerprint = null, currentFingerprint = 42L,
                lastRemoteScanAt = recent, nowMillis = recent, mtimeReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "ERROR", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = recent, nowMillis = recent, mtimeReliable = true,
            ),
        )
        assertTrue(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TWO_WAY, "OK", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = recent, nowMillis = recent, mtimeReliable = true,
            ),
        )
        assertTrue(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_DEVICE, "OK", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = recent, nowMillis = recent, mtimeReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = null, nowMillis = recent, mtimeReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = recent,
                nowMillis = recent + IdleSyncPolicy.FULL_REMOTE_SCAN_MAX_AGE_MS,
                mtimeReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(
                SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 42L,
                lastRemoteScanAt = recent, nowMillis = recent, mtimeReliable = false,
            ),
        )
    }

    @Test
    fun `fingerprint ignores list order`() {
        val a = file("b.txt", size = 2, mtime = 20)
        val b = file("a.txt", size = 1, mtime = 10)
        assertEquals(
            IdleSyncPolicy.fingerprint(listOf(a, b)),
            IdleSyncPolicy.fingerprint(listOf(b, a)),
        )
    }

    @Test
    fun `full local walk is skipped only while the cheap snapshot and both caps are fresh`() {
        val now = 10_000L
        assertTrue(
            IdleSyncPolicy.canSkipFullLocalWalk(
                lastCheapFingerprint = 7L,
                currentCheapFingerprint = 7L,
                lastFullLocalScanAt = now - 60_000,
                nowMillis = now,
                lastLocalFingerprint = 42L,
                lastSyncStatus = "OK",
                lastRemoteScanAt = now - 60_000,
                mtimeKnownReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipFullLocalWalk(
                lastCheapFingerprint = 7L,
                currentCheapFingerprint = 8L,
                lastFullLocalScanAt = now,
                nowMillis = now,
                lastLocalFingerprint = 42L,
                lastSyncStatus = "OK",
                lastRemoteScanAt = now,
                mtimeKnownReliable = true,
            ),
        )
        assertFalse(
            IdleSyncPolicy.canSkipFullLocalWalk(
                lastCheapFingerprint = 7L,
                currentCheapFingerprint = 7L,
                lastFullLocalScanAt = now - IdleSyncPolicy.FULL_LOCAL_WALK_MAX_AGE_MS,
                nowMillis = now,
                lastLocalFingerprint = 42L,
                lastSyncStatus = "OK",
                lastRemoteScanAt = now,
                mtimeKnownReliable = true,
            ),
        )
    }

    @Test
    fun `content hash sweep stays fresh for a day`() {
        val now = 5_000L
        assertTrue(IdleSyncPolicy.hashSweepIsFresh(now - 60_000, now))
        assertFalse(IdleSyncPolicy.hashSweepIsFresh(null, now))
        assertFalse(
            IdleSyncPolicy.hashSweepIsFresh(now - IdleSyncPolicy.CONTENT_HASH_SWEEP_MAX_AGE_MS, now),
        )
    }

    @Test
    fun `ensure remote is skipped only after an OK pass`() {
        assertTrue(IdleSyncPolicy.canSkipEnsureRemote("OK"))
        assertFalse(IdleSyncPolicy.canSkipEnsureRemote(null))
        assertFalse(IdleSyncPolicy.canSkipEnsureRemote("ERROR"))
    }

    @Test
    fun `ancestor directories are deepest first and ignore root files`() {
        val dirs = IdleSyncPolicy.ancestorDirectories(
            listOf("a/b/c.txt", "a/d.txt", "root.txt"),
        )
        assertEquals(listOf("a/b", "a"), dirs)
        assertTrue(IdleSyncPolicy.ancestorDirectories(listOf("file.txt")).isEmpty())
    }

    private fun file(path: String, size: Long, mtime: Long) = LocalFileEntry(
        relativePath = path,
        isDirectory = false,
        sizeBytes = size,
        lastModifiedEpochMillis = mtime,
    )

    private fun dir(path: String) = LocalFileEntry(
        relativePath = path,
        isDirectory = true,
        sizeBytes = 0,
        lastModifiedEpochMillis = 0,
    )
}
