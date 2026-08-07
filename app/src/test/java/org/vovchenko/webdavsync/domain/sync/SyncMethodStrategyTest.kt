package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.domain.model.SyncAction

private fun local(size: Long, mtime: Long) = LocalFileEntry("file.txt", isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = mtime)
private fun remote(size: Long, mtime: Long) = RemoteFileEntry("file.txt", isDirectory = false, sizeBytes = size, lastModifiedEpochMillis = mtime, etag = null)
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
        assertEquals(SyncAction.DeleteRemoteFile("file.txt"), action)
    }

    @Test
    fun `two-way local delete with remote mtime skew still deletes remote`() {
        // Server Last-Modified often differs from the baseline mtime we stored after upload;
        // that must not be treated as a remote edit (which would re-download the file).
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", null, remote(100, 99_999), base)
        assertEquals(SyncAction.DeleteRemoteFile("file.txt"), action)
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
        assertEquals(SyncAction.Conflict("file.txt", SyncAction.Side.LOCAL), action)
    }

    @Test
    fun `two-way both sides converge to the same content is not a conflict`() {
        val base = baseline(100, 1000)
        val action = twoWay.computeFileAction("file.txt", local(200, 5000), remote(200, 5000), base)
        assertNull(action)
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
        assertEquals(SyncAction.DeleteLocalFile("file.txt"), action)
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
        assertEquals(SyncAction.DeleteRemoteFile("file.txt"), action)
    }
}
