package org.vovchenko.webdavsync.domain.sync

import android.net.Uri
import kotlinx.coroutines.flow.first
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.SyncLogEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.LocalTreeScanner
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.SyncFileStateRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
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
    private val clientFactory: WebDavClientFactory,
    private val localTreeScanner: LocalTreeScanner,
    private val remoteTreeScanner: RemoteTreeScanner,
    private val diffCalculator: SyncDiffCalculator,
    private val transferExecutor: TransferExecutor,
    private val emptyFolderCleaner: EmptyFolderCleaner,
    private val syncControl: SyncControl,
    private val diagnosticLogger: DiagnosticLogger,
) {
    suspend fun sync(folderPairId: Long): SyncOutcome {
        val startedAt = System.currentTimeMillis()
        val pair = folderPairRepository.observeById(folderPairId).first()
            ?: return SyncOutcome(errors = 1, durationMs = System.currentTimeMillis() - startedAt)
        if (!pair.enabled) return SyncOutcome(durationMs = System.currentTimeMillis() - startedAt)
        if (syncControl.shouldStop()) {
            return SyncOutcome(durationMs = System.currentTimeMillis() - startedAt, cancelled = true)
        }

        return try {
            runSync(pair, startedAt)
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startedAt
            diagnosticLogger.e(TAG, "Sync crashed for pair id=${pair.id} name=${pair.name}", e)
            syncLogRepository.log(
                SyncLogEntity(
                    folderPairId = pair.id,
                    timestamp = System.currentTimeMillis(),
                    eventType = SyncEventType.ERROR,
                    message = e.message ?: "Sync failed",
                ),
            )
            folderPairRepository.update(pair.copy(lastSyncAt = startedAt, lastSyncDurationMs = duration, lastSyncStatus = "ERROR"))
            SyncOutcome(errors = 1, durationMs = duration)
        }
    }

    private suspend fun runSync(pair: FolderPairEntity, startedAt: Long): SyncOutcome {
        diagnosticLogger.i(
            TAG,
            "Sync start pair='${pair.name}' id=${pair.id} method=${pair.syncMethod} remote='${pair.remoteFolderPath}'",
        )
        syncLogRepository.log(
            SyncLogEntity(folderPairId = pair.id, timestamp = startedAt, eventType = SyncEventType.SYNC_START),
        )

        syncControl.awaitWhilePaused()
        if (syncControl.shouldStop()) return cancelledSync(pair, startedAt)

        val account = accountRepository.observeById(pair.accountId).first()
            ?: return failSync(pair, startedAt, "Account not found")
        val credentials = accountRepository.getCredentials(account.id)
            ?: return failSync(pair, startedAt, "No stored credentials for account")
        val authScheme = account.authScheme
            ?: return failSync(pair, startedAt, "Account has no detected auth scheme yet")
        val trustedCert = accountRepository.getTrustedCertificate(account.id)
        diagnosticLogger.i(
            TAG,
            "Using account='${account.displayName}' baseUrl='${account.baseUrl}' auth=$authScheme customCert=${trustedCert != null}",
        )
        val client = clientFactory.create(account.baseUrl, authScheme, credentials, trustedCert)

        val localRootUri = Uri.parse(pair.localFolderUri)
        val localEntries = runCatching {
            localTreeScanner.scan(localRootUri, pair.excludeHiddenFiles, pair.excludedSubfolders)
        }.getOrElse {
            diagnosticLogger.e(TAG, "Local scan failed for pair='${pair.name}'", it)
            return failSync(pair, startedAt, "Local folder scan failed: ${it.message}")
        }
        diagnosticLogger.i(TAG, "Local scan: ${localEntries.size} entries")

        if (syncControl.shouldStop()) return cancelledSync(pair, startedAt)
        syncControl.awaitWhilePaused()
        if (syncControl.shouldStop()) return cancelledSync(pair, startedAt)

        val remoteEntries = remoteTreeScanner.scan(client, pair.remoteFolderPath, pair.excludedSubfolders)
            .getOrElse {
                diagnosticLogger.e(TAG, "Remote scan failed for pair='${pair.name}'", it)
                return failSync(pair, startedAt, "Remote folder scan failed: ${it.message}")
            }
        diagnosticLogger.i(TAG, "Remote scan: ${remoteEntries.size} entries")

        val baseline = syncFileStateRepository.getForFolderPair(pair.id)
        val actions = diffCalculator.computeActions(pair.syncMethod, localEntries, remoteEntries, baseline)
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
            diagnosticLogger.i(TAG, "Cleaning empty folders")
            cleanEmptyFolders(pair, client, localRootUri)
        }

        val endedAt = System.currentTimeMillis()
        val finalOutcome = outcome.copy(durationMs = endedAt - startedAt)
        logOutcome(pair.id, endedAt, finalOutcome)
        val status = when {
            finalOutcome.cancelled -> "CANCELLED"
            finalOutcome.hasErrors -> "ERROR"
            else -> "OK"
        }
        diagnosticLogger.i(
            TAG,
            "Sync end pair='${pair.name}' status=$status uploaded=${finalOutcome.uploaded} " +
                "downloaded=${finalOutcome.downloaded} deletedLocal=${finalOutcome.deletedLocal} " +
                "deletedRemote=${finalOutcome.deletedRemote} conflicts=${finalOutcome.conflicts} " +
                "errors=${finalOutcome.errors} durationMs=${finalOutcome.durationMs}",
        )
        folderPairRepository.update(
            pair.copy(
                lastSyncAt = endedAt,
                lastSyncDurationMs = finalOutcome.durationMs,
                lastSyncStatus = status,
            ),
        )
        return finalOutcome
    }

    private suspend fun cleanEmptyFolders(pair: FolderPairEntity, client: org.vovchenko.webdavsync.data.remote.WebDavClient, localRootUri: Uri) {
        // Re-scan after transfers so newly-emptied directories are included.
        val localDirs = runCatching { localTreeScanner.scan(localRootUri, pair.excludeHiddenFiles, pair.excludedSubfolders) }
            .getOrDefault(emptyList())
            .filter { it.isDirectory }
            .map { it.relativePath }
        emptyFolderCleaner.cleanLocal(localRootUri, localDirs, pair.excludedSubfolders)

        val remoteDirs = remoteTreeScanner.scan(client, pair.remoteFolderPath, pair.excludedSubfolders)
            .getOrDefault(emptyList())
            .filter { it.isDirectory }
            .map { it.relativePath }
        emptyFolderCleaner.cleanRemote(client, pair.remoteFolderPath, remoteDirs, pair.excludedSubfolders)
    }

    private suspend fun logOutcome(folderPairId: Long, timestamp: Long, outcome: SyncOutcome) {
        if (outcome.uploaded > 0) {
            syncLogRepository.log(SyncLogEntity(folderPairId = folderPairId, timestamp = timestamp, eventType = SyncEventType.UPLOAD, fileCount = outcome.uploaded))
        }
        if (outcome.downloaded > 0) {
            syncLogRepository.log(SyncLogEntity(folderPairId = folderPairId, timestamp = timestamp, eventType = SyncEventType.DOWNLOAD, fileCount = outcome.downloaded))
        }
        if (outcome.deletedLocal > 0) {
            syncLogRepository.log(SyncLogEntity(folderPairId = folderPairId, timestamp = timestamp, eventType = SyncEventType.DELETE_DEVICE, fileCount = outcome.deletedLocal))
        }
        if (outcome.deletedRemote > 0) {
            syncLogRepository.log(SyncLogEntity(folderPairId = folderPairId, timestamp = timestamp, eventType = SyncEventType.DELETE_CLOUD, fileCount = outcome.deletedRemote))
        }
        if (outcome.conflicts > 0) {
            syncLogRepository.log(SyncLogEntity(folderPairId = folderPairId, timestamp = timestamp, eventType = SyncEventType.CONFLICT, fileCount = outcome.conflicts))
        }
        if (outcome.errors > 0) {
            syncLogRepository.log(SyncLogEntity(folderPairId = folderPairId, timestamp = timestamp, eventType = SyncEventType.ERROR, fileCount = outcome.errors))
        }
        val endMessage = if (outcome.cancelled) {
            "Cancelled after uploaded ${outcome.uploaded}, downloaded ${outcome.downloaded}, " +
                "deleted (device) ${outcome.deletedLocal}, deleted (cloud) ${outcome.deletedRemote}, " +
                "conflicts ${outcome.conflicts}, errors ${outcome.errors}"
        } else {
            "Uploaded ${outcome.uploaded}, downloaded ${outcome.downloaded}, " +
                "deleted (device) ${outcome.deletedLocal}, deleted (cloud) ${outcome.deletedRemote}, " +
                "conflicts ${outcome.conflicts}, errors ${outcome.errors}"
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

    private suspend fun cancelledSync(pair: FolderPairEntity, startedAt: Long): SyncOutcome {
        val endedAt = System.currentTimeMillis()
        val duration = endedAt - startedAt
        diagnosticLogger.i(TAG, "Sync cancelled for pair='${pair.name}'")
        syncLogRepository.log(
            SyncLogEntity(
                folderPairId = pair.id,
                timestamp = endedAt,
                eventType = SyncEventType.SYNC_END,
                message = "Cancelled by user",
            ),
        )
        folderPairRepository.update(
            pair.copy(lastSyncAt = endedAt, lastSyncDurationMs = duration, lastSyncStatus = "CANCELLED"),
        )
        return SyncOutcome(durationMs = duration, cancelled = true)
    }

    private suspend fun failSync(pair: FolderPairEntity, startedAt: Long, message: String): SyncOutcome {
        val endedAt = System.currentTimeMillis()
        diagnosticLogger.e(TAG, "Sync failed for pair='${pair.name}': $message")
        syncLogRepository.log(SyncLogEntity(folderPairId = pair.id, timestamp = endedAt, eventType = SyncEventType.ERROR, message = message))
        folderPairRepository.update(
            pair.copy(lastSyncAt = endedAt, lastSyncDurationMs = endedAt - startedAt, lastSyncStatus = "ERROR"),
        )
        return SyncOutcome(errors = 1, durationMs = endedAt - startedAt)
    }

    private companion object {
        const val TAG = "SyncEngine"
        const val MAX_PARALLEL_TRANSFERS = 4
    }
}
