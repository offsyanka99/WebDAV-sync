package org.vovchenko.webdavsync.sync.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import javax.inject.Inject

/** Reschedules periodic sync after a reboot, if "Auto-start on boot" is enabled (plan Phase 5). */
@AndroidEntryPoint
class BootCompletedReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                diagnosticLogger.i(
                    "BootCompleted",
                    "BOOT_COMPLETED autoStartOnBoot=${settings.autoStartOnBoot} autoSync=${settings.autoSyncEnabled}",
                )
                if (settings.autoStartOnBoot) {
                    syncScheduler.reschedulePeriodicSync()
                    diagnosticLogger.i("BootCompleted", "Periodic sync rescheduled after reboot")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
