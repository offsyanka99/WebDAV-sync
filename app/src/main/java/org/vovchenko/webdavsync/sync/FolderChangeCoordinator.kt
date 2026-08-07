package org.vovchenko.webdavsync.sync

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.FolderChangeDebouncer
import org.vovchenko.webdavsync.data.local.saf.FolderChangeObserver
import org.vovchenko.webdavsync.data.local.saf.LocalTreeScanner
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
 * SAF [ContentObserver] alone is unreliable on many devices/providers, so we also **poll** a
 * lightweight local fingerprint (entry count + total size + max mtime) every few seconds.
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
    private val localTreeScanner: LocalTreeScanner,
    private val diagnosticLogger: DiagnosticLogger,
    private val scope: CoroutineScope,
) {
    private val observers = ConcurrentHashMap<Long, FolderChangeObserver>()
    private val fingerprints = ConcurrentHashMap<Long, Long>()
    private val lastTriggerAt = ConcurrentHashMap<Long, Long>()
    private var pollJob: Job? = null
    @Volatile private var watchedPairs: Map<Long, FolderPairEntity> = emptyMap()

    fun start() {
        scope.launch {
            combine(
                folderPairRepository.observeAll(),
                settingsRepository.settings,
            ) { pairs, settings ->
                pairs.filter { pair ->
                    pair.enabled && (
                        pair.instantUpload ||
                            (settings.autoSyncEnabled && settings.syncImmediatelyOnLocalChange)
                        )
                }.associateBy { it.id }
            }
                .distinctUntilChanged { a, b ->
                    a.keys == b.keys && a.all { (id, p) ->
                        b[id]?.localFolderUri == p.localFolderUri &&
                            b[id]?.instantUpload == p.instantUpload &&
                            b[id]?.enabled == p.enabled
                    }
                }
                .collect { toWatch ->
                    watchedPairs = toWatch
                    reconcileObservers(toWatch)
                    ensurePoller(toWatch.isNotEmpty())
                }
        }
    }

    private fun reconcileObservers(toWatch: Map<Long, FolderPairEntity>) {
        val stale = observers.keys - toWatch.keys
        stale.forEach { id ->
            observers.remove(id)?.stop()
            debouncer.cancel(id)
            fingerprints.remove(id)
            diagnosticLogger.i(TAG, "Stopped folder watch pairId=$id")
        }
        for ((id, pair) in toWatch) {
            if (observers.containsKey(id)) continue
            val uri = runCatching { Uri.parse(pair.localFolderUri) }.getOrNull() ?: continue
            val observer = FolderChangeObserver(
                contentResolver = context.contentResolver,
                treeUri = uri,
                onChange = { scheduleSync(id, "content-observer") },
            )
            runCatching { observer.start() }
                .onSuccess {
                    observers[id] = observer
                    // Seed fingerprint so the first poll does not false-trigger.
                    fingerprints[id] = fingerprint(pair)
                    diagnosticLogger.i(TAG, "Started folder watch pairId=$id (observer+poll)")
                }
                .onFailure {
                    diagnosticLogger.w(TAG, "ContentObserver failed pairId=$id, relying on poll: ${it.message}")
                    fingerprints[id] = fingerprint(pair)
                }
        }
    }

    private fun ensurePoller(needed: Boolean) {
        if (!needed) {
            pollJob?.cancel()
            pollJob = null
            return
        }
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            diagnosticLogger.i(TAG, "Local folder poller started intervalMs=$POLL_MS")
            while (isActive) {
                delay(POLL_MS)
                val snapshot = watchedPairs
                for ((id, pair) in snapshot) {
                    val fp = fingerprint(pair)
                    val previous = fingerprints.put(id, fp)
                    if (previous != null && previous != fp) {
                        // Growing/shrinking tree while we download is expected — do not log spam.
                        if (syncControl.isSessionActive) {
                            syncControl.requestFollowUpSync()
                        } else {
                            scheduleSync(id, "poll fingerprint change $previous→$fp")
                        }
                    }
                }
            }
        }
    }

    private fun scheduleSync(folderPairId: Long, reason: String) {
        // Downloads/uploads rewrite the local tree; never start a competing worker mid-pass.
        if (syncControl.isSessionActive) {
            watchedPairs[folderPairId]?.let { fingerprints[folderPairId] = fingerprint(it) }
            syncControl.requestFollowUpSync()
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
                syncControl.requestFollowUpSync()
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

    /**
     * Cheap stable hash of the local tree. Changes when files are added/removed/resized/touched.
     */
    private fun fingerprint(pair: FolderPairEntity): Long {
        return runCatching {
            val uri = Uri.parse(pair.localFolderUri)
            val entries = localTreeScanner.scan(uri, pair.excludeHiddenFiles, pair.excludedSubfolders)
            var count = 0
            var totalSize = 0L
            var maxMtime = 0L
            for (e in entries) {
                count++
                totalSize += e.sizeBytes
                if (e.lastModifiedEpochMillis > maxMtime) maxMtime = e.lastModifiedEpochMillis
            }
            // Pack into a single long (collisions acceptable; we only need change detection).
            31L * count + 17L * totalSize + maxMtime
        }.getOrDefault(0L)
    }

    private companion object {
        const val TAG = "FolderChange"
        const val DEBOUNCE_MS = 2_000L
        const val POLL_MS = 4_000L
        const val COOLDOWN_MS = 15_000L
    }
}
