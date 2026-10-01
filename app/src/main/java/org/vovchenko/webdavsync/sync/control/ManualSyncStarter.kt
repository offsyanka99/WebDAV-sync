package org.vovchenko.webdavsync.sync.control

import android.content.Context
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.util.NetworkStatus

/**
 * Shared decision for user-triggered (manual) sync from Overview or the home widget.
 * Background / folder-watch / periodic paths do not use this gate.
 */
sealed class ManualSyncDecision {
    /** Safe to enqueue a user sync now, still under Wi-Fi only and charging. */
    data object Proceed : ManualSyncDecision()

    /** Show the mobile-data warning UI; only enqueue after the user confirms. */
    data object NeedsMobileDataConfirm : ManualSyncDecision()

    /**
     * Wi-Fi only is on and the device is on cellular. Ask before this one sync
     * uses mobile data. Waiting for Wi-Fi still enqueues under the Wi-Fi rule.
     */
    data object NeedsUnmeteredOverride : ManualSyncDecision()
}

object ManualSyncStarter {
    /**
     * @return [ManualSyncDecision.NeedsUnmeteredOverride] when Wi-Fi only would hold this sync
     * on cellular, [ManualSyncDecision.NeedsMobileDataConfirm] when the mobile-data warning is on,
     * otherwise [ManualSyncDecision.Proceed].
     */
    fun prepareManualSync(context: Context, settings: AppSettings): ManualSyncDecision =
        decide(settings, onCellularData = NetworkStatus.isOnCellularData(context))

    fun decide(settings: AppSettings, onCellularData: Boolean): ManualSyncDecision = when {
        settings.wifiOnly && onCellularData -> ManualSyncDecision.NeedsUnmeteredOverride
        settings.warnOnMobileNetwork && onCellularData -> ManualSyncDecision.NeedsMobileDataConfirm
        else -> ManualSyncDecision.Proceed
    }
}
