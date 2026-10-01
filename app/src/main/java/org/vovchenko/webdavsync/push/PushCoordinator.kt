package org.vovchenko.webdavsync.push

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRegistrationKey
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps push registration rows in line with the folder pairs and settings, and schedules the
 * network work. Same pattern as [org.vovchenko.webdavsync.sync.FolderChangeCoordinator].
 */
@Singleton
class PushCoordinator @Inject constructor(
    private val folderPairRepository: FolderPairRepository,
    private val settingsRepository: SettingsRepository,
    private val pushRepository: PushRepository,
    private val scheduler: SyncScheduler,
    private val diagnosticLogger: DiagnosticLogger,
    private val scope: CoroutineScope,
) {
    private data class Surface(
        val keys: Set<PushRegistrationKey>,
        val enabled: Boolean,
        /** Maintenance runs on the sync network type (plan D14). */
        val wifiOnly: Boolean,
    )

    fun start(
        pairs: Flow<List<FolderPairEntity>> = folderPairRepository.observeAll(),
        settings: Flow<AppSettings> = settingsRepository.settings,
    ) {
        scope.launch {
            combine(pairs, settings) { folderPairs, appSettings ->
                Surface(
                    keys = PushRegistrationPlanner.desired(folderPairs, appSettings).keys,
                    enabled = appSettings.instantDownloadEnabled,
                    wifiOnly = appSettings.wifiOnly,
                )
            }
                .distinctUntilChanged()
                .collect { surface -> apply(surface) }
        }
    }

    private suspend fun apply(surface: Surface) {
        try {
            if (surface.enabled) scheduler.ensurePushMaintenance() else scheduler.cancelPushMaintenance()
            if (pushRepository.applyDesired(surface.keys)) {
                diagnosticLogger.i(TAG, "Push registrations changed (${surface.keys.size} wanted); reconcile queued")
                scheduler.enqueuePushReconcile()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A pair or account deleted mid-update can fail the insert; the next change retries.
            diagnosticLogger.e(TAG, "Push desired-set update failed", e)
        }
    }

    private companion object {
        const val TAG = "Push"
    }
}
