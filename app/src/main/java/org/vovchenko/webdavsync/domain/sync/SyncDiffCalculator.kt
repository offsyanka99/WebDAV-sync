package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.SyncFileStateEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.local.saf.LocalNameSanitizer
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.domain.model.SyncAction
import javax.inject.Inject

/**
 * Three-way diff (local vs remote vs last-known-state) producing the ordered list of
 * [SyncAction]s for one folder pair (plan Phase 4).
 */
class SyncDiffCalculator @Inject constructor() {

    fun computeActions(
        syncMethod: SyncMethod,
        localEntries: List<LocalFileEntry>,
        remoteEntries: List<RemoteFileEntry>,
        baseline: List<SyncFileStateEntity>,
    ): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()
        actions += computeDirectoryActions(syncMethod, localEntries, remoteEntries, baseline)
        actions += computeFileActions(
            syncMethod,
            localEntries,
            remoteEntries,
            baseline.filterNot { it.isDirectory },
        )
        return actions
    }

    private fun computeDirectoryActions(
        syncMethod: SyncMethod,
        localEntries: List<LocalFileEntry>,
        remoteEntries: List<RemoteFileEntry>,
        baseline: List<SyncFileStateEntity>,
    ): List<SyncAction> {
        val localDirs = localEntries.filter { it.isDirectory }.map { it.relativePath }.toSet()
        // Canonicalize to the local-safe path: some SAF providers rewrite unsafe characters (e.g. `?` → `_`)
        // when a directory is created, so matching against the remote's raw name would never agree.
        val remoteDirRaw = remoteEntries.filter { it.isDirectory }
            .associate { LocalNameSanitizer.sanitizeRelativePath(it.relativePath) to it.relativePath }
        val baselineDirs = baseline.filter { it.isDirectory }.map { it.relativePath }.toSet()
        // Shallowest first so parent directories are created before their children.
        // Deletes are re-ordered deepest-first by the executor.
        val allDirs = (localDirs + remoteDirRaw.keys + baselineDirs).sortedBy { path -> path.count { it == '/' } }

        val actions = mutableListOf<SyncAction>()
        for (dirPath in allDirs) {
            val inLocal = dirPath in localDirs
            val inRemote = dirPath in remoteDirRaw
            val inBaseline = dirPath in baselineDirs
            val remotePath = remoteDirRaw[dirPath] ?: dirPath
            when (syncMethod) {
                SyncMethod.TWO_WAY -> directoryAction(
                    actions, dirPath, remotePath, inLocal, inRemote, inBaseline,
                    mirrorLocal = true,
                    mirrorRemote = true,
                )
                SyncMethod.TO_DEVICE -> directoryAction(
                    actions, dirPath, remotePath, inLocal, inRemote, inBaseline,
                    mirrorLocal = true,
                    mirrorRemote = false,
                )
                SyncMethod.TO_CLOUD -> directoryAction(
                    actions, dirPath, remotePath, inLocal, inRemote, inBaseline,
                    mirrorLocal = false,
                    mirrorRemote = true,
                )
            }
        }
        return actions
    }

    /**
     * [mirrorLocal] pulls directories onto the device. [mirrorRemote] pushes them to the server.
     * A baselined directory removed on the source side is deleted on the mirror side.
     * To-device treats remote as the source; to-cloud treats local as the source; two-way uses both.
     */
    private fun directoryAction(
        actions: MutableList<SyncAction>,
        dirPath: String,
        remotePath: String,
        inLocal: Boolean,
        inRemote: Boolean,
        inBaseline: Boolean,
        mirrorLocal: Boolean,
        mirrorRemote: Boolean,
    ) {
        when {
            inLocal && inRemote -> if (!inBaseline) actions += SyncAction.RememberDirectory(dirPath)
            inBaseline && !inLocal && !inRemote -> actions += SyncAction.DropBaseline(dirPath)
            inRemote && !inLocal && mirrorLocal && (!inBaseline || !mirrorRemote) ->
                actions += SyncAction.CreateLocalDirectory(dirPath)
            inLocal && !inRemote && mirrorRemote && (!inBaseline || !mirrorLocal) ->
                actions += SyncAction.CreateRemoteDirectory(dirPath)
            inBaseline && !inLocal && inRemote && mirrorRemote ->
                actions += SyncAction.DeleteRemoteDirectory(dirPath, remotePath)
            inBaseline && inLocal && !inRemote && mirrorLocal ->
                actions += SyncAction.DeleteLocalDirectory(dirPath)
        }
    }

    private fun computeFileActions(
        syncMethod: SyncMethod,
        localEntries: List<LocalFileEntry>,
        remoteEntries: List<RemoteFileEntry>,
        baseline: List<SyncFileStateEntity>,
    ): List<SyncAction> {
        val localByPath = localEntries.filterNot { it.isDirectory }.associateBy { it.relativePath }
        // Same canonicalization as directories above (see comment there) so a rewritten local file
        // name isn't mistaken for a brand new local-only file on every sync pass.
        val remoteByPath = remoteEntries.filterNot { it.isDirectory }
            .associateBy { LocalNameSanitizer.sanitizeRelativePath(it.relativePath) }
        val baselineByPath = baseline.associateBy { it.relativePath }

        val allFilePaths = localByPath.keys + remoteByPath.keys + baselineByPath.keys
        val strategy = SyncMethodStrategy.forMethod(syncMethod)

        return allFilePaths.mapNotNull { path ->
            strategy.computeFileAction(path, localByPath[path], remoteByPath[path], baselineByPath[path])
        }
    }
}
