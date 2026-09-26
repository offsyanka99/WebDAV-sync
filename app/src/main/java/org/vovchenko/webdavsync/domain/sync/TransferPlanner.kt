package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.domain.model.SyncAction

/**
 * Orders file actions so a rename cannot delete the only remaining copy first,
 * and turns a unique size match into a remote MOVE.
 */
object TransferPlanner {
    data class GuardedDelete(
        val action: SyncAction,
        /** When set, skip this delete unless [SyncAction.relativePath] of this path succeeded. */
        val afterRelativePath: String? = null,
    )

    data class Plan(
        val primary: List<SyncAction>,
        val guardedDeletes: List<GuardedDelete>,
    )

    fun plan(
        fileActions: List<SyncAction>,
        localSizes: Map<String, Long>,
        remoteSizes: Map<String, Long>,
    ): Plan {
        val uploads = fileActions.filterIsInstance<SyncAction.UploadFile>()
        val downloads = fileActions.filterIsInstance<SyncAction.DownloadFile>()
        val remoteDeletes = fileActions.filterIsInstance<SyncAction.DeleteRemoteFile>()
        val localDeletes = fileActions.filterIsInstance<SyncAction.DeleteLocalFile>()

        val uploadBySize = uniqueBySize(uploads.map { it.relativePath to (localSizes[it.relativePath] ?: -1L) })
        val remoteDeleteBySize = uniqueBySize(remoteDeletes.map { it.relativePath to (remoteSizes[it.relativePath] ?: -1L) })
        val downloadBySize = uniqueBySize(downloads.map { it.relativePath to it.remoteSizeBytes })
        val localDeleteBySize = uniqueBySize(localDeletes.map { it.relativePath to (localSizes[it.relativePath] ?: remoteSizes[it.relativePath] ?: -1L) })

        val movedUploads = mutableSetOf<String>()
        val movedRemoteDeletes = mutableSetOf<String>()
        val moves = mutableListOf<SyncAction.MoveRemote>()
        for ((size, uploadPath) in uploadBySize) {
            val deletePath = remoteDeleteBySize[size] ?: continue
            if (size < 0L) continue
            val upload = uploads.first { it.relativePath == uploadPath }
            val delete = remoteDeletes.first { it.relativePath == deletePath }
            moves += SyncAction.MoveRemote(
                relativePath = upload.relativePath,
                fromRelativePath = delete.relativePath,
                remoteFromRelativePath = delete.remoteRelativePath,
                remoteToRelativePath = upload.remoteRelativePath,
            )
            movedUploads += uploadPath
            movedRemoteDeletes += deletePath
        }

        val guardedLocalDeletes = mutableSetOf<String>()
        val localGuards = mutableListOf<GuardedDelete>()
        for ((size, downloadPath) in downloadBySize) {
            val deletePath = localDeleteBySize[size] ?: continue
            if (size <= 0L) continue
            val delete = localDeletes.first { it.relativePath == deletePath }
            localGuards += GuardedDelete(delete, afterRelativePath = downloadPath)
            guardedLocalDeletes += deletePath
        }

        val primary = buildList {
            fileActions.forEach { action ->
                when (action) {
                    is SyncAction.UploadFile -> if (action.relativePath !in movedUploads) add(action)
                    is SyncAction.DeleteRemoteFile -> Unit
                    is SyncAction.DeleteLocalFile -> Unit
                    else -> add(action)
                }
            }
            addAll(moves)
        }
        val deletes = buildList {
            addAll(localGuards)
            localDeletes.filter { it.relativePath !in guardedLocalDeletes }.forEach { add(GuardedDelete(it)) }
            remoteDeletes.filter { it.relativePath !in movedRemoteDeletes }.forEach { add(GuardedDelete(it)) }
        }
        return Plan(primary, deletes)
    }

    /** Size → path, only for sizes that occur once and are non-negative. */
    private fun uniqueBySize(items: List<Pair<String, Long>>): Map<Long, String> {
        val grouped = items.groupBy { it.second }
        val unique = mutableMapOf<Long, String>()
        for ((size, group) in grouped) {
            if (size < 0L || group.size != 1) continue
            unique[size] = group.first().first
        }
        return unique
    }
}
