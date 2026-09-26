package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.domain.model.SyncAction

class ZeroMtimeReconcilerTest {

    @Test
    fun `same size and mtime 0 with a new hash becomes an upload`() {
        val local = LocalFileEntry("a.txt", isDirectory = false, sizeBytes = 10, lastModifiedEpochMillis = 0)
        val baseline = SyncFileStateEntity(
            folderPairId = 1,
            relativePath = "a.txt",
            lastSyncedMtime = 0,
            lastSyncedSize = 10,
            lastSyncedHash = "old",
        )
        val actions = ZeroMtimeReconciler.apply(
            actions = listOf(SyncAction.RememberInSync("a.txt", 10, 0)),
            syncMethod = SyncMethod.TWO_WAY,
            localEntries = listOf(local),
            remoteEntries = listOf(
                RemoteFileEntry("a.txt", false, 10, 0, etag = null),
            ),
            baseline = listOf(baseline),
            hashOf = { "new" },
        )
        assertTrue(actions.single() is SyncAction.UploadFile)
    }
}
