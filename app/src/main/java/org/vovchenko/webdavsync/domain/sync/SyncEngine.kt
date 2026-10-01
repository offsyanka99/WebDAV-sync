package org.vovchenko.webdavsync.domain.sync

import android.net.Uri
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.SyncLogEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.LocalFileIo
import org.vovchenko.webdavsync.data.local.saf.LocalTreeFingerprint
import org.vovchenko.webdavsync.data.local.saf.LocalTreeScanner
import org.vovchenko.webdavsync.data.local.saf.PathFilters
import org.vovchenko.webdavsync.data.local.saf.SafFolderAccess
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.data.remote.WebDavClientSession
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.SyncFileStateRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.domain.model.SyncAction
import org.vovchenko.webdavsync.domain.model.SyncOutcome
import org.vovchenko.webdavsync.sync.control.SyncControl
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates one full sync pass for a folder pair (plan Phase 4): scan both trees, three-way
 * diff, execute transfers/deletes, clean up empty folders, and record the result.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val folderPairRepository: FolderPairRepository,
    private val accountRepository: WebDavAccountRepository,
    private val settingsRepository: SettingsRepository,
    private val syncLogRepository: SyncLogRepository,
    private val syncFileStateRepository: SyncFileStateRepository,
    private val localTreeScanner: LocalTreeScanner,
    private val localTreeFingerprint: LocalTreeFingerprint,
    private val localFileIo: LocalFileIo,
    private val safFolderAccess: SafFolderAccess,
    private val remoteTreeScanner: RemoteTreeScanner,
    private val diffCalculator: SyncDiffCalculator,
    private val transferExecutor: TransferExecutor,
    private val emptyFolderCleaner: EmptyFolderCleaner,
    private val syncControl: SyncControl,
    private val diagnosticLogger: DiagnosticLogger,
    private val pushRepository: PushRepository,
) {
    /**
     * @param onNeedsForeground invoked once this pass will do remote I/O or transfers,
     * so the worker can promote to a foreground service only when needed.
     */
    suspend fun sync(
        folderPairId: Long,
        clients: WebDavClientSession,
        onNeedsForeground: suspend () -> Unit = {},
    ): SyncOutcome {
        // Wall-clock start for "Last sync" timestamp; elapsedRealtime for duration (immune to clock skew).
        val wallStart = System.currentTimeMillis()
        val elapsedStart = SystemClock.elapsedRealtime()
        val pair = folderPairRepository.observeById(folderPairId).first()
            ?: return SyncOutcome(errors = 1, durationMs = SystemClock.elapsedRealtime() - elapsedStart)
        if (!pair.enabled) return SyncOutcome(durationMs = 0)
        if (syncControl.shouldStop()) {
            return SyncOutcome(durationMs = SystemClock.elapsedRealtime() - elapsedStart, cancelled = true)
        }

        return try {
            runSync(pair, wallStart, elapsedStart, clients, onNeedsForeground)
        } catch (e: CancellationException) {
            // Propagate so WorkManager can stop the worker; still record CANCELLED so Overview
            // does not keep showing "Sync in process..." / a stale OK after a superseded pass.
            val duration = runCatching {
                finishPair(pair, elapsedStart, status = "CANCELLED")
            }.getOrElse { SystemClock.elapsedRealtime() - elapsedStart }
            diagnosticLogger.i(TAG, "Sync cancelled (coroutine) for pair='${pair.name}' durationMs=$duration")
            throw e
        } catch (e: Exception) {
            diagnosticLogger.e(TAG, "Sync crashed for pair id=${pair.id} name=${pair.name}", e)
            val duration = finishPair(
                pair,
                elapsedStart,
                status = "ERROR",
                errorLogMessage = e.message ?: "Sync failed",
            )
            SyncOutcome(errors = 1, durationMs = duration)
        } finally {
            // Pairs run one at a time, so the next pair starts with no suppression.
            clients.clearPushDontNotify()
        }
    }

    private suspend fun runSync(
        pair: FolderPairEntity,
        wallStart: Long,
        elapsedStart: Long,
        clients: WebDavClientSession,
        onNeedsForeground: suspend () -> Unit,
    ): SyncOutcome {
        diagnosticLogger.i(
            TAG,
            "Sync start pair='${pair.name}' id=${pair.id} method=${pair.syncMethod} remote='${pair.remoteFolderPath}'",
        )

        syncControl.awaitWhilePaused()
        if (syncControl.shouldStop()) return cancelledSync(pair, elapsedStart)

        val account = accountRepository.observeById(pair.accountId).first()
            ?: return failSync(pair, elapsedStart, "Account not found")
        val credentials = accountRepository.getCredentials(account.id)
            ?: return failSync(pair, elapsedStart, "No stored credentials for account")
        val authScheme = account.authScheme
            ?: return failSync(pair, elapsedStart, "Account has no detected auth scheme yet")
        val trustedCert = accountRepository.getTrustedCertificate(account.id)
        diagnosticLogger.i(
            TAG,
            "Using account='${account.displayName}' baseUrl='${account.baseUrl}' auth=$authScheme customCert=${trustedCert != null}",
        )

        val localRootUri = Uri.parse(pair.localFolderUri)
        // Without this check a revoked/lost SAF grant silently scans as "0 entries" and every
        // local write then fails, which looks like a network/retry problem, not a permission one.
        if (!safFolderAccess.hasAccess(localRootUri)) {
            return failSync(pair, elapsedStart, "Local folder access lost — reselect the local folder in this folder pair's settings")
        }
        // A WebDAV-Push for this pair forces the full walk and the remote scan.
        val remoteChangePending = pair.remoteChangePendingAt != null
        if (remoteChangePending) {
            diagnosticLogger.i(TAG, "Server change pending pair='${pair.name}' (push); idle shortcuts off")
        }
        val cheapFingerprint = localTreeFingerprint.of(localRootUri)
        if (IdleSyncPolicy.canSkipFullLocalWalk(
                lastCheapFingerprint = pair.lastCheapFingerprint,
                currentCheapFingerprint = cheapFingerprint,
                lastFullLocalScanAt = pair.lastFullLocalScanAt,
                nowMillis = wallStart,
                lastLocalFingerprint = pair.lastLocalFingerprint,
                lastSyncStatus = pair.lastSyncStatus,
                lastRemoteScanAt = pair.lastRemoteScanAt,
                mtimeKnownReliable = true,
                remoteChangePending = remoteChangePending,
            )
        ) {
            diagnosticLogger.i(
                TAG,
                "Skip full local walk pair='${pair.name}' (cheap fingerprint unchanged)",
            )
            finishPair(
                pair,
                elapsedStart,
                status = IdleSyncPolicy.STATUS_OK,
                overwriteLastSync = false,
                localFingerprint = pair.lastLocalFingerprint,
            )
            return SyncOutcome(durationMs = SystemClock.elapsedRealtime() - elapsedStart)
        }
        val localEntries = runCatching {
            localTreeScanner.scan(localRootUri, pair.excludeHiddenFiles, pair.excludedSubfolders)
        }.getOrElse {
            if (it is CancellationException) throw it
            diagnosticLogger.e(TAG, "Local scan failed for pair='${pair.name}'", it)
            return failSync(pair, elapsedStart, "Local folder scan failed: ${it.message}")
        }
        val localFingerprint = IdleSyncPolicy.fingerprint(localEntries)
        val mtimeReliable = IdleSyncPolicy.mtimeReliable(localEntries)
        val scanMs = SystemClock.elapsedRealtime() - elapsedStart
        val runtime = Runtime.getRuntime()
        diagnosticLogger.i(
            TAG,
            "Local scan: ${localEntries.size} entries fingerprint=$localFingerprint " +
                "scanMs=$scanMs heapFree=${runtime.freeMemory()} heapTotal=${runtime.totalMemory()}",
        )

        if (IdleSyncPolicy.canSkipRemoteScan(
                method = pair.syncMethod,
                lastSyncStatus = pair.lastSyncStatus,
                lastFingerprint = pair.lastLocalFingerprint,
                currentFingerprint = localFingerprint,
                lastRemoteScanAt = pair.lastRemoteScanAt,
                nowMillis = wallStart,
                mtimeReliable = mtimeReliable,
                remoteChangePending = remoteChangePending,
            )
        ) {
            diagnosticLogger.i(TAG, "Idle short-circuit pair='${pair.name}' (local fingerprint unchanged, to-cloud)")
            finishPair(
                pair,
                elapsedStart,
                status = IdleSyncPolicy.STATUS_OK,
                overwriteLastSync = false,
                localFingerprint = localFingerprint,
                cheapFingerprint = if (mtimeReliable) cheapFingerprint else null,
                rememberCheapFingerprint = true,
                fullLocalScanAt = wallStart,
            )
            return SyncOutcome(durationMs = SystemClock.elapsedRealtime() - elapsedStart)
        }

        syncLogRepository.log(
            SyncLogEntity(folderPairId = pair.id, timestamp = wallStart, eventType = SyncEventType.SYNC_START),
        )

        val client = clients.clientFor(account.id, account.baseUrl, authScheme, credentials, trustedCert)
        // Our own writes must not echo back as pushes; cleared in sync()'s finally.
        val suppression = pushRepository.suppressionUrlsFor(pair.id)
        client.setPushDontNotify(suppression)
        if (suppression.isNotEmpty()) {
            diagnosticLogger.i(TAG, "Push-Dont-Notify pair='${pair.name}' registrations=${suppression.size}")
        }

        if (syncControl.shouldStop()) return cancelledSync(pair, elapsedStart)
        syncControl.awaitWhilePaused()
        if (syncControl.shouldStop()) return cancelledSync(pair, elapsedStart)

        onNeedsForeground()

        // Create remote root (e.g. /Webdavsync) when the user configured a path that does not
        // exist yet — first sync should MKCOL then scan, not fail with 404.
        if (!IdleSyncPolicy.canSkipEnsureRemote(pair.lastSyncStatus)) {
            ensureRemoteFolderTree(client, pair.remoteFolderPath).onFailure {
                diagnosticLogger.e(TAG, "Could not create remote folder '${pair.remoteFolderPath}'", it)
                return failSync(pair, elapsedStart, "Remote folder setup failed: ${it.message}")
            }
        }

        val remoteStarted = SystemClock.elapsedRealtime()
        val remoteScanStartedAt = System.currentTimeMillis()
        val remoteEntries = remoteTreeScanner.scan(
            client,
            pair.remoteFolderPath,
            pair.excludedSubfolders,
            pair.excludeHiddenFiles,
        ).getOrElse {
            // The push flag stays set; ERROR already disables the idle shortcuts, so no tight loop.
            diagnosticLogger.e(TAG, "Remote scan failed for pair='${pair.name}'", it)
            return failSync(pair, elapsedStart, "Remote folder scan failed: ${it.message}")
        }
        // Only pushes older than this scan are consumed; one that arrived mid-walk stays set.
        folderPairRepository.clearRemoteChangePending(pair.id, remoteScanStartedAt)
        diagnosticLogger.i(
            TAG,
            "Remote scan: ${remoteEntries.size} entries in ${SystemClock.elapsedRealtime() - remoteStarted}ms " +
                "heapFree=${Runtime.getRuntime().freeMemory()}",
        )

        val baseline = dropIgnoredBaseline(pair)
        val diffActions = diffCalculator.computeActions(pair.syncMethod, localEntries, remoteEntries, baseline)
        val hashSweepFresh = IdleSyncPolicy.hashSweepIsFresh(pair.lastContentHashSweepAt, wallStart)
        val actions = if (hashSweepFresh) {
            diffActions
        } else {
            ZeroMtimeReconciler.apply(
                actions = diffActions,
                syncMethod = pair.syncMethod,
                localEntries = localEntries,
                remoteEntries = remoteEntries,
                baseline = baseline,
                hashOf = { path ->
                    localFileIo.openInputStream(localRootUri, path)?.use { ContentHash.sha256(it) }
                },
            )
        }
        diagnosticLogger.i(TAG, "Diff produced ${actions.size} action(s)")

        val settings = settingsRepository.settings.first()
        val ctx = TransferContext(
            folderPairId = pair.id,
            localRootUri = localRootUri,
            remoteRootPath = pair.remoteFolderPath,
            client = client,
            uploadSizeLimitBytes = settings.uploadSizeLimitBytes,
            downloadSizeLimitBytes = settings.downloadSizeLimitBytes,
            maxParallelTransfers = if (settings.allowParallelTransfers) MAX_PARALLEL_TRANSFERS else 1,
            retryAttempts = settings.retryAttempts,
            retryDelayMs = TimeUnit.MINUTES.toMillis(settings.retryWaitMinutes.toLong()),
        )
        val outcome = transferExecutor.executeAll(actions, ctx)

        if (!outcome.cancelled && pair.deleteEmptyFolders) {
            cleanEmptiedAncestors(pair, client, localRootUri, actions)
        }

        val wallEnd = System.currentTimeMillis()
        val durationMs = SystemClock.elapsedRealtime() - elapsedStart
        val finalOutcome = outcome.copy(durationMs = durationMs)
        val status = when {
            finalOutcome.cancelled -> "CANCELLED"
            finalOutcome.hasErrors -> "ERROR"
            else -> IdleSyncPolicy.STATUS_OK
        }
        diagnosticLogger.i(
            TAG,
            "Sync end pair='${pair.name}' status=$status uploaded=${finalOutcome.uploaded} " +
                "downloaded=${finalOutcome.downloaded} deletedLocal=${finalOutcome.deletedLocal} " +
                "deletedRemote=${finalOutcome.deletedRemote} conflicts=${finalOutcome.conflicts} " +
                "errors=${finalOutcome.errors} skipped=${finalOutcome.skipped} durationMs=$durationMs" +
                if (finalOutcome.isIdleNoOp) " (idle no-op)" else "",
        )

        val ok = status == IdleSyncPolicy.STATUS_OK
        val persistFp = if (ok) localFingerprint else null
        val scannedRemoteAt = if (ok) wallEnd else null
        val sweptAt = if (!hashSweepFresh && ok) wallEnd else null
        val rememberCheap = ok || !mtimeReliable
        val persistedCheap = if (ok && mtimeReliable) cheapFingerprint else null

        // Nothing transferred (including a pass that only re-baselines matching files).
        // Overwriting Last sync / Duration here replaced a real upload with "0s".
        if (finalOutcome.isIdleNoOp) {
            finishPair(
                pair,
                elapsedStart,
                status = status,
                overwriteLastSync = false,
                wallEnd = wallEnd,
                localFingerprint = persistFp,
                remoteScanAt = scannedRemoteAt,
                cheapFingerprint = persistedCheap,
                rememberCheapFingerprint = rememberCheap,
                fullLocalScanAt = if (ok) wallEnd else null,
                hashSweepAt = sweptAt,
            )
            return finalOutcome
        }

        logOutcome(pair.id, wallEnd, finalOutcome)
        finishPair(
            pair,
            elapsedStart,
            status = status,
            wallEnd = wallEnd,
            localFingerprint = persistFp,
            remoteScanAt = scannedRemoteAt,
            cheapFingerprint = persistedCheap,
            rememberCheapFingerprint = rememberCheap,
            fullLocalScanAt = if (ok) wallEnd else null,
            hashSweepAt = sweptAt,
        )
        return finalOutcome
    }

    /** Remove folders that just lost their last file — no second full tree walk. */
    private suspend fun cleanEmptiedAncestors(
        pair: FolderPairEntity,
        client: org.vovchenko.webdavsync.data.remote.WebDavClient,
        localRootUri: Uri,
        actions: List<SyncAction>,
    ) {
        val localDeleted = actions.mapNotNull { action ->
            (action as? SyncAction.DeleteLocalFile)?.relativePath
        }
        val remoteDeleted = actions.mapNotNull { action ->
            (action as? SyncAction.DeleteRemoteFile)?.relativePath
        }
        val localDirs = IdleSyncPolicy.ancestorDirectories(localDeleted)
        val remoteDirs = IdleSyncPolicy.ancestorDirectories(remoteDeleted)
        if (localDirs.isEmpty() && remoteDirs.isEmpty()) return
        diagnosticLogger.i(
            TAG,
            "Cleaning emptied ancestors local=${localDirs.size} remote=${remoteDirs.size}",
        )
        if (localDirs.isNotEmpty()) {
            emptyFolderCleaner.cleanLocal(localRootUri, localDirs, pair.excludedSubfolders)
        }
        if (remoteDirs.isNotEmpty()) {
            emptyFolderCleaner.cleanRemote(client, pair.remoteFolderPath, remoteDirs, pair.excludedSubfolders)
        }
    }

    private suspend fun logOutcome(folderPairId: Long, timestamp: Long, outcome: SyncOutcome) {
        // Always write all four counter types (including zeros). Overview prefers the worker
        // session summary, but per-pair batches must still be complete and unambiguous.
        syncLogRepository.log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.UPLOAD,
                fileCount = outcome.uploaded,
            ),
        )
        syncLogRepository.log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.DOWNLOAD,
                fileCount = outcome.downloaded,
            ),
        )
        syncLogRepository.log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.DELETE_DEVICE,
                fileCount = outcome.deletedLocal,
            ),
        )
        syncLogRepository.log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.DELETE_CLOUD,
                fileCount = outcome.deletedRemote,
            ),
        )
        if (outcome.conflicts > 0) {
            syncLogRepository.log(
                SyncLogEntity(
                    folderPairId = folderPairId,
                    timestamp = timestamp,
                    eventType = SyncEventType.CONFLICT,
                    fileCount = outcome.conflicts,
                ),
            )
        }
        if (outcome.errors > 0) {
            syncLogRepository.log(
                SyncLogEntity(
                    folderPairId = folderPairId,
                    timestamp = timestamp,
                    eventType = SyncEventType.ERROR,
                    fileCount = outcome.errors,
                ),
            )
        }
        val endMessage = buildString {
            if (outcome.cancelled) append("Cancelled after ")
            append("uploaded ${outcome.uploaded}, downloaded ${outcome.downloaded}, ")
            append("deleted (device) ${outcome.deletedLocal}, deleted (cloud) ${outcome.deletedRemote}, ")
            append("conflicts ${outcome.conflicts}, errors ${outcome.errors}")
            if (outcome.skipped > 0) append(", skipped ${outcome.skipped}")
        }
        syncLogRepository.log(
            SyncLogEntity(
                folderPairId = folderPairId,
                timestamp = timestamp,
                eventType = SyncEventType.SYNC_END,
                fileCount = outcome.totalChanges,
                message = endMessage,
            ),
        )
    }

    /**
     * Ensures each path segment of [remoteFolderPath] exists on the server (MKCOL as needed).
     */
    private suspend fun ensureRemoteFolderTree(
        client: org.vovchenko.webdavsync.data.remote.WebDavClient,
        remoteFolderPath: String,
    ): Result<Unit> {
        val segments = remoteFolderPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) return Result.success(Unit)

        var built = ""
        for (segment in segments) {
            built = if (built.isEmpty()) segment else "$built/$segment"
            val exists = client.exists(built).getOrDefault(false)
            if (exists) continue
            diagnosticLogger.i(TAG, "Creating remote folder: $built")
            client.createDirectory(built).onFailure { err ->
                val stillMissing = !client.exists(built).getOrDefault(false)
                if (stillMissing) return Result.failure(err)
            }
        }
        return Result.success(Unit)
    }

    private suspend fun cancelledSync(pair: FolderPairEntity, elapsedStart: Long): SyncOutcome {
        val wallEnd = System.currentTimeMillis()
        val duration = finishPair(
            pair,
            elapsedStart,
            status = "CANCELLED",
            wallEnd = wallEnd,
            syncEndMessage = "Cancelled by user",
        )
        diagnosticLogger.i(TAG, "Sync cancelled for pair='${pair.name}' durationMs=$duration")
        return SyncOutcome(durationMs = duration, cancelled = true)
    }

    private suspend fun failSync(pair: FolderPairEntity, elapsedStart: Long, message: String): SyncOutcome {
        val wallEnd = System.currentTimeMillis()
        val duration = finishPair(
            pair,
            elapsedStart,
            status = "ERROR",
            wallEnd = wallEnd,
            errorLogMessage = message,
        )
        diagnosticLogger.e(TAG, "Sync failed for pair='${pair.name}': $message durationMs=$duration")
        return SyncOutcome(errors = 1, durationMs = duration)
    }

    /**
     * Centralizes Last sync / Duration / Status DB writes for success, error, cancel, and idle paths.
     * @param overwriteLastSync when false (idle follow-up), only refresh status if it changed.
     * @return duration in ms for [SyncOutcome] / logs
     */
    private suspend fun finishPair(
        pair: FolderPairEntity,
        elapsedStart: Long,
        status: String,
        wallEnd: Long = System.currentTimeMillis(),
        overwriteLastSync: Boolean = true,
        errorLogMessage: String? = null,
        syncEndMessage: String? = null,
        localFingerprint: Long? = null,
        remoteScanAt: Long? = null,
        cheapFingerprint: Long? = null,
        rememberCheapFingerprint: Boolean = false,
        fullLocalScanAt: Long? = null,
        hashSweepAt: Long? = null,
    ): Long {
        val duration = SystemClock.elapsedRealtime() - elapsedStart
        if (errorLogMessage != null) {
            syncLogRepository.log(
                SyncLogEntity(
                    folderPairId = pair.id,
                    timestamp = wallEnd,
                    eventType = SyncEventType.ERROR,
                    message = errorLogMessage,
                ),
            )
        }
        if (syncEndMessage != null) {
            syncLogRepository.log(
                SyncLogEntity(
                    folderPairId = pair.id,
                    timestamp = wallEnd,
                    eventType = SyncEventType.SYNC_END,
                    message = syncEndMessage,
                ),
            )
        }
        val nextFp = localFingerprint ?: pair.lastLocalFingerprint
        val nextScan = remoteScanAt ?: pair.lastRemoteScanAt
        val nextCheap = if (rememberCheapFingerprint) cheapFingerprint else pair.lastCheapFingerprint
        val nextFullScan = fullLocalScanAt ?: pair.lastFullLocalScanAt
        val nextSweep = hashSweepAt ?: pair.lastContentHashSweepAt
        if (overwriteLastSync) {
            folderPairRepository.update(
                pair.copy(
                    lastSyncAt = wallEnd,
                    lastSyncDurationMs = duration,
                    lastSyncStatus = status,
                    lastLocalFingerprint = nextFp,
                    lastRemoteScanAt = nextScan,
                    lastCheapFingerprint = nextCheap,
                    lastFullLocalScanAt = nextFullScan,
                    lastContentHashSweepAt = nextSweep,
                ),
            )
        } else if (
            pair.lastSyncStatus != status ||
            pair.lastLocalFingerprint != nextFp ||
            pair.lastRemoteScanAt != nextScan ||
            pair.lastCheapFingerprint != nextCheap ||
            pair.lastFullLocalScanAt != nextFullScan ||
            pair.lastContentHashSweepAt != nextSweep
        ) {
            folderPairRepository.update(
                pair.copy(
                    lastSyncStatus = status,
                    lastLocalFingerprint = nextFp,
                    lastRemoteScanAt = nextScan,
                    lastCheapFingerprint = nextCheap,
                    lastFullLocalScanAt = nextFullScan,
                    lastContentHashSweepAt = nextSweep,
                ),
            )
        }
        return duration
    }

    /**
     * Hidden and temporary paths are out of scope. Drop their baseline rows before the diff
     * so a filtered-out file is not classified as a local delete.
     */
    private suspend fun dropIgnoredBaseline(pair: org.vovchenko.webdavsync.data.local.FolderPairEntity) =
        syncFileStateRepository.getForFolderPair(pair.id).filter { row ->
            val ignored = PathFilters.excludedFromSync(row.relativePath, pair.excludeHiddenFiles)
            if (ignored) {
                syncFileStateRepository.deleteForPath(pair.id, row.relativePath)
            }
            !ignored
        }

    private companion object {
        const val TAG = "SyncEngine"
        const val MAX_PARALLEL_TRANSFERS = 4
    }
}
