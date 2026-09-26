package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.SyncLogEntity
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.data.model.SyncMethod

class SyncOverviewMetricsTest {

    @Test
    fun `picks most recent pair and recent-change session summary`() {
        val pairs = listOf(
            FolderPairEntity(
                id = 1,
                accountId = 1,
                name = "old",
                remoteFolderPath = "/",
                localFolderUri = "content://a",
                syncMethod = SyncMethod.TWO_WAY,
                lastSyncAt = 1_000L,
                lastSyncDurationMs = 100,
                lastSyncStatus = "OK",
            ),
            FolderPairEntity(
                id = 2,
                accountId = 1,
                name = "new",
                remoteFolderPath = "/",
                localFolderUri = "content://b",
                syncMethod = SyncMethod.TWO_WAY,
                lastSyncAt = 5_000L,
                lastSyncDurationMs = 250,
                lastSyncStatus = "ERROR",
            ),
        )
        val logs = listOf(
            SyncLogEntity(
                id = 1,
                folderPairId = 2,
                timestamp = 5_000L,
                eventType = SyncEventType.SYNC_END,
                fileCount = 0,
                message = RecentChangesCalculator.SESSION_MESSAGE,
            ),
            SyncLogEntity(
                id = 2,
                folderPairId = 2,
                timestamp = 5_000L,
                eventType = SyncEventType.UPLOAD,
                fileCount = 3,
            ),
            SyncLogEntity(
                id = 3,
                folderPairId = 2,
                timestamp = 5_000L,
                eventType = SyncEventType.DOWNLOAD,
                fileCount = 1,
            ),
            SyncLogEntity(
                id = 4,
                folderPairId = 2,
                timestamp = 5_000L,
                eventType = SyncEventType.DELETE_DEVICE,
                fileCount = 0,
            ),
            SyncLogEntity(
                id = 5,
                folderPairId = 2,
                timestamp = 5_000L,
                eventType = SyncEventType.DELETE_CLOUD,
                fileCount = 2,
            ),
        )
        val metrics = SyncOverviewMetrics.from(pairs, logs, syncing = true)
        assertEquals(5_000L, metrics.lastSyncAtMillis)
        assertEquals(250L, metrics.lastSyncDurationMs)
        assertEquals("ERROR", metrics.lastSyncStatus)
        assertEquals(3, metrics.uploaded)
        assertEquals(1, metrics.downloaded)
        assertEquals(0, metrics.deletedDevice)
        assertEquals(2, metrics.deletedCloud)
        assertEquals(true, metrics.syncing)
    }

    @Test
    fun `any enabled pair in ERROR wins over a newer OK pair`() {
        val pairs = listOf(
            FolderPairEntity(
                id = 1, accountId = 1, name = "failed", remoteFolderPath = "/a",
                localFolderUri = "content://a", lastSyncAt = 1_000L, lastSyncStatus = "ERROR",
            ),
            FolderPairEntity(
                id = 2, accountId = 1, name = "ok", remoteFolderPath = "/b",
                localFolderUri = "content://b", lastSyncAt = 9_000L, lastSyncStatus = "OK",
            ),
        )
        val metrics = SyncOverviewMetrics.from(pairs, emptyList(), syncing = false)
        assertEquals("ERROR", metrics.lastSyncStatus)
        assertEquals(9_000L, metrics.lastSyncAtMillis)
    }

    @Test
    fun `empty pairs yields null last sync`() {
        val metrics = SyncOverviewMetrics.from(emptyList(), emptyList(), syncing = false)
        assertNull(metrics.lastSyncAtMillis)
        assertNull(metrics.lastSyncStatus)
        assertEquals(0, metrics.uploaded)
    }
}
