package org.vovchenko.webdavsync.data.local.saf

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.vovchenko.webdavsync.domain.sync.ScanLimits
import org.vovchenko.webdavsync.util.RelativePaths
import java.io.IOException
import javax.inject.Inject

/**
 * Recursively enumerates a SAF folder tree into [LocalFileEntry] rows, honoring
 * `excludeHiddenFiles` and the per-folder-pair exclusion list.
 *
 * One [DocumentsContract] query per directory, with name, size, mime type, and
 * last-modified in the projection. A failed query throws instead of looking like
 * an empty tree (an empty listing would be synced as a mass delete).
 */
class LocalTreeScanner @Inject constructor(
    private val context: Context,
) {
    fun scan(
        rootUri: Uri,
        excludeHiddenFiles: Boolean,
        excludedSubfolders: List<String>,
    ): List<LocalFileEntry> {
        val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
        val results = mutableListOf<LocalFileEntry>()
        walk(rootUri, rootDocId, relativePrefix = "", excludeHiddenFiles, excludedSubfolders, results)
        return results
    }

    private fun walk(
        treeUri: Uri,
        parentDocId: String,
        relativePrefix: String,
        excludeHiddenFiles: Boolean,
        excludedSubfolders: List<String>,
        out: MutableList<LocalFileEntry>,
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val cursor = context.contentResolver.query(
            childrenUri,
            PROJECTION,
            null,
            null,
            null,
        ) ?: throw IOException("Could not list $childrenUri")
        cursor.use { rows ->
            val idIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val mtimeIdx = rows.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (rows.moveToNext()) {
                val name = if (nameIdx >= 0 && !rows.isNull(nameIdx)) rows.getString(nameIdx) else continue
                if (excludeHiddenFiles && name.startsWith(".")) continue
                if (PathFilters.isTemporary(name)) continue

                val relativePath = RelativePaths.joinRelative(relativePrefix, name)
                if (PathExclusion.isExcluded(relativePath, excludedSubfolders)) continue
                val depth = if (relativePath.isEmpty()) 0 else relativePath.count { it == '/' } + 1
                ScanLimits.check(depth, out.size + 1)

                val mime = if (mimeIdx >= 0 && !rows.isNull(mimeIdx)) rows.getString(mimeIdx) else null
                val isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (sizeIdx >= 0 && !rows.isNull(sizeIdx)) rows.getLong(sizeIdx) else 0L
                val mtime = if (mtimeIdx >= 0 && !rows.isNull(mtimeIdx)) rows.getLong(mtimeIdx) else 0L
                out.add(
                    LocalFileEntry(
                        relativePath = relativePath,
                        isDirectory = isDirectory,
                        sizeBytes = size,
                        lastModifiedEpochMillis = mtime,
                    ),
                )
                if (isDirectory && idIdx >= 0 && !rows.isNull(idIdx)) {
                    val childId = rows.getString(idIdx)
                    walk(treeUri, childId, relativePath, excludeHiddenFiles, excludedSubfolders, out)
                }
            }
        }
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}
