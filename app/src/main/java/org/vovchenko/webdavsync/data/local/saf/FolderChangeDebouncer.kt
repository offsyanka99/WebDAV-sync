package org.vovchenko.webdavsync.data.local.saf

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debounces per-folder-pair change notifications so a burst of file events collapses into one
 * sync trigger after the configured short delay (plan §4.3, requirements.md intro).
 */
@Singleton
class FolderChangeDebouncer @Inject constructor(
    private val scope: CoroutineScope,
) {
    private val pendingJobs = mutableMapOf<Long, Job>()

    /** (Re)starts the debounce timer for [folderPairId]; each call resets the delay. */
    fun onChangeDetected(folderPairId: Long, delayMillis: Long, onDebounced: suspend (Long) -> Unit) {
        pendingJobs[folderPairId]?.cancel()
        pendingJobs[folderPairId] = scope.launch {
            delay(delayMillis)
            onDebounced(folderPairId)
            pendingJobs.remove(folderPairId)
        }
    }

    fun cancel(folderPairId: Long) {
        pendingJobs.remove(folderPairId)?.cancel()
    }
}
