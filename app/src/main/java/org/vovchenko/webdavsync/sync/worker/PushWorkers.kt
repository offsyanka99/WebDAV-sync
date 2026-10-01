package org.vovchenko.webdavsync.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.push.PushRegistrationManager

/** Unique `push_reconcile`: applies the desired push registrations on the server. */
@HiltWorker
class PushReconcileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: PushRegistrationManager,
    private val diagnosticLogger: DiagnosticLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val outcome = manager.reconcile()
        if (outcome.retry && runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        diagnosticLogger.e(TAG, "Push reconcile failed attempt=$runAttemptCount", e)
        if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "Push"
        const val MAX_ATTEMPTS = 5
    }
}

/** Unique periodic `push_maintenance`: daily distributor check, renewals, and key checks. */
@HiltWorker
class PushMaintenanceWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val manager: PushRegistrationManager,
    private val diagnosticLogger: DiagnosticLogger,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        manager.maintenance()
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        diagnosticLogger.e(TAG, "Push maintenance failed", e)
        Result.success()
    }

    private companion object {
        const val TAG = "Push"
    }
}
