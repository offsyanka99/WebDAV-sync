package org.vovchenko.webdavsync.sync.control

import androidx.work.Data
import kotlinx.coroutines.delay
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.sync.worker.SyncWorker
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What should run after the current session. A null pair means every enabled pair.
 * Requests made before [SyncControl.tryBeginSession] are ignored: that pass scans the
 * tree as it is now, so a second wake is wasted work.
 */
data class FollowUpRequest(
    val allPairs: Boolean = false,
    val pairIds: Set<Long> = emptySet(),
) {
    val isEmpty: Boolean get() = !allPairs && pairIds.isEmpty()

    fun with(pairId: Long?): FollowUpRequest = when {
        allPairs || pairId == null -> FollowUpRequest(allPairs = true)
        else -> copy(pairIds = pairIds + pairId)
    }

    fun toWorkInput(): Data {
        if (isEmpty || allPairs) return Data.EMPTY
        if (pairIds.size == 1) {
            return Data.Builder().putLong(SyncWorker.KEY_FOLDER_PAIR_ID, pairIds.first()).build()
        }
        return Data.Builder()
            .putLongArray(SyncWorker.KEY_FOLDER_PAIR_IDS, pairIds.toLongArray())
            .build()
    }
}

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
class SyncControl @Inject constructor(
    private val inFlightCalls: InFlightCallRegistry,
) {

    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val sessionActive = AtomicBoolean(false)
    private val followUpLock = Any()
    private var followUp = FollowUpRequest()

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
     * A follow-up recorded before this returns is dropped. The pass that just claimed the
     * session has not scanned yet, so it already includes those edits.
     */
    fun tryBeginSession(): Boolean {
        synchronized(followUpLock) {
            if (!sessionActive.compareAndSet(false, true)) return false
            reset()
            followUp = FollowUpRequest()
            return true
        }
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
     * Ends the active session. The result lists pairs that changed after the session started.
     * Empty when nothing arrived during the pass.
     */
    fun endSession(): FollowUpRequest {
        synchronized(followUpLock) {
            sessionActive.set(false)
            val pending = followUp
            followUp = FollowUpRequest()
            return pending
        }
    }

    /**
     * Ask for one more sync after the current session finishes instead of cancelling it.
     * Ignored when no session is active: a queued pass will scan the current tree itself.
     * A null [pairId] means every enabled pair.
     */
    fun requestFollowUpSync(pairId: Long? = null) {
        if (!sessionActive.get()) return
        synchronized(followUpLock) {
            if (!sessionActive.get()) return
            followUp = followUp.with(pairId)
        }
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
        inFlightCalls.cancelAll()
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
