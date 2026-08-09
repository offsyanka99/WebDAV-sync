package org.vovchenko.webdavsync.sync.control

import android.content.Context
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.util.NetworkStatus

/**
 * Shared decision for user-triggered (manual) sync from Overview or the home widget.
 * Background / folder-watch / periodic paths do not use this gate.
 */
sealed class ManualSyncDecision {
    /** Safe to enqueue [org.vovchenko.webdavsync.sync.worker.SyncScheduler.enqueueImmediateSync] now. */
    data object Proceed : ManualSyncDecision()

    /** Show the mobile-data warning UI; only enqueue after the user confirms. */
    data object NeedsMobileDataConfirm : ManualSyncDecision()
}

object ManualSyncStarter {
    /**
     * @return [ManualSyncDecision.Proceed] or [ManualSyncDecision.NeedsMobileDataConfirm]
     * when settings ask to warn on cellular.
     */
    fun prepareManualSync(context: Context, settings: AppSettings): ManualSyncDecision {
        if (settings.warnOnMobileNetwork && NetworkStatus.isOnCellularData(context)) {
            return ManualSyncDecision.NeedsMobileDataConfirm
        }
        return ManualSyncDecision.Proceed
    }
}
