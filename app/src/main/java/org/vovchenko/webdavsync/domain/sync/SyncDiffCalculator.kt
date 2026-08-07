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
        actions += computeDirectoryActions(syncMethod, localEntries, remoteEntries)
        actions += computeFileActions(syncMethod, localEntries, remoteEntries, baseline)
        return actions
    }

    private fun computeDirectoryActions(
        syncMethod: SyncMethod,
        localEntries: List<LocalFileEntry>,
        remoteEntries: List<RemoteFileEntry>,
    ): List<SyncAction> {
        val localDirs = localEntries.filter { it.isDirectory }.map { it.relativePath }.toSet()
        // Canonicalize to the local-safe path: some SAF providers rewrite unsafe characters (e.g. `?` → `_`)
        // when a directory is created, so matching against the remote's raw name would never agree.
        val remoteDirs = remoteEntries.filter { it.isDirectory }
            .map { LocalNameSanitizer.sanitizeRelativePath(it.relativePath) }.toSet()
        // Shallowest first so parent directories are created before their children.
        val allDirs = (localDirs + remoteDirs).sortedBy { path -> path.count { it == '/' } }

        val actions = mutableListOf<SyncAction>()
        for (dirPath in allDirs) {
            val inLocal = dirPath in localDirs
            val inRemote = dirPath in remoteDirs
            when (syncMethod) {
                SyncMethod.TWO_WAY -> {
                    if (inRemote && !inLocal) actions += SyncAction.CreateLocalDirectory(dirPath)
                    if (inLocal && !inRemote) actions += SyncAction.CreateRemoteDirectory(dirPath)
                }
                SyncMethod.TO_DEVICE -> if (inRemote && !inLocal) actions += SyncAction.CreateLocalDirectory(dirPath)
                SyncMethod.TO_CLOUD -> if (inLocal && !inRemote) actions += SyncAction.CreateRemoteDirectory(dirPath)
            }
        }
        return actions
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
