package org.vovchenko.webdavsync.domain.sync

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import org.vovchenko.webdavsync.sync.control.SyncProgress
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
    object Remembered : ActionResult()
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
    private val syncProgress: SyncProgress,
    private val diagnosticLogger: DiagnosticLogger,
) {
    suspend fun executeAll(actions: List<SyncAction>, ctx: TransferContext): SyncOutcome {
        var directoryErrors = 0
        val (directoryActions, fileActions) = actions.partition {
            it is SyncAction.CreateLocalDirectory || it is SyncAction.CreateRemoteDirectory ||
                it is SyncAction.DeleteLocalDirectory || it is SyncAction.DeleteRemoteDirectory ||
                it is SyncAction.RememberDirectory || it is SyncAction.DropBaseline
        }
        diagnosticLogger.i(
            TAG,
            "Executing transfers pairId=${ctx.folderPairId}: ${directoryActions.size} dir action(s), " +
                "${fileActions.size} file action(s), parallel=${ctx.maxParallelTransfers}",
        )

        val earlyDirectories = directoryActions.filter {
            it is SyncAction.CreateLocalDirectory ||
                it is SyncAction.CreateRemoteDirectory ||
                it is SyncAction.RememberDirectory
        }
        // Creates run sequentially, already ordered shallowest-first by the diff calculator.
        // Deletes run after file transfers so a removed directory is empty first.
        for (action in earlyDirectories) {
            if (shouldAbortAtCheckpoint()) break
            diagnosticLogger.i(TAG, "Dir action start type=${action::class.simpleName} path=${action.relativePath}")
            val ok = runCatching { executeDirectoryAction(action, ctx) }.getOrElse {
                if (it is CancellationException) throw it
                diagnosticLogger.e(TAG, "Directory action failed path=${action.relativePath}", it)
                false
            }
            if (!ok) {
                directoryErrors++
                diagnosticLogger.w(TAG, "Directory action failed path=${action.relativePath}")
            } else {
                diagnosticLogger.i(TAG, "Dir action done path=${action.relativePath}")
            }
        }

        if (syncControl.shouldStop()) {
            diagnosticLogger.i(TAG, "Transfers cancelled after directory phase pairId=${ctx.folderPairId}")
            return SyncOutcome(errors = directoryErrors, cancelled = true)
        }

        val directoryDeletes = directoryActions.filter {
            it is SyncAction.DeleteLocalDirectory || it is SyncAction.DeleteRemoteDirectory || it is SyncAction.DropBaseline
        }.sortedByDescending { action -> action.relativePath.count { it == '/' } }

        val plan = TransferPlanner.plan(
            fileActions = fileActions,
            localSizes = localSizes(fileActions, ctx),
            remoteSizes = remoteSizes(fileActions),
        )

        // Parallel multi‑100MB SAF reads + full-body staging used to OOM/hang with no further
        // log lines after "Executing transfers". Cap concurrency for heavy upload batches.
        val parallel = effectiveFileParallelism(plan.primary, ctx)
        diagnosticLogger.i(
            TAG,
            "File phase start pairId=${ctx.folderPairId}: ${plan.primary.size} action(s), " +
                "${plan.guardedDeletes.size} delete(s), parallel=$parallel (configured=${ctx.maxParallelTransfers})",
        )
        Log.i(TAG, "File phase start pairId=${ctx.folderPairId} actions=${plan.primary.size} parallel=$parallel")
        val semaphore = Semaphore(parallel)
        val succeededPaths = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val primaryResults = coroutineScope {
            plan.primary.map { action ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val result = executeFileActionWithRetry(action, ctx)
                        if (result.isTransferSuccess()) succeededPaths.add(action.relativePath)
                        recordLiveProgress(result)
                        result
                    }
                }
            }.awaitAll()
        }
        val deleteResults = coroutineScope {
            plan.guardedDeletes.map { guarded ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val required = guarded.afterRelativePath
                        if (required != null && required !in succeededPaths) {
                            diagnosticLogger.w(
                                TAG,
                                "Skip delete path=${guarded.action.relativePath} because $required did not succeed",
                            )
                            return@withPermit ActionResult.Skipped
                        }
                        val result = executeFileActionWithRetry(guarded.action, ctx)
                        recordLiveProgress(result)
                        result
                    }
                }
            }.awaitAll()
        }
        val results = primaryResults + deleteResults

        for (action in directoryDeletes) {
            if (shouldAbortAtCheckpoint()) break
            val ok = runCatching { executeDirectoryAction(action, ctx) }.getOrElse {
                if (it is CancellationException) throw it
                diagnosticLogger.e(TAG, "Directory delete failed path=${action.relativePath}", it)
                false
            }
            if (!ok) directoryErrors++
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
                ActionResult.Remembered -> Unit
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
        is SyncAction.RememberDirectory -> {
            saveBaseline(ctx.folderPairId, action.relativePath, sizeBytes = 0L, mtime = 0L, isDirectory = true)
            true
        }
        is SyncAction.DropBaseline -> {
            syncFileStateRepository.deleteForPath(ctx.folderPairId, action.relativePath)
            true
        }
        is SyncAction.DeleteLocalDirectory -> {
            if (!localFileIo.isEmptyDirectory(ctx.localRootUri, action.relativePath)) {
                true
            } else if (localFileIo.delete(ctx.localRootUri, action.relativePath)) {
                syncFileStateRepository.deleteForPath(ctx.folderPairId, action.relativePath)
                true
            } else {
                false
            }
        }
        is SyncAction.DeleteRemoteDirectory -> {
            val remotePath = RemotePaths.join(ctx.remoteRootPath, action.remoteRelativePath)
            val children = ctx.client.list(remotePath).getOrNull()
            if (children == null) {
                false
            } else if (children.isNotEmpty()) {
                true
            } else {
                val result = ctx.client.delete(remotePath)
                val ok = result.isSuccess || result.exceptionOrNull() is WebDavException.NotFound
                if (ok) syncFileStateRepository.deleteForPath(ctx.folderPairId, action.relativePath)
                ok
            }
        }
        else -> true
    }

    private suspend fun executeFileActionWithRetry(action: SyncAction, ctx: TransferContext): ActionResult {
        if (shouldAbortAtCheckpoint()) return ActionResult.Skipped

        var lastResult: ActionResult = ActionResult.Failed
        val maxAttempts = ctx.retryAttempts.coerceAtLeast(1)
        for (attempt in 1..maxAttempts) {
            if (shouldAbortAtCheckpoint()) return ActionResult.Skipped

            lastResult = runCatching { executeFileAction(action, ctx) }.getOrElse {
                // Cancellation (e.g. this sync got superseded) must propagate, not be treated as a
                // retryable failure — retrying after cancellation re-downloaded/re-uploaded files
                // that had already completed, and re-created "conflicted copy" duplicates.
                if (it is CancellationException) throw it
                diagnosticLogger.e(TAG, "File action threw path=${action.relativePath} attempt=$attempt/$maxAttempts", it)
                ActionResult.Failed
            }
            if (syncControl.shouldStop()) return ActionResult.Skipped
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
        is SyncAction.DownloadFile -> downloadFile(
            action.relativePath,
            action.remoteRelativePath,
            action.remoteSizeBytes,
            ctx,
            remoteEtag = action.remoteEtag,
        )
        is SyncAction.DeleteLocalFile -> deleteLocalFile(action.relativePath, ctx)
        is SyncAction.DeleteRemoteFile -> deleteRemoteFile(action.relativePath, action.remoteRelativePath, ctx)
        is SyncAction.Conflict -> resolveConflict(action, ctx)
        is SyncAction.RememberInSync -> rememberInSync(action, ctx)
        is SyncAction.MoveRemote -> moveRemote(action, ctx)
        is SyncAction.CreateLocalDirectory,
        is SyncAction.CreateRemoteDirectory,
        is SyncAction.DeleteLocalDirectory,
        is SyncAction.DeleteRemoteDirectory,
        is SyncAction.RememberDirectory,
        is SyncAction.DropBaseline,
        -> ActionResult.Skipped
    }

    /** [remoteRelativePath] is the server's actual name; it can differ from [relativePath] (the local-safe canonical path). */
    private suspend fun uploadFile(relativePath: String, remoteRelativePath: String, ctx: TransferContext): ActionResult {
        val local = localFileIo.statOrNull(ctx.localRootUri, relativePath)
        if (local == null) {
            diagnosticLogger.w(TAG, "Upload skipped — local file not found path=$relativePath")
            return ActionResult.Failed
        }
        if (local.sizeBytes < 0L) {
            diagnosticLogger.w(TAG, "Skip upload of unreadable local file path=$relativePath")
            return ActionResult.Skipped
        }
        if (ctx.uploadSizeLimitBytes != null && local.sizeBytes > ctx.uploadSizeLimitBytes) {
            diagnosticLogger.i(TAG, "Skip upload (size limit) path=$relativePath size=${local.sizeBytes}")
            return ActionResult.Skipped
        }

        diagnosticLogger.i(
            TAG,
            "Upload start path=$relativePath remote=$remoteRelativePath size=${local.sizeBytes}",
        )
        Log.i(TAG, "Upload start path=$relativePath size=${local.sizeBytes}")
        // Probe stream is openable before starting HTTP.
        localFileIo.openInputStream(ctx.localRootUri, relativePath)?.close()
            ?: run {
                diagnosticLogger.w(TAG, "Upload failed — cannot open local stream path=$relativePath")
                return ActionResult.Failed
            }
        val remotePath = RemotePaths.join(ctx.remoteRootPath, remoteRelativePath)
        val fileName = relativePath.substringAfterLast('/')
        // Re-open SAF stream on every writeTo (Digest retry); do not stage multi‑100MB to cache.
        val result = ctx.client.upload(
            remotePath = remotePath,
            contentType = MimeTypes.guess(fileName),
            contentLength = local.sizeBytes,
            openContent = {
                localFileIo.openInputStream(ctx.localRootUri, relativePath)
                    ?: throw java.io.IOException("Cannot re-open local stream path=$relativePath")
            },
        )
        if (result.isFailure) {
            diagnosticLogger.w(TAG, "Upload failed path=$relativePath: ${result.exceptionOrNull()?.message}")
            Log.w(TAG, "Upload failed path=$relativePath: ${result.exceptionOrNull()?.message}")
            // A cancelled PUT must not delete a copy that actually landed.
            if (!syncControl.shouldStop()) {
                runCatching { deleteIncompleteRemoteAfterFailedUpload(remotePath, local.sizeBytes, ctx) }
            }
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
        val hash = if (result.getOrNull().isNullOrBlank() || after.lastModifiedEpochMillis == 0L) {
            localFileIo.openInputStream(ctx.localRootUri, relativePath)?.use { ContentHash.sha256(it) }
        } else {
            null
        }
        saveBaseline(
            ctx.folderPairId,
            relativePath,
            after.sizeBytes,
            after.lastModifiedEpochMillis,
            etag = result.getOrNull(),
            hash = hash,
        )
        return ActionResult.Uploaded
    }

    /** [remoteRelativePath] is the server's actual name; it can differ from [relativePath] (the local-safe canonical path). */
    private suspend fun downloadFile(
        relativePath: String,
        remoteRelativePath: String,
        remoteSizeBytes: Long,
        ctx: TransferContext,
        remoteEtag: String? = null,
    ): ActionResult {
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
            diagnosticLogger.i(
                TAG,
                "Download start path=$relativePath remote=$remoteRelativePath size=$remoteSizeBytes",
            )
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
            val hash = if (remoteEtag.isNullOrBlank() || stat.lastModifiedEpochMillis == 0L) {
                localFileIo.openInputStream(ctx.localRootUri, relativePath)?.use { ContentHash.sha256(it) }
            } else {
                null
            }
            saveBaseline(
                ctx.folderPairId,
                relativePath,
                stat.sizeBytes,
                stat.lastModifiedEpochMillis,
                etag = remoteEtag,
                hash = hash,
            )
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
        val deleted = localFileIo.delete(ctx.localRootUri, relativePath)
        if (!deleted && localFileIo.statOrNull(ctx.localRootUri, relativePath) != null) {
            diagnosticLogger.w(TAG, "Local delete failed path=$relativePath")
            return ActionResult.Failed
        }
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
        // Never nest: if we somehow still get a Conflict on an existing conflicted-copy path,
        // reconcile by size instead of minting "… (conflicted copy) (conflicted copy)…".
        if (isConflictedCopyPath(relativePath)) {
            diagnosticLogger.w(TAG, "Skip nested conflict path=$relativePath — repairing by size")
            val local = localFileIo.statOrNull(ctx.localRootUri, relativePath)
            return if (local != null) {
                uploadFile(relativePath, action.remoteRelativePath, ctx)
            } else {
                downloadFile(relativePath, action.remoteRelativePath, remoteSizeBytes = 0L, ctx)
            }
        }

        val losingSide = if (action.winningSide == SyncAction.Side.LOCAL) SyncAction.Side.REMOTE else SyncAction.Side.LOCAL
        val conflictCopyPath = conflictResolver.conflictedCopyPath(relativePath, losingSide)

        val preserved = when (losingSide) {
            // Remote wins → local's current content is the loser, back it up as a local copy first.
            SyncAction.Side.LOCAL -> localFileIo.openInputStream(ctx.localRootUri, relativePath)?.use { input ->
                localFileIo.openOutputStream(ctx.localRootUri, conflictCopyPath)?.use { out -> input.copyTo(out) } != null
            } ?: false
            // Local wins → remote's current content is the loser, download it aside as a local copy first.
            SyncAction.Side.REMOTE -> preserveRemoteWithinLimit(action, conflictCopyPath, ctx)
        }
        if (!preserved) {
            diagnosticLogger.w(TAG, "Conflict preserve failed path=$relativePath")
            return ActionResult.Failed
        }

        val applied = when (action.winningSide) {
            SyncAction.Side.LOCAL -> uploadFile(relativePath, action.remoteRelativePath, ctx)
            SyncAction.Side.REMOTE -> downloadFile(
                relativePath,
                action.remoteRelativePath,
                remoteSizeBytes = action.remoteSizeBytes,
                ctx,
                remoteEtag = action.remoteEtag,
            )
        }
        if (applied is ActionResult.Failed) {
            return ActionResult.Failed
        }

        // Upload the preserved copy in the same pass so the next follow-up does not treat it as a
        // brand-new local file that races with a partial remote PUT after a timeout.
        val copyUpload = uploadFile(conflictCopyPath, conflictCopyPath, ctx)
        if (copyUpload is ActionResult.Failed || copyUpload is ActionResult.Skipped) {
            diagnosticLogger.w(
                TAG,
                "Conflict copy not uploaded yet path=$conflictCopyPath result=$copyUpload — will retry next pass",
            )
        }

        diagnosticLogger.i(TAG, "Conflict resolved path=$relativePath winner=${action.winningSide} copy=$conflictCopyPath")
        return ActionResult.ConflictResolved(action.winningSide)
    }

    private suspend fun saveBaseline(
        folderPairId: Long,
        relativePath: String,
        sizeBytes: Long,
        mtime: Long,
        etag: String? = null,
        hash: String? = null,
        isDirectory: Boolean = false,
    ) {
        syncFileStateRepository.upsert(
            SyncFileStateEntity(
                folderPairId = folderPairId,
                relativePath = relativePath,
                lastSyncedMtime = mtime,
                lastSyncedSize = sizeBytes,
                lastSyncedHash = hash,
                lastSyncedEtag = etag,
                isDirectory = isDirectory,
            ),
        )
    }

    private suspend fun rememberInSync(action: SyncAction.RememberInSync, ctx: TransferContext): ActionResult {
        val hash = if (action.remoteEtag.isNullOrBlank() || action.lastModifiedEpochMillis == 0L) {
            localFileIo.openInputStream(ctx.localRootUri, action.relativePath)?.use { ContentHash.sha256(it) }
        } else {
            null
        }
        saveBaseline(
            ctx.folderPairId,
            action.relativePath,
            action.sizeBytes,
            action.lastModifiedEpochMillis,
            etag = action.remoteEtag,
            hash = hash,
        )
        return ActionResult.Remembered
    }

    private suspend fun moveRemote(action: SyncAction.MoveRemote, ctx: TransferContext): ActionResult {
        val from = RemotePaths.join(ctx.remoteRootPath, action.remoteFromRelativePath)
        val to = RemotePaths.join(ctx.remoteRootPath, action.remoteToRelativePath)
        val moved = ctx.client.move(from, to)
        if (moved.isSuccess) {
            val local = localFileIo.statOrNull(ctx.localRootUri, action.relativePath)
            if (local != null) {
                saveBaseline(ctx.folderPairId, action.relativePath, local.sizeBytes, local.lastModifiedEpochMillis)
            }
            syncFileStateRepository.deleteForPath(ctx.folderPairId, action.fromRelativePath)
            diagnosticLogger.i(TAG, "Moved remote ${action.fromRelativePath} -> ${action.relativePath}")
            return ActionResult.Uploaded
        }
        diagnosticLogger.w(TAG, "MOVE failed ${action.fromRelativePath}: ${moved.exceptionOrNull()?.message}")
        val uploaded = uploadFile(action.relativePath, action.remoteToRelativePath, ctx)
        if (uploaded != ActionResult.Uploaded) return uploaded
        val deleted = deleteRemoteFile(action.fromRelativePath, action.remoteFromRelativePath, ctx)
        if (deleted == ActionResult.Failed) {
            diagnosticLogger.w(TAG, "Rename uploaded new path but old remote remains ${action.fromRelativePath}")
        }
        return ActionResult.Uploaded
    }

    /** Downloads the losing remote side under the same size limit as a normal download. */
    private suspend fun preserveRemoteWithinLimit(
        action: SyncAction.Conflict,
        conflictCopyPath: String,
        ctx: TransferContext,
    ): Boolean {
        val limit = ctx.downloadSizeLimitBytes
        if (limit != null && action.remoteSizeBytes > 0L && action.remoteSizeBytes > limit) {
            diagnosticLogger.i(TAG, "Skip conflict preserve (size limit) path=${action.relativePath}")
            return false
        }
        val remotePath = RemotePaths.join(ctx.remoteRootPath, action.remoteRelativePath)
        val stream = ctx.client.download(remotePath).getOrNull() ?: return false
        var exceeded = false
        val wrote = stream.use { input ->
            localFileIo.openOutputStream(ctx.localRootUri, conflictCopyPath)?.use { out ->
                if (limit != null) {
                    exceeded = copyLimited(input, out, limit)
                    !exceeded
                } else {
                    input.copyTo(out)
                    true
                }
            } ?: false
        }
        if (!wrote || exceeded) {
            localFileIo.delete(ctx.localRootUri, conflictCopyPath)
            return false
        }
        return true
    }

    private fun localSizes(actions: List<SyncAction>, ctx: TransferContext): Map<String, Long> {
        val sizes = mutableMapOf<String, Long>()
        for (action in actions) {
            when (action) {
                is SyncAction.UploadFile -> {
                    val size = localFileIo.statOrNull(ctx.localRootUri, action.relativePath)?.sizeBytes
                    if (size != null) sizes[action.relativePath] = size
                }
                is SyncAction.DeleteLocalFile -> if (action.sizeBytes >= 0) sizes[action.relativePath] = action.sizeBytes
                else -> Unit
            }
        }
        return sizes
    }

    private fun remoteSizes(actions: List<SyncAction>): Map<String, Long> {
        val sizes = mutableMapOf<String, Long>()
        for (action in actions) {
            when (action) {
                is SyncAction.DownloadFile -> sizes[action.relativePath] = action.remoteSizeBytes
                is SyncAction.DeleteRemoteFile -> if (action.sizeBytes >= 0) sizes[action.relativePath] = action.sizeBytes
                else -> Unit
            }
        }
        return sizes
    }

    private fun ActionResult.isTransferSuccess(): Boolean = when (this) {
        ActionResult.Uploaded, ActionResult.Downloaded -> true
        is ActionResult.ConflictResolved -> true
        else -> false
    }

    /** Pause/cancel checkpoint: await resume, then true if the worker should stop. */
    private suspend fun shouldAbortAtCheckpoint(): Boolean {
        if (syncControl.shouldStop()) return true
        syncControl.awaitWhilePaused()
        return syncControl.shouldStop()
    }

    private fun recordLiveProgress(result: ActionResult) {
        when (result) {
            ActionResult.Uploaded -> syncProgress.recordUploaded()
            ActionResult.Downloaded -> syncProgress.recordDownloaded()
            ActionResult.DeletedLocal -> syncProgress.recordDeletedDevice()
            ActionResult.DeletedRemote -> syncProgress.recordDeletedCloud()
            is ActionResult.ConflictResolved -> {
                if (result.winningSide == SyncAction.Side.LOCAL) {
                    syncProgress.recordUploaded()
                } else {
                    syncProgress.recordDownloaded()
                }
            }
            ActionResult.Skipped, ActionResult.Remembered, ActionResult.Failed -> Unit
        }
    }

    /**
     * After a failed PUT, drop a truncated remote object so the next pass uploads cleanly.
     * Only deletes when the remote size is strictly less than the local source size.
     */
    private suspend fun deleteIncompleteRemoteAfterFailedUpload(
        remotePath: String,
        localSizeBytes: Long,
        ctx: TransferContext,
    ) {
        val parent = remotePath.substringBeforeLast('/', missingDelimiterValue = "")
        val name = remotePath.substringAfterLast('/')
        val listing = ctx.client.list(if (parent.isEmpty()) "/" else parent).getOrNull() ?: return
        val remote = listing.firstOrNull { resource ->
            !resource.isDirectory && resource.path.trimEnd('/').substringAfterLast('/') == name
        } ?: return
        val remoteSize = remote.sizeBytes
        if (remoteSize in 1 until localSizeBytes) {
            diagnosticLogger.w(
                TAG,
                "Removing incomplete remote after failed upload path=$remotePath remoteSize=$remoteSize localSize=$localSizeBytes",
            )
            ctx.client.delete(remotePath)
        }
    }

    /**
     * Heavy multi-file uploads (e.g. ~375MB / 12 files) with parallel=4 used to hang: concurrent
     * SAF multi-stream reads + full-body temp staging exhausted memory/IO with no further logs.
     * Use serial concurrency when the batch is upload-heavy.
     */
    private fun effectiveFileParallelism(fileActions: List<SyncAction>, ctx: TransferContext): Int {
        val configured = ctx.maxParallelTransfers.coerceAtLeast(1)
        var uploadCount = 0
        var totalUploadBytes = 0L
        var maxUploadBytes = 0L
        for (action in fileActions) {
            when (action) {
                is SyncAction.UploadFile -> {
                    val size = localFileIo.statOrNull(ctx.localRootUri, action.relativePath)?.sizeBytes ?: 0L
                    uploadCount++
                    totalUploadBytes += size
                    if (size > maxUploadBytes) maxUploadBytes = size
                }
                is SyncAction.Conflict -> if (action.winningSide == SyncAction.Side.LOCAL) {
                    val size = localFileIo.statOrNull(ctx.localRootUri, action.relativePath)?.sizeBytes ?: 0L
                    uploadCount++
                    totalUploadBytes += size
                    if (size > maxUploadBytes) maxUploadBytes = size
                }
                else -> Unit
            }
        }
        if (uploadCount == 0) return configured
        if (maxUploadBytes >= LARGE_FILE_BYTES || totalUploadBytes >= HEAVY_BATCH_BYTES || uploadCount >= 4) {
            diagnosticLogger.i(
                TAG,
                "Throttling upload concurrency to 1 (uploads=$uploadCount totalBytes=$totalUploadBytes max=$maxUploadBytes)",
            )
            return 1
        }
        return configured
    }

    private companion object {
        const val TAG = "TransferExecutor"
        const val DEFAULT_COPY_BUFFER_SIZE = 8192
        const val LARGE_FILE_BYTES = 2L * 1024 * 1024 // 2 MiB
        const val HEAVY_BATCH_BYTES = 32L * 1024 * 1024 // 32 MiB total
    }
}
