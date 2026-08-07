package org.vovchenko.webdavsync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository

/**
 * 4×2 home-screen widget: last sync status, recent change counts, and a Sync button.
 */
class SyncWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = loadState(context)
                val views = SyncWidgetRenderer.build(context, state)
                for (id in appWidgetIds) {
                    appWidgetManager.updateAppWidget(id, views)
                }
            } finally {
                pending.finish()
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SYNC_NOW) {
            val pending = goAsync()
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    // Immediate feedback on the widget.
                    pushState(context, loadState(context).copy(syncing = true, status = "Syncing…"))
                    val entry = entryPoint(context)
                    entry.syncScheduler().enqueueImmediateSync()
                } finally {
                    pending.finish()
                }
            }
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        const val ACTION_SYNC_NOW = "org.vovchenko.webdavsync.widget.ACTION_SYNC_NOW"

        /** Refresh every placed instance from the database (call after a sync pass completes). */
        fun requestUpdate(context: Context) {
            val appContext = context.applicationContext
            val manager = AppWidgetManager.getInstance(appContext)
            val ids = manager.getAppWidgetIds(ComponentName(appContext, SyncWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val intent = Intent(appContext, SyncWidgetProvider::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            }
            appContext.sendBroadcast(intent)
        }

        /** Immediate “Syncing…” paint without waiting for WorkManager / DB. */
        fun showSyncing(context: Context) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    pushState(context, loadState(context).copy(syncing = true, status = "Syncing…"))
                }
            }
        }

        private fun entryPoint(context: Context): SyncWidgetEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, SyncWidgetEntryPoint::class.java)

        private suspend fun loadState(context: Context): SyncWidgetState {
            val entry = entryPoint(context)
            return loadState(entry.folderPairRepository(), entry.syncLogRepository())
        }

        suspend fun loadState(
            folderPairRepository: FolderPairRepository,
            syncLogRepository: SyncLogRepository,
        ): SyncWidgetState {
            val folderPairs = folderPairRepository.observeAll().first()
            val logs = syncLogRepository.observeRecent(100).first()
            val mostRecentPair = folderPairs.filter { it.lastSyncAt != null }.maxByOrNull { it.lastSyncAt!! }
            val latestSyncEndTimestamp = logs.firstOrNull { it.eventType == SyncEventType.SYNC_END }?.timestamp
            val latestBatch = logs.filter { it.timestamp == latestSyncEndTimestamp }
            return SyncWidgetState(
                status = mostRecentPair?.lastSyncStatus ?: "Ready",
                lastSyncAtMillis = mostRecentPair?.lastSyncAt,
                lastSyncDurationMs = mostRecentPair?.lastSyncDurationMs,
                uploaded = latestBatch.firstOrNull { it.eventType == SyncEventType.UPLOAD }?.fileCount ?: 0,
                downloaded = latestBatch.firstOrNull { it.eventType == SyncEventType.DOWNLOAD }?.fileCount ?: 0,
                deletedDevice = latestBatch.firstOrNull { it.eventType == SyncEventType.DELETE_DEVICE }?.fileCount ?: 0,
                deletedCloud = latestBatch.firstOrNull { it.eventType == SyncEventType.DELETE_CLOUD }?.fileCount ?: 0,
            )
        }

        private fun pushState(context: Context, state: SyncWidgetState) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, SyncWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = SyncWidgetRenderer.build(context, state)
            for (id in ids) {
                manager.updateAppWidget(id, views)
            }
        }
    }
}
