package org.vovchenko.webdavsync.sync.control

import org.vovchenko.webdavsync.data.local.settings.AppSettings

/** Why a user-requested sync was enqueued but cannot run yet. */
enum class SyncWaitReason {
    WIFI,
    CHARGING,
}

/**
 * Result of the Sync button or the home-screen widget.
 * A running pass is left alone. A pass that has not started is replaced.
 */
sealed class UserSyncResult {
    /** WorkManager accepted the request and its constraints are already met. */
    data object Started : UserSyncResult()

    /** A sync worker is already running. The tap is a follow-up, not a second pass. */
    data object AlreadyRunning : UserSyncResult()

    /** The new request is queued and will run when every [reasons] entry is satisfied. */
    data class Waiting(val reasons: List<SyncWaitReason>) : UserSyncResult()
}

/**
 * Constraints that still block a manual sync. [bypassUnmetered] is the explicit
 * "sync now on mobile data" choice, and it does not skip the charging rule.
 */
fun manualSyncWaitReasons(
    settings: AppSettings,
    onUnmeteredNetwork: Boolean,
    charging: Boolean,
    bypassUnmetered: Boolean,
): List<SyncWaitReason> = buildList {
    if (settings.wifiOnly && !bypassUnmetered && !onUnmeteredNetwork) add(SyncWaitReason.WIFI)
    if (settings.onlyWhileCharging && !charging) add(SyncWaitReason.CHARGING)
}

object SyncUserMessage {
    const val ALREADY_RUNNING =
        "A sync is already in progress. Your latest changes will be included when it finishes."

    const val UNMETERED_OVERRIDE =
        "Sync is set to wait for Wi-Fi. Sync now on mobile data instead? The waiting sync will be replaced."

    fun waiting(reasons: List<SyncWaitReason>): String = when {
        SyncWaitReason.WIFI in reasons && SyncWaitReason.CHARGING in reasons ->
            "Sync will start when you are on Wi-Fi and the device is charging."
        SyncWaitReason.WIFI in reasons ->
            "Sync will start when you are on Wi-Fi."
        else ->
            "Sync will start when the device is charging."
    }
}
