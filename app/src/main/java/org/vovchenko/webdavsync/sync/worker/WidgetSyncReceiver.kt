package org.vovchenko.webdavsync.sync.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vovchenko.webdavsync.MainActivity
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.ManualSyncDecision
import org.vovchenko.webdavsync.sync.control.ManualSyncStarter
import org.vovchenko.webdavsync.sync.control.SyncRequestToken
import org.vovchenko.webdavsync.sync.control.SyncUserMessage
import org.vovchenko.webdavsync.sync.control.UserSyncResult
import org.vovchenko.webdavsync.widget.SyncWidgetProvider
import javax.inject.Inject

/**
 * Sync button target. Not exported, so only this app's widget PendingIntent can start a sync.
 */
@AndroidEntryPoint
class WidgetSyncReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var syncScheduler: SyncScheduler
    @Inject lateinit var syncRequestToken: SyncRequestToken

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_SYNC_NOW) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.settings.first()
                when (ManualSyncStarter.prepareManualSync(context, settings)) {
                    ManualSyncDecision.NeedsMobileDataConfirm,
                    ManualSyncDecision.NeedsUnmeteredOverride,
                    -> openOverviewForSync(context)
                    ManualSyncDecision.Proceed -> when (
                        val result = syncScheduler.enqueueUserSync(bypassUnmetered = false)
                    ) {
                        UserSyncResult.Started -> Unit
                        UserSyncResult.AlreadyRunning ->
                            showToast(context, SyncUserMessage.ALREADY_RUNNING)
                        is UserSyncResult.Waiting ->
                            showToast(context, SyncUserMessage.waiting(result.reasons))
                    }
                }
                SyncWidgetProvider.requestUpdate(context)
            } finally {
                pending.finish()
            }
        }
    }

    private fun openOverviewForSync(context: Context) {
        val open = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_REQUEST_SYNC, true)
            putExtra(SyncRequestToken.EXTRA, syncRequestToken.value)
        }
        context.startActivity(open)
    }

    private suspend fun showToast(context: Context, text: String) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "org.vovchenko.webdavsync.widget.ACTION_SYNC_NOW"
    }
}
