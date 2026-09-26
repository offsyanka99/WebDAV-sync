package org.vovchenko.webdavsync.sync.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.MainActivity
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.ManualSyncDecision
import org.vovchenko.webdavsync.sync.control.ManualSyncStarter
import org.vovchenko.webdavsync.sync.control.SyncRequestToken
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
                    ManualSyncDecision.NeedsMobileDataConfirm -> {
                        val open = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_REQUEST_SYNC, true)
                            putExtra(SyncRequestToken.EXTRA, syncRequestToken.value)
                        }
                        context.startActivity(open)
                    }
                    ManualSyncDecision.Proceed -> syncScheduler.enqueueImmediateSync()
                }
                SyncWidgetProvider.requestUpdate(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_SYNC_NOW = "org.vovchenko.webdavsync.widget.ACTION_SYNC_NOW"
    }
}
