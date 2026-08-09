package org.vovchenko.webdavsync.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.MainActivity
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.domain.sync.RecentChangesCalculator
import org.vovchenko.webdavsync.sync.worker.SyncWorker
import org.vovchenko.webdavsync.util.NetworkStatus

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
                    val entry = entryPoint(context)
                    val settings = entry.settingsRepository().settings.first()
                    // Widget cannot host the Compose dialog — open Overview so the same warning runs.
                    if (settings.warnOnMobileNetwork && NetworkStatus.isOnCellularData(context)) {
                        val open = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(MainActivity.EXTRA_REQUEST_SYNC, true)
                        }
                        context.startActivity(open)
                        return@launch
                    }
                    entry.syncScheduler().enqueueImmediateSync()
                    // Paint from DB + WorkManager only — never force "Syncing…" that can outlive the worker.
                    refreshNow(context)
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
            val syncing = if (forceIdle) false else isSyncWorkerRunning(context)
            return loadState(entry.folderPairRepository(), entry.syncLogRepository(), syncing = syncing)
        }

        /**
         * Aligns with Overview: only RUNNING workers count as syncing, so a finished pass that left
         * an ENQUEUED follow-up waiting on Wi‑Fi does not leave the widget/Overview disagreeing.
         */
        private fun isSyncWorkerRunning(context: Context): Boolean {
            val wm = WorkManager.getInstance(context.applicationContext)
            fun anyRunning(uniqueName: String): Boolean =
                runCatching { wm.getWorkInfosForUniqueWork(uniqueName).get() }
                    .getOrDefault(emptyList())
                    .any { it.state == WorkInfo.State.RUNNING }
            return anyRunning(SyncWorker.UNIQUE_MANUAL_WORK_NAME) ||
                anyRunning(SyncWorker.UNIQUE_PERIODIC_WORK_NAME)
        }

        suspend fun loadState(
            folderPairRepository: FolderPairRepository,
            syncLogRepository: SyncLogRepository,
            syncing: Boolean = false,
        ): SyncWidgetState {
            val folderPairs = folderPairRepository.observeAll().first()
            val logs = syncLogRepository.observeRecent(100).first()
            val mostRecentPair = folderPairs.filter { it.lastSyncAt != null }.maxByOrNull { it.lastSyncAt!! }
            val recent = RecentChangesCalculator.fromLogs(logs)
            return SyncWidgetState(
                status = mostRecentPair?.lastSyncStatus ?: "Ready",
                lastSyncAtMillis = mostRecentPair?.lastSyncAt,
                lastSyncDurationMs = mostRecentPair?.lastSyncDurationMs,
                uploaded = recent.uploaded,
                downloaded = recent.downloaded,
                deletedDevice = recent.deletedDevice,
                deletedCloud = recent.deletedCloud,
                syncing = syncing,
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
