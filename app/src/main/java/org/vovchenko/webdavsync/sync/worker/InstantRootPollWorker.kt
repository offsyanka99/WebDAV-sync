package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.LocalTreeFingerprint
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.domain.sync.IdleSyncPolicy
import org.vovchenko.webdavsync.sync.InstantWatchPolicy

/**
 * Wakes while instant upload is on and compares each watched folder's root snapshot to the
 * snapshot stored at the last full scan. A file copied by another app often never notifies
 * [ContentWatchWorker], so this poll is what starts the sync.
 */
@HiltWorker
class InstantRootPollWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val folderPairRepository: FolderPairRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val treeFingerprint: LocalTreeFingerprint,
    private val diagnosticLogger: DiagnosticLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = settingsRepository.settings.first()
        val watched = folderPairRepository.observeAll().first()
            .filter { InstantWatchPolicy.shouldWatch(it, settings) }
        if (watched.isEmpty()) {
            diagnosticLogger.i(TAG, "Instant poll stopped; nothing is watched")
            return Result.success()
        }
        for (pair in watched) {
            val cheap = runCatching { treeFingerprint.of(Uri.parse(pair.localFolderUri)) }.getOrDefault(0L)
            if (IdleSyncPolicy.cheapRootChanged(pair.lastCheapFingerprint, cheap)) {
                diagnosticLogger.i(TAG, "Instant poll root changed pairId=${pair.id}")
                syncScheduler.enqueueImmediateSync(pair.id)
            }
        }
        syncScheduler.scheduleNextInstantPoll()
        return Result.success()
    }

    private companion object {
        const val TAG = "InstantPoll"
    }
}
