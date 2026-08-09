package org.vovchenko.webdavsync.data.local.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.vovchenko.webdavsync.util.RelativePaths
import javax.inject.Inject

/**
 * Recursively enumerates a SAF folder tree into [LocalFileEntry] rows, honoring
 * `excludeHiddenFiles` and the per-folder-pair exclusion list (plan §4.7).
 */
class LocalTreeScanner @Inject constructor(
    private val context: Context,
) {
    fun scan(
        rootUri: Uri,
        excludeHiddenFiles: Boolean,
        excludedSubfolders: List<String>,
    ): List<LocalFileEntry> {
        val root = DocumentFile.fromTreeUri(context, rootUri) ?: return emptyList()
        val results = mutableListOf<LocalFileEntry>()
        walk(root, relativePrefix = "", excludeHiddenFiles, excludedSubfolders, results)
        return results
    }

    private fun walk(
        dir: DocumentFile,
        relativePrefix: String,
        excludeHiddenFiles: Boolean,
        excludedSubfolders: List<String>,
        out: MutableList<LocalFileEntry>,
    ) {
        for (child in dir.listFiles()) {
            val name = child.name ?: continue
            if (excludeHiddenFiles && name.startsWith(".")) continue

            val relativePath = RelativePaths.joinRelative(relativePrefix, name)
            if (isExcluded(relativePath, excludedSubfolders)) continue

            out.add(
                LocalFileEntry(
                    relativePath = relativePath,
                    isDirectory = child.isDirectory,
                    sizeBytes = child.length(),
                    lastModifiedEpochMillis = child.lastModified(),
                ),
            )
            if (child.isDirectory) {
                walk(child, relativePath, excludeHiddenFiles, excludedSubfolders, out)
            }
        }
    }

    private fun isExcluded(relativePath: String, patterns: List<String>): Boolean =
        PathExclusion.isExcluded(relativePath, patterns)
}
