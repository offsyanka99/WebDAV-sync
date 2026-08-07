package org.vovchenko.webdavsync

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.sync.FolderChangeCoordinator
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject

@HiltAndroidApp
class WebDavSyncApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var syncScheduler: SyncScheduler
    /** Eager init so the settings collector starts with the process. */
    @Inject lateinit var diagnosticLogger: DiagnosticLogger
    @Inject lateinit var folderChangeCoordinator: FolderChangeCoordinator

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Ensures periodic sync reflects current settings even if they were changed while the app was killed.
        CoroutineScope(Dispatchers.Default).launch {
            syncScheduler.reschedulePeriodicSync()
            diagnosticLogger.i("App", "Process start version=${BuildConfig.VERSION_NAME}")
        }
        folderChangeCoordinator.start()
    }
}
