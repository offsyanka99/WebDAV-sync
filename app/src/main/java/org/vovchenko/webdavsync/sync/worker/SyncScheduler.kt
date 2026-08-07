package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** (Re)schedules the periodic sync work and enqueues one-off manual/instant syncs (plan Phase 5). */
@Singleton
class SyncScheduler @Inject constructor(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
) {
    private val workManager get() = WorkManager.getInstance(context)

    /** Call whenever auto-sync settings change (autoSyncEnabled, interval, wifiOnly, onlyWhileCharging). */
    suspend fun reschedulePeriodicSync() {
        val settings = settingsRepository.settings.first()
        if (!settings.autoSyncEnabled) {
            workManager.cancelUniqueWork(SyncWorker.UNIQUE_PERIODIC_WORK_NAME)
            return
        }

        val intervalMinutes = settings.autoSyncIntervalMinutes.coerceAtLeast(MIN_INTERVAL_MINUTES)
        val request = PeriodicWorkRequestBuilder<SyncWorker>(intervalMinutes.toLong(), TimeUnit.MINUTES)
            .setConstraints(buildConstraints(settings))
            .build()

        workManager.enqueueUniquePeriodicWork(
            SyncWorker.UNIQUE_PERIODIC_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    /** Triggers an immediate sync — either one folder pair (manual "Sync" action) or all enabled pairs. */
    suspend fun enqueueImmediateSync(folderPairId: Long? = null) {
        val inputData = folderPairId?.let {
            Data.Builder().putLong(SyncWorker.KEY_FOLDER_PAIR_ID, it).build()
        } ?: Data.EMPTY

        // Security audit finding #7: manual syncs must respect the same Wi-Fi-only/charging
        // constraints as scheduled syncs, otherwise "Wi-Fi only" can be silently bypassed.
        val settings = settingsRepository.settings.first()
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setInputData(inputData)
            .setConstraints(buildConstraints(settings))
            .build()

        workManager.enqueueUniqueWork(SyncWorker.UNIQUE_MANUAL_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
    }

    private fun buildConstraints(settings: AppSettings): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(settings.onlyWhileCharging)
        .build()

    private companion object {
        const val MIN_INTERVAL_MINUTES = 15 // androidx.work.PeriodicWorkRequest's enforced floor
    }
}
