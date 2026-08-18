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
        assertTrue(
            IdleSyncPolicy.canSkipRemoteScan(SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 42L),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(SyncMethod.TO_CLOUD, "OK", lastFingerprint = 42L, currentFingerprint = 43L),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(SyncMethod.TO_CLOUD, "OK", lastFingerprint = null, currentFingerprint = 42L),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(SyncMethod.TO_CLOUD, "ERROR", lastFingerprint = 42L, currentFingerprint = 42L),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(SyncMethod.TWO_WAY, "OK", lastFingerprint = 42L, currentFingerprint = 42L),
        )
        assertFalse(
            IdleSyncPolicy.canSkipRemoteScan(SyncMethod.TO_DEVICE, "OK", lastFingerprint = 42L, currentFingerprint = 42L),
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
