package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.domain.model.SyncAction

private fun local(size: Long, mtime: Long) = LocalFileEntry("file.txt", isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = mtime)
private fun remote(size: Long, mtime: Long, etag: String? = null) =
    RemoteFileEntry("file.txt", isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = mtime, etag = etag)
private fun baseline(size: Long, mtime: Long) = SyncFileStateEntity(folderPairId = 1, relativePath = "file.txt", lastSyncedMtime = mtime, lastSyncedSize = size)

class SyncMethodStrategyTest {

    private val twoWay = SyncMethodStrategy.forMethod(org.vovchenko.webdavsync.data.model.SyncMethod.TWO_WAY)
    private val toDevice = SyncMethodStrategy.forMethod(org.vovchenko.webdavsync.data.model.SyncMethod.TO_DEVICE)
    private val toCloud = SyncMethodStrategy.forMethod(org.vovchenko.webdavsync.data.model.SyncMethod.TO_CLOUD)

    @Test
    fun `two-way new local file is uploaded`() {
        val action = twoWay.computeFileAction("file.txt", local(100, 1000), null, null)
        assertEquals(SyncAction.UploadFile("file.txt"), action)
    }

    @Test
    fun `two-way new remote file is downloaded`() {
        val action = twoWay.computeFileAction("file.txt", null, remote(100, 1000), null)
        assertEquals(SyncAction.DownloadFile("file.txt", remoteSizeBytes = 100), action)
    }

    @Test
    fun `two-way unchanged file produces no action`() {
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", local(100, 1000), remote(100, 1000), base)
        assertNull(action)
    }

    @Test
    fun `two-way local delete propagates as remote delete`() {
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", null, remote(100, 1000), base)
        assertEquals(SyncAction.DeleteRemoteFile("file.txt", sizeBytes = 100), action)
    }

    @Test
    fun `two-way local delete with remote mtime skew still deletes remote`() {
        // Server Last-Modified often differs from the baseline mtime we stored after upload;
        // that must not be treated as a remote edit (which would re-download the file).
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", null, remote(100, 99_999), base)
        assertEquals(SyncAction.DeleteRemoteFile("file.txt", sizeBytes = 100), action)
    }

    @Test
    fun `two-way delete plus modify resurrects the modified side`() {
        val base = baseline(100, 1000)
        // Local deleted it, remote content size changed since baseline -> resurrect (download), not delete.
        val action = twoWay.computeFileAction("file.txt", null, remote(200, 5000), base)
        assertEquals(SyncAction.DownloadFile("file.txt", remoteSizeBytes = 200), action)
    }

    @Test
    fun `two-way both sides modified differently is a true conflict`() {
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", local(150, 9000), remote(200, 5000), base)
        assertEquals(
            SyncAction.Conflict(
                "file.txt",
                SyncAction.Side.LOCAL,
                localSizeBytes = 150,
                remoteSizeBytes = 200,
            ),
            action,
        )
    }

    @Test
    fun `two-way both sides converge to the same content records a fresh baseline`() {
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", local(200, 5000), remote(200, 5000), base)
        assertEquals(SyncAction.RememberInSync("file.txt", sizeBytes = 200, lastModifiedEpochMillis = 5000), action)
    }

    @Test
    fun `two-way both new with same size but different mtime seeds a baseline`() {
        // After download, SAF mtime is "now" while remote keeps original Last-Modified.
        val action = twoWay.computeFileAction("file.txt", local(200, 99_999), remote(200, 1000), null)
        assertEquals(SyncAction.RememberInSync("file.txt", sizeBytes = 200, lastModifiedEpochMillis = 99_999), action)
    }

    @Test
    fun `two-way both new with different sizes is a conflict when nothing was synced yet`() {
        val action = twoWay.computeFileAction("file.txt", local(4_000_000, 9000), remote(900_000, 8000), null)
        assertEquals(
            SyncAction.Conflict("file.txt", SyncAction.Side.LOCAL, localSizeBytes = 4_000_000, remoteSizeBytes = 900_000),
            action,
        )
    }

    @Test
    fun `two-way baseline matching the larger side repairs a truncated remote`() {
        val base = baseline(4_000_000, 1000)
        val action = twoWay.computeFileAction("file.txt", local(4_000_000, 9000), remote(900_000, 8000), base)
        assertEquals(SyncAction.UploadFile("file.txt"), action)
    }

    @Test
    fun `two-way etag change with the same size is a remote modification`() {
        val base = baseline(100, 1000).copy(lastSyncedEtag = "\"v1\"")
        val action = twoWay.computeFileAction(
            "file.txt",
            local(100, 1000),
            remote(100, 1000, etag = "\"v2\""),
            base,
        )
        assertEquals(SyncAction.DownloadFile("file.txt", remoteSizeBytes = 100, remoteEtag = "\"v2\""), action)
    }

    @Test
    fun `two-way never nests conflict on existing conflicted-copy path`() {
        val path = "file (conflicted copy, device, 2026-08-09).apk"
        fun localAt(size: Long, mtime: Long) =
            LocalFileEntry(path, isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = mtime)
        fun remoteAt(size: Long, mtime: Long) =
            RemoteFileEntry(path, isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = mtime, etag = null)
        val base = SyncFileStateEntity(
            folderPairId = 1,
            relativePath = path,
            lastSyncedMtime = 1000,
            lastSyncedSize = 4_000_000,
        )
        val action = twoWay.computeFileAction(
            path,
            localAt(4_000_000, 9000),
            remoteAt(900_000, 8000),
            base,
        )
        assertEquals(SyncAction.UploadFile(path), action)
    }

    @Test
    fun `to-device ignores local-only files`() {
        val action = toDevice.computeFileAction("file.txt", local(100, 1000), null, null)
        assertNull(action)
    }

    @Test
    fun `to-device downloads remote-only files`() {
        val action = toDevice.computeFileAction("file.txt", null, remote(100, 1000), null)
        assertEquals(SyncAction.DownloadFile("file.txt", remoteSizeBytes = 100), action)
    }

    @Test
    fun `to-device deletes local file removed remotely`() {
        val base = baseline(100, 1000)
        val action = toDevice.computeFileAction("file.txt", local(100, 1000), null, base)
        assertEquals(SyncAction.DeleteLocalFile("file.txt", sizeBytes = 100), action)
    }

    @Test
    fun `to-device equal size with distant mtimes is in sync once a baseline exists`() {
        val base = baseline(100, 50_000)
        val action = toDevice.computeFileAction("file.txt", local(100, 50_000), remote(100, 1_000), base)
        assertNull(action)
    }

    @Test
    fun `to-cloud ignores remote-only files`() {
        val action = toCloud.computeFileAction("file.txt", null, remote(100, 1000), null)
        assertNull(action)
    }

    @Test
    fun `to-cloud uploads local-only files`() {
        val action = toCloud.computeFileAction("file.txt", local(100, 1000), null, null)
        assertEquals(SyncAction.UploadFile("file.txt"), action)
    }

    @Test
    fun `to-cloud deletes remote file removed locally`() {
        val base = baseline(100, 1000)
        val action = toCloud.computeFileAction("file.txt", null, remote(100, 1000), base)
        assertEquals(SyncAction.DeleteRemoteFile("file.txt", sizeBytes = 100), action)
    }

    @Test
    fun `to-cloud equal size with distant mtimes is in sync once a baseline exists`() {
        val base = baseline(100, 1000)
        val action = toCloud.computeFileAction("file.txt", local(100, 1000), remote(100, 90_000), base)
        assertNull(action)
    }
}
