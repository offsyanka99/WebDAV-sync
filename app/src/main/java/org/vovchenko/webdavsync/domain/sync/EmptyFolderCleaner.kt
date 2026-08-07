package org.vovchenko.webdavsync.domain.sync

import android.net.Uri
import org.vovchenko.webdavsync.data.local.saf.LocalFileIo
import org.vovchenko.webdavsync.data.local.saf.PathExclusion
import org.vovchenko.webdavsync.data.remote.WebDavClient
import javax.inject.Inject

/** Post-sync empty-folder cleanup on both sides (plan §4.8) — never removes an excluded folder. */
class EmptyFolderCleaner @Inject constructor(
    private val localFileIo: LocalFileIo,
) {
    fun cleanLocal(rootUri: Uri, directoryPaths: List<String>, excludedSubfolders: List<String>): Int {
        var deleted = 0
        for (path in deepestFirst(directoryPaths, excludedSubfolders)) {
            if (localFileIo.isEmptyDirectory(rootUri, path) && localFileIo.delete(rootUri, path)) {
                deleted++
            }
        }
        return deleted
    }

    suspend fun cleanRemote(
        client: WebDavClient,
        remoteRootPath: String,
        directoryPaths: List<String>,
        excludedSubfolders: List<String>,
    ): Int {
        var deleted = 0
        for (path in deepestFirst(directoryPaths, excludedSubfolders)) {
            val remotePath = RemotePaths.join(remoteRootPath, path)
            val children = client.list(remotePath).getOrNull() ?: continue
            if (children.isEmpty() && client.delete(remotePath).isSuccess) {
                deleted++
            }
        }
        return deleted
    }

    private fun deepestFirst(directoryPaths: List<String>, excludedSubfolders: List<String>): List<String> =
        directoryPaths
            .filterNot { PathExclusion.isExcluded(it, excludedSubfolders) }
            .sortedByDescending { path -> path.count { it == '/' } }
}
