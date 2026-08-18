package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.InstantWatchPolicy

/**
 * Fires when JobScheduler reports a SAF content-URI change for a watched folder pair.
 * Enqueues a constrained [SyncWorker] and re-arms the one-shot content-URI watch.
 */
@HiltWorker
class ContentWatchWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderPairRepository: FolderPairRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val diagnosticLogger: DiagnosticLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val pairId = inputData.getLong(KEY_FOLDER_PAIR_ID, -1L)
        if (pairId < 0L) return Result.success()

        diagnosticLogger.i(TAG, "Content URI trigger pairId=$pairId")
        val pair = folderPairRepository.observeById(pairId).first()
        val settings = settingsRepository.settings.first()
        val stillWatching = pair != null && InstantWatchPolicy.shouldWatch(pair, settings)

        // APPEND while this unique work is still RUNNING — REPLACE would cancel us.
        if (stillWatching && pair != null) {
            syncScheduler.armContentWatch(pair, ExistingWorkPolicy.APPEND)
        }

        if (stillWatching) {
            syncScheduler.enqueueImmediateSync(pairId)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "ContentWatch"
        const val KEY_FOLDER_PAIR_ID = "folder_pair_id"
    }
}
