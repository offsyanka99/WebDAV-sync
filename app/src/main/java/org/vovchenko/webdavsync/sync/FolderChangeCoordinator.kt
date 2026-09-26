package org.vovchenko.webdavsync.sync

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.FolderChangeDebouncer
import org.vovchenko.webdavsync.data.local.saf.FolderChangeObserver
import org.vovchenko.webdavsync.data.local.saf.LocalTreeFingerprint
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.sync.control.SyncControl
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Triggers sync on local folder changes for Instant upload / "immediately on local changes".
 *
 * Detection layers (cheapest first):
 * 1. SAF [ContentObserver] while this process is alive (event-driven).
 * 2. WorkManager content-URI trigger ([org.vovchenko.webdavsync.sync.worker.ContentWatchWorker])
 *    so a change can wake the app after process death.
 * 3. A slow **non-recursive** fingerprint poll as a fallback for providers that never notify.
 * 4. Periodic [org.vovchenko.webdavsync.sync.worker.SyncWorker] as the durable safety net.
 *
 * The poller must not walk the whole tree. Nested edits that miss the cheap fingerprint are
 * picked up by the observer, the content-URI job, or the next periodic pass.
 *
 * While a [SyncControl] session is active, fingerprint changes are absorbed (downloads write local
 * files) and at most one follow-up sync is requested — never a mid-transfer REPLACE.
 */
@Singleton
class FolderChangeCoordinator @Inject constructor(
    private val context: Context,
    private val folderPairRepository: FolderPairRepository,
    private val settingsRepository: SettingsRepository,
    private val debouncer: FolderChangeDebouncer,
    private val syncScheduler: SyncScheduler,
    private val syncControl: SyncControl,
    private val treeFingerprint: LocalTreeFingerprint,
    private val diagnosticLogger: DiagnosticLogger,
    private val scope: CoroutineScope,
) {
    private val observers = ConcurrentHashMap<Long, FolderChangeObserver>()
    private val fingerprints = ConcurrentHashMap<Long, Long>()
    private val lastTriggerAt = ConcurrentHashMap<Long, Long>()
    /** Pairs whose in-process observer failed to register. The cheap poll covers only these. */
    private val pollPairIds = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
    private var pollJob: Job? = null
    @Volatile private var watchedPairs: Map<Long, FolderPairEntity> = emptyMap()

    fun start(
        pairs: Flow<List<FolderPairEntity>> = folderPairRepository.observeAll(),
        settings: Flow<AppSettings> = settingsRepository.settings,
    ) {
        scope.launch {
            combine(pairs, settings) { folderPairs, appSettings ->
                folderPairs.filter { InstantWatchPolicy.shouldWatch(it, appSettings) }.associateBy { it.id }
            }
                .distinctUntilChanged { a, b -> sameWatchSurface(a, b) }
                .collect { toWatch ->
                    watchedPairs = toWatch
                    reconcileObservers(toWatch)
                    syncScheduler.reconcileContentWatches(toWatch)
                }
        }
    }

    private fun reconcileObservers(toWatch: Map<Long, FolderPairEntity>) {
        val stale = observers.keys - toWatch.keys
        stale.forEach { id ->
            stopWatch(id)
            diagnosticLogger.i(TAG, "Stopped folder watch pairId=$id")
        }
        for ((id, pair) in toWatch) {
            val uri = runCatching { Uri.parse(pair.localFolderUri) }.getOrNull() ?: continue
            val existing = observers[id]
            if (existing != null && existing.treeUri == uri) continue
            existing?.let {
                it.stop()
                observers.remove(id)
            }
            val observer = FolderChangeObserver(
                contentResolver = context.contentResolver,
                treeUri = uri,
                onChange = { onLocalChange(id, "content-observer") },
            )
            runCatching { observer.start() }
                .onSuccess {
                    observers[id] = observer
                    pollPairIds.remove(id)
                    fingerprints[id] = fingerprint(pair)
                    diagnosticLogger.i(TAG, "Started folder watch pairId=$id (observer+content-uri)")
                }
                .onFailure {
                    pollPairIds.add(id)
                    diagnosticLogger.w(TAG, "ContentObserver failed pairId=$id, relying on content-uri+poll: ${it.message}")
                    fingerprints[id] = fingerprint(pair)
                }
        }
        ensurePoller(pollPairIds.isNotEmpty())
    }

    private fun stopWatch(id: Long) {
        observers.remove(id)?.stop()
        pollPairIds.remove(id)
        debouncer.cancel(id)
        fingerprints.remove(id)
    }

    private fun ensurePoller(needed: Boolean) {
        if (!needed) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            diagnosticLogger.i(TAG, "Local folder cheap poller started intervalMs=$CHEAP_POLL_MS")
            while (isActive) {
                delay(CHEAP_POLL_MS)
                for (id in pollPairIds.toList()) {
                    val pair = watchedPairs[id] ?: continue
                    val fp = fingerprint(pair)
                    val previous = fingerprints.put(id, fp)
                    if (previous != null && previous != fp) {
                        if (syncControl.isSessionActive) {
                            syncControl.requestFollowUpSync(id)
                        } else {
                            scheduleSync(id, "cheap-poll fingerprint change $previous→$fp")
                        }
                    }
                }
            }
        }
    }

    /** Hop off the ContentObserver thread before any fingerprint or WorkManager work. */
    private fun onLocalChange(folderPairId: Long, reason: String) {
        scope.launch { scheduleSync(folderPairId, reason) }
    }

    private suspend fun scheduleSync(folderPairId: Long, reason: String) {
        // Downloads/uploads rewrite the local tree; never start a competing worker mid-pass.
        if (syncControl.isSessionActive) {
            watchedPairs[folderPairId]?.let { fingerprints[folderPairId] = fingerprint(it) }
            syncControl.requestFollowUpSync(folderPairId)
            diagnosticLogger.i(TAG, "Defer local change during active sync pairId=$folderPairId ($reason)")
            return
        }

        // Ignore fingerprint noise right after a sync finishes (downloads change the local tree
        // and used to enqueue a near-empty follow-up sync that overwrote Duration with ~0s).
        val now = SystemClock.elapsedRealtime()
        val last = lastTriggerAt[folderPairId] ?: 0L
        if (now - last < COOLDOWN_MS) {
            watchedPairs[folderPairId]?.let { fingerprints[folderPairId] = fingerprint(it) }
            return
        }
        debouncer.onChangeDetected(folderPairId, DEBOUNCE_MS) { id ->
            if (syncControl.isSessionActive) {
                watchedPairs[id]?.let { fingerprints[id] = fingerprint(it) }
                syncControl.requestFollowUpSync(id)
                diagnosticLogger.i(TAG, "Defer debounced change during active sync pairId=$id ($reason)")
                return@onChangeDetected
            }
            lastTriggerAt[id] = SystemClock.elapsedRealtime()
            diagnosticLogger.i(TAG, "Local change → sync pairId=$id ($reason)")
            syncScheduler.enqueueImmediateSync(id)
        }
    }

    /** Call after a sync pass so the next poll does not treat transferred files as a new change. */
    fun reseedAfterSync(folderPairIds: Collection<Long>) {
        val now = SystemClock.elapsedRealtime()
        for (id in folderPairIds) {
            lastTriggerAt[id] = now
            val pair = watchedPairs[id] ?: continue
            fingerprints[id] = fingerprint(pair)
        }
    }

    private fun fingerprint(pair: FolderPairEntity): Long =
        runCatching { treeFingerprint.of(Uri.parse(pair.localFolderUri)) }.getOrDefault(0L)

    private fun sameWatchSurface(
        a: Map<Long, FolderPairEntity>,
        b: Map<Long, FolderPairEntity>,
    ): Boolean {
        if (a.keys != b.keys) return false
        return a.all { (id, p) ->
            val o = b[id] ?: return@all false
            o.localFolderUri == p.localFolderUri &&
                o.instantUpload == p.instantUpload &&
                o.enabled == p.enabled
        }
    }

    companion object {
        const val TAG = "FolderChange"
        const val DEBOUNCE_MS = 2_000L
        /** Fallback only — not a full-tree walk. ContentObserver / content-URI are the fast path. */
        const val CHEAP_POLL_MS = 90_000L
        const val COOLDOWN_MS = 15_000L
    }
}
