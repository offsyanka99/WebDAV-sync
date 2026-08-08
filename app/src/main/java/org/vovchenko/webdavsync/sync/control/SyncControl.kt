package org.vovchenko.webdavsync.sync.control

import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cooperative pause/cancel state for an in-flight sync (plan Phase 11 notification actions),
 * plus single-flight session tracking so instant-upload / folder-watch cannot REPLACE a running
 * worker mid-transfer (that cancelled large downloads and re-ran as uploads → duplicates).
 *
 * Manual and periodic WorkManager unique works are independent and can start in parallel; only
 * one may own a session ([tryBeginSession]) so two passes never download the same remote file
 * into SAF at once (which creates `name (1).ext` ghosts that the next pass uploads).
 */
@Singleton
class SyncControl @Inject constructor() {

    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private val followUpRequested = AtomicBoolean(false)

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    /** True while [org.vovchenko.webdavsync.sync.worker.SyncWorker] is inside a sync pass. */
    val isSessionActive: Boolean get() = sessionActive.get()

    /** True when the worker should stop starting new work (cancelled). */
    fun shouldStop(): Boolean = cancelled.get()

    fun reset() {
        paused.set(false)
        cancelled.set(false)
    }

    /**
     * Atomically claims the sync session if idle. Returns false when another worker already owns
     * the pass — the caller must [requestFollowUpSync] and exit without transferring files.
     *
     * Does not clear [requestFollowUpSync] — requests made while work was only ENQUEUED
     * (before this session started) must still run after [endSession].
     */
    fun tryBeginSession(): Boolean {
        if (!sessionActive.compareAndSet(false, true)) return false
        reset()
        return true
    }

    /**
     * Marks a worker pass as active and clears pause/cancel (unconditional). Prefer
     * [tryBeginSession] in production workers so concurrent manual/periodic work cannot both run.
     */
    fun beginSession() {
        reset()
        sessionActive.set(true)
    }

    /**
     * Ends the active session. Returns whether a deferred sync was requested while this pass ran
     * (folder changes during download/upload, or enqueue attempts that were coalesced).
     */
    fun endSession(): Boolean {
        sessionActive.set(false)
        return followUpRequested.getAndSet(false)
    }

    /**
     * Ask for one more sync after the current session finishes instead of cancelling it.
     * Safe to call when no session is active (consumed by the next [endSession] or ignored).
     */
    fun requestFollowUpSync() {
        followUpRequested.set(true)
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
