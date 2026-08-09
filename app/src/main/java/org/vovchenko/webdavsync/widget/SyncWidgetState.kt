package org.vovchenko.webdavsync.widget

import org.vovchenko.webdavsync.domain.sync.SyncOverviewMetrics

/** Snapshot of overview metrics shown on the home-screen widget. */
data class SyncWidgetState(
    val status: String = "Ready",
    val lastSyncAtMillis: Long? = null,
    val lastSyncDurationMs: Long? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedDevice: Int = 0,
    val deletedCloud: Int = 0,
    val syncing: Boolean = false,
) {
    companion object {
        fun from(metrics: SyncOverviewMetrics): SyncWidgetState = SyncWidgetState(
            status = metrics.lastSyncStatus ?: "Ready",
            lastSyncAtMillis = metrics.lastSyncAtMillis,
            lastSyncDurationMs = metrics.lastSyncDurationMs,
            uploaded = metrics.uploaded,
            downloaded = metrics.downloaded,
            deletedDevice = metrics.deletedDevice,
            deletedCloud = metrics.deletedCloud,
            syncing = metrics.syncing,
        )
    }
}
