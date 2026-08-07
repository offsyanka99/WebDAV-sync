package org.vovchenko.webdavsync.sync.control

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cooperative pause/cancel state for an in-flight sync (plan Phase 11 notification actions).
 * Reset at the start of each [org.vovchenko.webdavsync.sync.worker.SyncWorker] run.
 */
@Singleton
class SyncControl @Inject constructor() {

    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    /** True when the worker should stop starting new work (cancelled). */
    fun shouldStop(): Boolean = cancelled.get()

    fun reset() {
        paused.set(false)
        cancelled.set(false)
    }

    fun pause() {
        paused.set(true)
    }

    fun resume() {
        paused.set(false)
    }

    /**
     * Request cancel. Also clears pause so any [awaitWhilePaused] waiter can observe the cancel
     * and exit promptly.
     */
    fun cancel() {
        cancelled.set(true)
        paused.set(false)
    }

    /**
     * Suspends while paused (unless cancelled). Call at safe checkpoints between folder pairs
     * and transfers. [externalStop] is typically `isStopped` from the worker.
     */
    suspend fun awaitWhilePaused(externalStop: () -> Boolean = { false }) {
        while (paused.get() && !cancelled.get() && !externalStop()) {
            delay(PAUSE_POLL_MS)
        }
    }

    private companion object {
        const val PAUSE_POLL_MS = 200L
    }
}
