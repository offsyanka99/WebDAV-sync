package org.vovchenko.webdavsync.data.local.saf

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import javax.inject.Inject

/**
 * Cheap, **non-recursive** snapshot of a SAF tree root.
 *
 * One or two [DocumentsContract] queries (the document itself + immediate children). This is the
 * idle poll signal — nested edits that do not change root/child count, sizes, or mtimes are
 * caught by [FolderChangeObserver], a WorkManager content-URI trigger, or the next periodic sync.
 */
open class LocalTreeFingerprint @Inject constructor(
    private val context: Context,
) {
    open fun of(treeUri: Uri): Long = compute(context.contentResolver, treeUri)

    companion object {
        fun compute(resolver: ContentResolver, treeUri: Uri): Long = runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

            val rootMtime = queryRootMtime(resolver, documentUri)
            val children = queryImmediateChildren(resolver, childrenUri)
            pack(children.count, children.totalSize, rootMtime, children.maxMtime, children.idHash)
        }.getOrDefault(0L)

        fun pack(
            childCount: Int,
            totalSize: Long,
            rootMtime: Long,
            maxChildMtime: Long,
            idHash: Long = 0L,
        ): Long = 31L * childCount + 17L * totalSize + rootMtime + maxChildMtime + idHash

        private fun queryRootMtime(resolver: ContentResolver, documentUri: Uri): Long {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return 0L
                val idx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                return if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else 0L
            }
            return 0L
        }

        private fun queryImmediateChildren(resolver: ContentResolver, childrenUri: Uri): ChildStats {
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mtimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                var count = 0
                var totalSize = 0L
                var maxMtime = 0L
                var idHash = 0L
                while (cursor.moveToNext()) {
                    count++
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        totalSize += cursor.getLong(sizeIdx)
                    }
                    if (mtimeIdx >= 0 && !cursor.isNull(mtimeIdx)) {
                        val mtime = cursor.getLong(mtimeIdx)
                        if (mtime > maxMtime) maxMtime = mtime
                    }
                    if (idIdx >= 0 && !cursor.isNull(idIdx)) {
                        idHash = 31L * idHash + cursor.getString(idIdx).hashCode()
                    }
                }
                return ChildStats(count, totalSize, maxMtime, idHash)
            }
            return ChildStats()
        }

        private data class ChildStats(
            val count: Int = 0,
            val totalSize: Long = 0L,
            val maxMtime: Long = 0L,
            val idHash: Long = 0L,
        )
    }
}
