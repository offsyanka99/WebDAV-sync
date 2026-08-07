package org.vovchenko.webdavsync.widget

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
)
