package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.SyncLogEntity
import org.vovchenko.webdavsync.data.model.SyncEventType

/**
 * Builds the Overview / widget "Recent changes" counters from [SyncLogEntity] rows.
 *
 * Prefer a worker-written **session summary** (one batch after all folder pairs finish) so
 * multi-pair syncs show the full totals. Fall back to the latest per-pair [SyncEventType.SYNC_END]
 * batch for older logs that predate session summaries.
 */
object RecentChangesCalculator {

    /** Marker written on the session-level [SyncEventType.SYNC_END] row. */
    const val SESSION_MESSAGE = "session"

    data class Counts(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val deletedDevice: Int = 0,
        val deletedCloud: Int = 0,
    )

    /**
     * @param logs newest-first (e.g. `ORDER BY timestamp DESC, id DESC`)
     */
    fun fromLogs(logs: List<SyncLogEntity>): Counts {
        if (logs.isEmpty()) return Counts()

        val sessionEnd = logs.firstOrNull {
            it.eventType == SyncEventType.SYNC_END && it.message == SESSION_MESSAGE
        }
        if (sessionEnd != null) {
            return batchCounts(logs, sessionEnd.timestamp, sessionEnd.folderPairId)
        }

        // Legacy multi-pair: sum each pair's most recent SYNC_END batch when those ends
        // form a tight chain (worker finishes pairs back-to-back).
        return sumChainedPairBatches(logs)
    }

    private fun sumChainedPairBatches(logs: List<SyncLogEntity>): Counts {
        val endsNewestFirst = logs.filter { it.eventType == SyncEventType.SYNC_END }
        if (endsNewestFirst.isEmpty()) return Counts()

        // One SYNC_END per pair (most recent first).
        val latestEndPerPair = LinkedHashMap<Long, SyncLogEntity>()
        for (end in endsNewestFirst) {
            if (end.folderPairId !in latestEndPerPair) {
                latestEndPerPair[end.folderPairId] = end
            }
        }
        val chained = mutableListOf<SyncLogEntity>()
        var previousTs: Long? = null
        // Newest-first chain: include while consecutive finish times are close.
        for (end in latestEndPerPair.values) {
            val prev = previousTs
            if (prev != null && prev - end.timestamp > MAX_PAIR_FINISH_GAP_MS) break
            chained.add(end)
            previousTs = end.timestamp
        }

        var uploaded = 0
        var downloaded = 0
        var deletedDevice = 0
        var deletedCloud = 0
        for (end in chained) {
            val c = batchCounts(logs, end.timestamp, end.folderPairId)
            uploaded += c.uploaded
            downloaded += c.downloaded
            deletedDevice += c.deletedDevice
            deletedCloud += c.deletedCloud
        }
        return Counts(uploaded, downloaded, deletedDevice, deletedCloud)
    }

    private fun batchCounts(
        logs: List<SyncLogEntity>,
        timestamp: Long,
        folderPairId: Long,
    ): Counts {
        val batch = logs.filter { it.timestamp == timestamp && it.folderPairId == folderPairId }
        return Counts(
            uploaded = batch.filter { it.eventType == SyncEventType.UPLOAD }.sumOf { it.fileCount },
            downloaded = batch.filter { it.eventType == SyncEventType.DOWNLOAD }.sumOf { it.fileCount },
            deletedDevice = batch.filter { it.eventType == SyncEventType.DELETE_DEVICE }.sumOf { it.fileCount },
            deletedCloud = batch.filter { it.eventType == SyncEventType.DELETE_CLOUD }.sumOf { it.fileCount },
        )
    }

    /** Max gap between consecutive folder-pair finish times still treated as one worker pass. */
    private const val MAX_PAIR_FINISH_GAP_MS = 30 * 60 * 1000L
}
