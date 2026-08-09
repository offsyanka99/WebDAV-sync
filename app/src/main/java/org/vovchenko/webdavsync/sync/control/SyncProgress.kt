package org.vovchenko.webdavsync.sync.control

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory live counters for the current sync pass (Overview "Recent changes" while syncing).
 * Not persisted — no Room writes per file. Thread-safe for parallel [TransferExecutor] workers.
 */
@Singleton
class SyncProgress @Inject constructor() {

    data class Counts(
        val uploaded: Int = 0,
        val downloaded: Int = 0,
        val deletedDevice: Int = 0,
        val deletedCloud: Int = 0,
        /** True between [beginPass] and [endPass]. */
        val active: Boolean = false,
    )

    private val active = AtomicBoolean(false)
    private val uploaded = AtomicInteger(0)
    private val downloaded = AtomicInteger(0)
    private val deletedDevice = AtomicInteger(0)
    private val deletedCloud = AtomicInteger(0)

    private val _counts = MutableStateFlow(Counts())
    val counts: StateFlow<Counts> = _counts.asStateFlow()

    /** Call at the start of a worker session (after [SyncControl.tryBeginSession]). */
    fun beginPass() {
        uploaded.set(0)
        downloaded.set(0)
        deletedDevice.set(0)
        deletedCloud.set(0)
        active.set(true)
        publish()
    }

    /** Call when the worker session ends (before or after [SyncControl.endSession]). */
    fun endPass() {
        active.set(false)
        publish()
    }

    fun recordUploaded(n: Int = 1) {
        if (!active.get() || n <= 0) return
        uploaded.addAndGet(n)
        publish()
    }

    fun recordDownloaded(n: Int = 1) {
        if (!active.get() || n <= 0) return
        downloaded.addAndGet(n)
        publish()
    }

    fun recordDeletedDevice(n: Int = 1) {
        if (!active.get() || n <= 0) return
        deletedDevice.addAndGet(n)
        publish()
    }

    fun recordDeletedCloud(n: Int = 1) {
        if (!active.get() || n <= 0) return
        deletedCloud.addAndGet(n)
        publish()
    }

    private fun snapshot(): Counts = Counts(
        uploaded = uploaded.get(),
        downloaded = downloaded.get(),
        deletedDevice = deletedDevice.get(),
        deletedCloud = deletedCloud.get(),
        active = active.get(),
    )

    /**
     * Always publish a fresh snapshot. Compose + [stateIn] handle frequent updates fine for
     * file-count progress; avoid timer-based throttle so unit tests and last-file counts stay exact.
     * Concurrent publishers may overwrite with a slightly stale snapshot — CAS loop keeps final
     * values consistent with atomics.
     */
    private fun publish() {
        // Re-read atomics after setting so a concurrent increment is not lost forever.
        var spins = 0
        while (spins++ < 8) {
            val snap = snapshot()
            _counts.value = snap
            if (snap == snapshot()) break
        }
    }
}
