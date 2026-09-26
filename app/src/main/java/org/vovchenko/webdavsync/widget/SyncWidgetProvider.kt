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
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.domain.sync.SyncOverviewMetrics

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
        // Sync taps go to the non-exported WidgetSyncReceiver. Ignore a forged copy of that action.
        if (intent.action == org.vovchenko.webdavsync.sync.worker.WidgetSyncReceiver.ACTION_SYNC_NOW) {
            return
        }
        super.onReceive(context, intent)
    }

    companion object {
        /**
         * Refresh every placed instance from DB + whether a worker is RUNNING.
         * Same rules as Overview — do not force a sticky "Syncing…" paint.
         *
         * @param forceIdle When true (worker `finally`), paint as not syncing even if WorkManager
         * still reports this worker RUNNING for a moment. Otherwise the widget freezes on
         * "Sync in process..." while Overview already flipped to ERROR/OK after the pass wrote DB.
         * The next worker's start calls [requestUpdate] again and shows syncing if needed.
         */
        fun requestUpdate(context: Context, forceIdle: Boolean = false) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { refreshNow(context, forceIdle = forceIdle) }
            }
        }

        /** @see requestUpdate */
        fun showSyncing(context: Context) = requestUpdate(context, forceIdle = false)

        private suspend fun refreshNow(context: Context, forceIdle: Boolean = false) {
            pushState(context, loadState(context, forceIdle = forceIdle))
        }

        private fun entryPoint(context: Context): SyncWidgetEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, SyncWidgetEntryPoint::class.java)

        private suspend fun loadState(context: Context, forceIdle: Boolean = false): SyncWidgetState {
            val entry = entryPoint(context)
            val syncing = if (forceIdle) false else entry.syncScheduler().isSyncWorkerRunning()
            return loadState(entry.folderPairRepository(), entry.syncLogRepository(), syncing = syncing)
        }

        suspend fun loadState(
            folderPairRepository: FolderPairRepository,
            syncLogRepository: SyncLogRepository,
            syncing: Boolean = false,
        ): SyncWidgetState {
            val folderPairs = folderPairRepository.observeAll().first()
            val logs = syncLogRepository.observeRecent(100).first()
            return SyncWidgetState.from(SyncOverviewMetrics.from(folderPairs, logs, syncing = syncing))
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
