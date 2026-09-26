package org.vovchenko.webdavsync.domain.sync

import org.vovchenko.webdavsync.data.local.saf.PathExclusion
import org.vovchenko.webdavsync.data.local.saf.PathFilters
import org.vovchenko.webdavsync.data.remote.DavHref
import org.vovchenko.webdavsync.data.remote.WebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavPathSafety
import org.vovchenko.webdavsync.util.RelativePaths
import javax.inject.Inject

/** One remote file/folder entry, relative to a folder pair's remote root. */
data class RemoteFileEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMillis: Long?,
    val etag: String?,
)

/** Recursively walks a remote folder via repeated `PROPFIND` (Depth 1) calls, honoring `excludedSubfolders` (§4.7). */
class RemoteTreeScanner @Inject constructor() {

    suspend fun scan(
        client: WebDavClient,
        rootPath: String,
        excludedSubfolders: List<String>,
        excludeHiddenFiles: Boolean = false,
    ): Result<List<RemoteFileEntry>> {
        val results = mutableListOf<RemoteFileEntry>()
        val failure = walk(
            client, rootPath, relativePrefix = "", excludedSubfolders, excludeHiddenFiles, depth = 1, results,
        )
        return failure?.let { Result.failure(it) } ?: Result.success(results)
    }

    /** Returns the first error encountered, or null on success. */
    private suspend fun walk(
        client: WebDavClient,
        remotePath: String,
        relativePrefix: String,
        excludedSubfolders: List<String>,
        excludeHiddenFiles: Boolean,
        depth: Int,
        out: MutableList<RemoteFileEntry>,
    ): Throwable? {
        val limit = runCatching { ScanLimits.check(depth, out.size) }.exceptionOrNull()
        if (limit != null) return limit
        val children = client.list(remotePath).getOrElse { return it }
        val parentNormalized = WebDavPathSafety.sanitize(remotePath)

        for (resource in children) {
            // Decode last segment so "test%201" / "test 1" both become "test 1".
            val name = DavHref.childName(resource.path) ?: continue
            if (excludeHiddenFiles && name.startsWith(".")) continue
            if (PathFilters.isTemporary(name)) continue

            val relativePath = RelativePaths.joinRelative(relativePrefix, name)
            if (PathExclusion.isExcluded(relativePath, excludedSubfolders)) continue
            val capped = runCatching { ScanLimits.check(depth, out.size + 1) }.exceptionOrNull()
            if (capped != null) return capped

            val childRemotePath = RemotePaths.join(remotePath, name)
            // Defensive: never re-enter the same collection (encoding mismatches can still leak self).
            if (WebDavPathSafety.sanitize(childRemotePath) == parentNormalized) continue

            out.add(
                RemoteFileEntry(
                    relativePath = relativePath,
                    isDirectory = resource.isDirectory,
                    sizeBytes = resource.sizeBytes,
                    lastModifiedEpochMillis = resource.lastModifiedEpochMillis,
                    etag = resource.etag,
                ),
            )

            if (resource.isDirectory) {
                val failure = walk(
                    client, childRemotePath, relativePath, excludedSubfolders, excludeHiddenFiles, depth + 1, out,
                )
                if (failure != null) return failure
            }
        }
        return null
    }
}

/** Small helper for joining WebDAV path segments without producing doubled/missing slashes. */
object RemotePaths {
    fun join(base: String, segment: String): String {
        // Treat "/" or blank as account root so join("/", "Test 1") → "Test 1", not "//Test 1".
        val trimmedBase = base.trim().trimEnd('/').trimStart('/').let { root ->
            if (root.isEmpty()) "" else root
        }
        val trimmedSeg = segment.trim().trimStart('/')
        return when {
            trimmedBase.isEmpty() -> trimmedSeg
            trimmedSeg.isEmpty() -> trimmedBase
            else -> "$trimmedBase/$trimmedSeg"
        }
    }

    /** @see RelativePaths.joinRelative */
    fun joinRelative(prefix: String, name: String): String = RelativePaths.joinRelative(prefix, name)
}
