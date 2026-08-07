package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.vovchenko.webdavsync.data.local.SyncLogEntity
import org.vovchenko.webdavsync.data.model.SyncEventType

class RecentChangesCalculatorTest {

    @Test
    fun emptyLogs_zeroCounts() {
        assertEquals(RecentChangesCalculator.Counts(), RecentChangesCalculator.fromLogs(emptyList()))
    }

    @Test
    fun sessionSummary_preferredOverPairBatches() {
        // Older pair batch (would look like only pair 2 if session were ignored).
        val pairOnly = listOf(
            entry(2, 2000, SyncEventType.SYNC_END, 0, "uploaded 0…"),
            entry(2, 2000, SyncEventType.UPLOAD, 0),
            entry(1, 1000, SyncEventType.SYNC_END, 5, "uploaded 5…"),
            entry(1, 1000, SyncEventType.UPLOAD, 5),
        )
        // Session summary written last (newest-first in the list).
        val logs = listOf(
            entry(2, 3000, SyncEventType.SYNC_END, 7, RecentChangesCalculator.SESSION_MESSAGE),
            entry(2, 3000, SyncEventType.DELETE_CLOUD, 0),
            entry(2, 3000, SyncEventType.DELETE_DEVICE, 0),
            entry(2, 3000, SyncEventType.DOWNLOAD, 2),
            entry(2, 3000, SyncEventType.UPLOAD, 5),
        ) + pairOnly

        val counts = RecentChangesCalculator.fromLogs(logs)
        assertEquals(5, counts.uploaded)
        assertEquals(2, counts.downloaded)
        assertEquals(0, counts.deletedDevice)
        assertEquals(0, counts.deletedCloud)
    }

    @Test
    fun sessionSummary_withZeros_clearsPreviousActivity() {
        val logs = listOf(
            entry(1, 5000, SyncEventType.SYNC_END, 0, RecentChangesCalculator.SESSION_MESSAGE),
            entry(1, 5000, SyncEventType.DELETE_CLOUD, 0),
            entry(1, 5000, SyncEventType.DELETE_DEVICE, 0),
            entry(1, 5000, SyncEventType.DOWNLOAD, 0),
            entry(1, 5000, SyncEventType.UPLOAD, 0),
            // Stale previous session should be ignored.
            entry(1, 1000, SyncEventType.SYNC_END, 9, RecentChangesCalculator.SESSION_MESSAGE),
            entry(1, 1000, SyncEventType.UPLOAD, 9),
        )
        assertEquals(RecentChangesCalculator.Counts(), RecentChangesCalculator.fromLogs(logs))
    }

    @Test
    fun legacyMultiPair_sumsChainedBatches() {
        // No session marker: pair A then pair B finished a few seconds apart.
        val logs = listOf(
            entry(2, 2000, SyncEventType.SYNC_END, 3, "pair B"),
            entry(2, 2000, SyncEventType.DOWNLOAD, 3),
            entry(2, 2000, SyncEventType.UPLOAD, 0),
            entry(1, 1500, SyncEventType.SYNC_END, 5, "pair A"),
            entry(1, 1500, SyncEventType.DOWNLOAD, 0),
            entry(1, 1500, SyncEventType.UPLOAD, 5),
        )
        val counts = RecentChangesCalculator.fromLogs(logs)
        assertEquals(5, counts.uploaded)
        assertEquals(3, counts.downloaded)
    }

    @Test
    fun legacy_quietLastPair_stillKeepsEarlierPairCounts() {
        // Bug that broke Overview: last pair wrote only SYNC_END / zeros; UI showed 0 everywhere.
        val logs = listOf(
            entry(2, 2000, SyncEventType.SYNC_END, 0, "nothing"),
            entry(2, 2000, SyncEventType.DELETE_CLOUD, 0),
            entry(2, 2000, SyncEventType.DELETE_DEVICE, 0),
            entry(2, 2000, SyncEventType.DOWNLOAD, 0),
            entry(2, 2000, SyncEventType.UPLOAD, 0),
            entry(1, 1000, SyncEventType.SYNC_END, 4, "uploads"),
            entry(1, 1000, SyncEventType.UPLOAD, 4),
        )
        val counts = RecentChangesCalculator.fromLogs(logs)
        assertEquals(4, counts.uploaded)
        assertEquals(0, counts.downloaded)
    }

    @Test
    fun deletes_countedSeparately() {
        val logs = listOf(
            entry(1, 1000, SyncEventType.SYNC_END, 5, RecentChangesCalculator.SESSION_MESSAGE),
            entry(1, 1000, SyncEventType.DELETE_CLOUD, 2),
            entry(1, 1000, SyncEventType.DELETE_DEVICE, 3),
            entry(1, 1000, SyncEventType.DOWNLOAD, 0),
            entry(1, 1000, SyncEventType.UPLOAD, 0),
        )
        val counts = RecentChangesCalculator.fromLogs(logs)
        assertEquals(0, counts.uploaded)
        assertEquals(0, counts.downloaded)
        assertEquals(3, counts.deletedDevice)
        assertEquals(2, counts.deletedCloud)
    }

    private fun entry(
        pairId: Long,
        timestamp: Long,
        type: SyncEventType,
        count: Int,
        message: String? = null,
    ) = SyncLogEntity(
        id = 0,
        folderPairId = pairId,
        timestamp = timestamp,
        eventType = type,
        fileCount = count,
        message = message,
    )
}
