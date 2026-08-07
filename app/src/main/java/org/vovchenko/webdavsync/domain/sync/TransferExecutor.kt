package org.vovchenko.webdavsync.domain.sync

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.saf.LocalFileIo
import org.vovchenko.webdavsync.data.local.saf.MimeTypes
import org.vovchenko.webdavsync.data.remote.WebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavException
import org.vovchenko.webdavsync.data.repository.SyncFileStateRepository
import org.vovchenko.webdavsync.domain.model.SyncAction
import org.vovchenko.webdavsync.domain.model.SyncOutcome
import org.vovchenko.webdavsync.sync.control.SyncControl
import java.io.InputStream
import javax.inject.Inject

/** Everything [TransferExecutor] needs for one folder pair's transfers. */
data class TransferContext(
    val folderPairId: Long,
    val localRootUri: Uri,
    val remoteRootPath: String,
    val client: WebDavClient,
    val uploadSizeLimitBytes: Long?,
    val downloadSizeLimitBytes: Long?,
    val maxParallelTransfers: Int,
    val retryAttempts: Int,
    val retryDelayMs: Long,
)

private sealed class ActionResult {
    object Uploaded : ActionResult()
    object Downloaded : ActionResult()
    object DeletedLocal : ActionResult()
    object DeletedRemote : ActionResult()
    object Skipped : ActionResult()
    data class ConflictResolved(val winningSide: SyncAction.Side) : ActionResult()
    object Failed : ActionResult()
}

/**
 * Executes the actions from [SyncDiffCalculator], with optional parallelism, upload/download
 * size limits, and retry-on-transient-failure (plan Phase 4). Updates the per-file baseline
 * ([SyncFileStateEntity]) as each action completes.
 */
class TransferExecutor @Inject constructor(
    private val localFileIo: LocalFileIo,
    private val conflictResolver: ConflictResolver,
    private val syncFileStateRepository: SyncFileStateRepository,
    private val syncControl: SyncControl,
    private val diagnosticLogger: DiagnosticLogger,
) {
    suspend fun executeAll(actions: List<SyncAction>, ctx: TransferContext): SyncOutcome {
        var directoryErrors = 0
        val (directoryActions, fileActions) = actions.partition {
            it is SyncAction.CreateLocalDirectory || it is SyncAction.CreateRemoteDirectory
        }
        diagnosticLogger.i(
            TAG,
            "Executing transfers pairId=${ctx.folderPairId}: ${directoryActions.size} dir action(s), " +
                "${fileActions.size} file action(s), parallel=${ctx.maxParallelTransfers}",
        )

        // Directories run sequentially, already ordered shallowest-first by the diff calculator.
        for (action in directoryActions) {
            if (syncControl.shouldStop()) break
            syncControl.awaitWhilePaused()
            if (syncControl.shouldStop()) break
            val ok = runCatching { executeDirectoryAction(action, ctx) }.getOrElse {
                if (it is CancellationException) throw it
                diagnosticLogger.e(TAG, "Directory action failed path=${action.relativePath}", it)
                false
            }
            if (!ok) {
                directoryErrors++
                diagnosticLogger.w(TAG, "Directory action failed path=${action.relativePath}")
            }
        }

        if (syncControl.shouldStop()) {
            diagnosticLogger.i(TAG, "Transfers cancelled after directory phase pairId=${ctx.folderPairId}")
            return SyncOutcome(errors = directoryErrors, cancelled = true)
        }

        val semaphore = Semaphore(ctx.maxParallelTransfers.coerceAtLeast(1))
        val results = coroutineScope {
            fileActions.map { action ->
                async(Dispatchers.IO) {
                    semaphore.withPermit { executeFileActionWithRetry(action, ctx) }
                }
            }.awaitAll()
        }

        var uploaded = 0
        var downloaded = 0
        var deletedLocal = 0
        var deletedRemote = 0
        var conflicts = 0
        var skipped = 0
        var errors = directoryErrors
        for (result in results) {
            when (result) {
                ActionResult.Uploaded -> uploaded++
                ActionResult.Downloaded -> downloaded++
                ActionResult.DeletedLocal -> deletedLocal++
                ActionResult.DeletedRemote -> deletedRemote++
                is ActionResult.ConflictResolved -> {
                    conflicts++
                    if (result.winningSide == SyncAction.Side.LOCAL) uploaded++ else downloaded++
                }
                ActionResult.Skipped -> skipped++
                ActionResult.Failed -> errors++
            }
        }

        diagnosticLogger.i(
            TAG,
            "Transfers done pairId=${ctx.folderPairId}: up=$uploaded down=$downloaded " +
                "delLocal=$deletedLocal delRemote=$deletedRemote conflicts=$conflicts skipped=$skipped " +
                "errors=$errors cancelled=${syncControl.isCancelled}",
        )

        return SyncOutcome(
            uploaded = uploaded,
            downloaded = downloaded,
            deletedLocal = deletedLocal,
            deletedRemote = deletedRemote,
            conflicts = conflicts,
            errors = errors,
            cancelled = syncControl.isCancelled,
            skipped = skipped,
        )
    }

    private suspend fun executeDirectoryAction(action: SyncAction, ctx: TransferContext): Boolean = when (action) {
        is SyncAction.CreateLocalDirectory -> localFileIo.ensureDirectory(ctx.localRootUri, action.relativePath) != null
        is SyncAction.CreateRemoteDirectory ->
            ctx.client.createDirectory(RemotePaths.join(ctx.remoteRootPath, action.relativePath)).isSuccess
        else -> true
    }

    private suspend fun executeFileActionWithRetry(action: SyncAction, ctx: TransferContext): ActionResult {
        if (syncControl.shouldStop()) return ActionResult.Skipped
        syncControl.awaitWhilePaused()
        if (syncControl.shouldStop()) return ActionResult.Skipped

        var lastResult: ActionResult = ActionResult.Failed
        val maxAttempts = ctx.retryAttempts.coerceAtLeast(1)
        for (attempt in 1..maxAttempts) {
            if (syncControl.shouldStop()) return ActionResult.Skipped
            syncControl.awaitWhilePaused()
            if (syncControl.shouldStop()) return ActionResult.Skipped

            lastResult = runCatching { executeFileAction(action, ctx) }.getOrElse {
                // Cancellation (e.g. this sync got superseded) must propagate, not be treated as a
                // retryable failure — retrying after cancellation re-downloaded/re-uploaded files
                // that had already completed, and re-created "conflicted copy" duplicates.
                if (it is CancellationException) throw it
                diagnosticLogger.e(TAG, "File action threw path=${action.relativePath} attempt=$attempt/$maxAttempts", it)
                ActionResult.Failed
            }
            val isRetryable = lastResult == ActionResult.Failed
            if (!isRetryable || attempt == maxAttempts) {
                if (lastResult == ActionResult.Failed) {
                    diagnosticLogger.w(TAG, "File action failed path=${action.relativePath} after $attempt attempt(s)")
                }
                return lastResult
            }
            diagnosticLogger.w(TAG, "Retrying path=${action.relativePath} attempt=${attempt + 1}/$maxAttempts")
            delay(ctx.retryDelayMs)
        }
        return lastResult
    }

    private suspend fun executeFileAction(action: SyncAction, ctx: TransferContext): ActionResult = when (action) {
        is SyncAction.UploadFile -> uploadFile(action.relativePath, action.remoteRelativePath, ctx)
        is SyncAction.DownloadFile -> downloadFile(action.relativePath, action.remoteRelativePath, action.remoteSizeBytes, ctx)
        is SyncAction.DeleteLocalFile -> deleteLocalFile(action.relativePath, ctx)
        is SyncAction.DeleteRemoteFile -> deleteRemoteFile(action.relativePath, action.remoteRelativePath, ctx)
        is SyncAction.Conflict -> resolveConflict(action, ctx)
        is SyncAction.CreateLocalDirectory, is SyncAction.CreateRemoteDirectory -> ActionResult.Skipped
    }

    /** [remoteRelativePath] is the server's actual name; it can differ from [relativePath] (the local-safe canonical path). */
    private suspend fun uploadFile(relativePath: String, remoteRelativePath: String, ctx: TransferContext): ActionResult {
        val local = localFileIo.statOrNull(ctx.localRootUri, relativePath) ?: return ActionResult.Failed
        if (local.sizeBytes <= 0L) {
            diagnosticLogger.w(TAG, "Skip upload of empty local file path=$relativePath")
            return ActionResult.Skipped
        }
        if (ctx.uploadSizeLimitBytes != null && local.sizeBytes > ctx.uploadSizeLimitBytes) {
            diagnosticLogger.i(TAG, "Skip upload (size limit) path=$relativePath size=${local.sizeBytes}")
            return ActionResult.Skipped
        }

        val input = localFileIo.openInputStream(ctx.localRootUri, relativePath) ?: return ActionResult.Failed
        val remotePath = RemotePaths.join(ctx.remoteRootPath, remoteRelativePath)
        val fileName = relativePath.substringAfterLast('/')
        val result = input.use { ctx.client.upload(remotePath, MimeTypes.guess(fileName), it) }
        if (result.isFailure) {
            diagnosticLogger.w(TAG, "Upload failed path=$relativePath: ${result.exceptionOrNull()?.message}")
            return ActionResult.Failed
        }

        // Re-stat after upload: never treat a wiped/empty local file as a successful transfer.
        val after = localFileIo.statOrNull(ctx.localRootUri, relativePath)
        if (after == null || after.sizeBytes <= 0L) {
            diagnosticLogger.e(
                TAG,
                "Local file empty after upload path=$relativePath (was ${local.sizeBytes} bytes) — not updating baseline",
            )
            return ActionResult.Failed
        }

        diagnosticLogger.i(TAG, "Uploaded path=$relativePath size=${after.sizeBytes}")
        saveBaseline(ctx.folderPairId, relativePath, after.sizeBytes, after.lastModifiedEpochMillis)
        return ActionResult.Uploaded
    }

    /** [remoteRelativePath] is the server's actual name; it can differ from [relativePath] (the local-safe canonical path). */
    private suspend fun downloadFile(relativePath: String, remoteRelativePath: String, remoteSizeBytes: Long, ctx: TransferContext): ActionResult {
        val limit = ctx.downloadSizeLimitBytes
        // Enforce limit before streaming so oversized files never count as downloads and never
        // touch local storage (partial write + delete was easy to misread as a "Download").
        if (limit != null && remoteSizeBytes > 0L && remoteSizeBytes > limit) {
            diagnosticLogger.i(
                TAG,
                "Skip download (size limit) path=$relativePath size=$remoteSizeBytes limit=$limit",
            )
            return ActionResult.Skipped
        }

        val remotePath = RemotePaths.join(ctx.remoteRootPath, remoteRelativePath)
        var baselined = false
        try {
            val downloadResult = ctx.client.download(remotePath)
            val remoteStream = downloadResult.getOrElse {
                diagnosticLogger.w(TAG, "Download failed path=$relativePath: ${it.message}")
                return ActionResult.Failed
            }
            var limitExceeded = false
            val wrote = remoteStream.use { input ->
                localFileIo.openOutputStream(ctx.localRootUri, relativePath)?.use { out ->
                    if (limit != null) {
                        limitExceeded = copyLimited(input, out, limit)
                        !limitExceeded
                    } else {
                        input.copyTo(out)
                        true
                    }
                } ?: false
            }
            if (!wrote || limitExceeded) {
                // Security audit finding #3: don't leave a truncated partial file on disk.
                localFileIo.delete(ctx.localRootUri, relativePath)
                if (limitExceeded) {
                    diagnosticLogger.i(TAG, "Skip download (size limit while streaming) path=$relativePath limit=$limit")
                    return ActionResult.Skipped
                }
                return ActionResult.Failed
            }

            val stat = localFileIo.statOrNull(ctx.localRootUri, relativePath) ?: return ActionResult.Failed
            diagnosticLogger.i(TAG, "Downloaded path=$relativePath size=${stat.sizeBytes}")
            saveBaseline(ctx.folderPairId, relativePath, stat.sizeBytes, stat.lastModifiedEpochMillis)
            baselined = true
            return ActionResult.Downloaded
        } catch (e: CancellationException) {
            // Worker was stopped mid-stream: remove partial local bytes so the next pass downloads
            // cleanly instead of treating a half-file as a local edit / conflict.
            if (!baselined) {
                runCatching { localFileIo.delete(ctx.localRootUri, relativePath) }
                diagnosticLogger.i(TAG, "Removed partial download after cancel path=$relativePath")
            }
            throw e
        }
    }

    /** Copies [input] to [output] in chunks, stopping early if more than [limitBytes] is read. Returns true if the limit was exceeded. */
    private fun copyLimited(input: InputStream, output: java.io.OutputStream, limitBytes: Long): Boolean {
        val buffer = ByteArray(DEFAULT_COPY_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) return false
            total += read
            if (total > limitBytes) return true
            output.write(buffer, 0, read)
        }
    }


    private suspend fun deleteLocalFile(relativePath: String, ctx: TransferContext): ActionResult {
        localFileIo.delete(ctx.localRootUri, relativePath)
        syncFileStateRepository.deleteForPath(ctx.folderPairId, relativePath)
        return ActionResult.DeletedLocal
    }

    private suspend fun deleteRemoteFile(relativePath: String, remoteRelativePath: String, ctx: TransferContext): ActionResult {
        val remotePath = RemotePaths.join(ctx.remoteRootPath, remoteRelativePath)
        val result = ctx.client.delete(remotePath)
        // A 404 here just means it's already gone remotely — treat as success either way.
        if (result.isFailure && result.exceptionOrNull() !is WebDavException.NotFound) return ActionResult.Failed
        syncFileStateRepository.deleteForPath(ctx.folderPairId, relativePath)
        return ActionResult.DeletedRemote
    }

    private suspend fun resolveConflict(action: SyncAction.Conflict, ctx: TransferContext): ActionResult {
        val relativePath = action.relativePath
        val losingSide = if (action.winningSide == SyncAction.Side.LOCAL) SyncAction.Side.REMOTE else SyncAction.Side.LOCAL
        val conflictCopyPath = conflictResolver.conflictedCopyPath(relativePath, losingSide)

        val preserved = when (losingSide) {
            // Remote wins → local's current content is the loser, back it up as a local copy first.
            SyncAction.Side.LOCAL -> localFileIo.openInputStream(ctx.localRootUri, relativePath)?.use { input ->
                localFileIo.openOutputStream(ctx.localRootUri, conflictCopyPath)?.use { out -> input.copyTo(out) } != null
            } ?: false
            // Local wins → remote's current content is the loser, download it aside as a local copy first.
            SyncAction.Side.REMOTE -> {
                val remotePath = RemotePaths.join(ctx.remoteRootPath, action.remoteRelativePath)
                ctx.client.download(remotePath).getOrNull()?.use { input ->
                    localFileIo.openOutputStream(ctx.localRootUri, conflictCopyPath)?.use { out -> input.copyTo(out) } != null
                } ?: false
            }
        }
        if (!preserved) {
            diagnosticLogger.w(TAG, "Conflict preserve failed path=$relativePath")
            return ActionResult.Failed
        }

        val applied = when (action.winningSide) {
            SyncAction.Side.LOCAL -> uploadFile(relativePath, action.remoteRelativePath, ctx)
            SyncAction.Side.REMOTE -> downloadFile(relativePath, action.remoteRelativePath, remoteSizeBytes = 0L, ctx)
        }
        return if (applied is ActionResult.Failed) {
            ActionResult.Failed
        } else {
            diagnosticLogger.i(TAG, "Conflict resolved path=$relativePath winner=${action.winningSide} copy=$conflictCopyPath")
            ActionResult.ConflictResolved(action.winningSide)
        }
    }

    private suspend fun saveBaseline(folderPairId: Long, relativePath: String, sizeBytes: Long, mtime: Long) {
        syncFileStateRepository.upsert(
            SyncFileStateEntity(
                folderPairId = folderPairId,
                relativePath = relativePath,
                lastSyncedMtime = mtime,
                lastSyncedSize = sizeBytes,
            ),
        )
    }

    private companion object {
        const val TAG = "TransferExecutor"
        const val DEFAULT_COPY_BUFFER_SIZE = 8192
    }
}
