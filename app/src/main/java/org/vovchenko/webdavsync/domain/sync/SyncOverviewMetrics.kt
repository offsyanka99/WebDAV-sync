package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.SyncLogEntity

/**
 * Shared Overview + home-widget snapshot: last finished pair + recent-change counts.
 * Built once so the two surfaces cannot drift (status, duration, counters).
 */
data class SyncOverviewMetrics(
    val lastSyncAtMillis: Long? = null,
    val lastSyncDurationMs: Long? = null,
    val lastSyncStatus: String? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedDevice: Int = 0,
    val deletedCloud: Int = 0,
    val syncing: Boolean = false,
) {
    companion object {
        fun from(
            folderPairs: List<FolderPairEntity>,
            logs: List<SyncLogEntity>,
            syncing: Boolean = false,
        ): SyncOverviewMetrics {
            val mostRecentPair = folderPairs
                .filter { it.lastSyncAt != null }
                .maxByOrNull { it.lastSyncAt!! }
            val recent = RecentChangesCalculator.fromLogs(logs)
            return SyncOverviewMetrics(
                lastSyncAtMillis = mostRecentPair?.lastSyncAt,
                lastSyncDurationMs = mostRecentPair?.lastSyncDurationMs,
                lastSyncStatus = mostRecentPair?.lastSyncStatus,
                uploaded = recent.uploaded,
                downloaded = recent.downloaded,
                deletedDevice = recent.deletedDevice,
                deletedCloud = recent.deletedCloud,
                syncing = syncing,
            )
        }
    }
}
