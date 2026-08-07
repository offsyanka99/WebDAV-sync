package org.vovchenko.webdavsync.data.local.saf

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject

/** Translates folder-pair-relative paths into SAF [DocumentFile] I/O for the sync engine (Phase 4). */
class LocalFileIo @Inject constructor(
    private val context: Context,
) {
    private fun root(rootUri: Uri): DocumentFile? = DocumentFile.fromTreeUri(context, rootUri)

    private fun segments(relativePath: String): List<String> = relativePath.split('/').filter { it.isNotEmpty() }

    fun find(rootUri: Uri, relativePath: String): DocumentFile? {
        var current = root(rootUri) ?: return null
        for (segment in segments(relativePath)) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** Creates any missing directories along [relativePath] (a directory path, not a file path). */
    fun ensureDirectory(rootUri: Uri, relativePath: String): DocumentFile? {
        var current = root(rootUri) ?: return null
        for (segment in segments(relativePath)) {
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
        }
        return current
    }

    fun openInputStream(rootUri: Uri, relativePath: String): InputStream? {
        val file = find(rootUri, relativePath) ?: return null
        return context.contentResolver.openInputStream(file.uri)
    }

    /** Opens a truncating output stream for [relativePath], creating parent dirs and the file as needed. */
    fun openOutputStream(rootUri: Uri, relativePath: String): OutputStream? {
        val parentPath = relativePath.substringBeforeLast('/', missingDelimiterValue = "")
        val fileName = relativePath.substringAfterLast('/')
        val parent = if (parentPath.isEmpty()) root(rootUri) else ensureDirectory(rootUri, parentPath)
        val target = parent?.findFile(fileName) ?: parent?.createFile(MimeTypes.guess(fileName), fileName) ?: return null
        return context.contentResolver.openOutputStream(target.uri, "wt")
    }

    fun delete(rootUri: Uri, relativePath: String): Boolean = find(rootUri, relativePath)?.delete() ?: false

    /**
     * Deletes only the *contents* of [relativePath] (recursively), never the folder document at
     * [relativePath] itself. Used for "also delete data" account/folder-pair removal (security
     * audit finding #2): [relativePath] is often the SAF tree root the user granted access to,
     * which may be a much broader folder (e.g. an entire SD card) than "this folder pair's data" —
     * deleting the tree-root document itself would be far more destructive than the user intends.
     */
    fun deleteContents(rootUri: Uri, relativePath: String): Boolean {
        val target = find(rootUri, relativePath) ?: return false
        if (!target.isDirectory) return target.delete()
        return target.listFiles().fold(true) { allDeleted, child -> deleteRecursively(child) && allDeleted }
    }

    private fun deleteRecursively(file: DocumentFile): Boolean {
        if (file.isDirectory) {
            val childrenDeleted = file.listFiles().fold(true) { allDeleted, child -> deleteRecursively(child) && allDeleted }
            if (!childrenDeleted) return false
        }
        return file.delete()
    }

    fun isEmptyDirectory(rootUri: Uri, relativePath: String): Boolean {
        val dir = find(rootUri, relativePath) ?: return false
        return dir.isDirectory && dir.listFiles().isEmpty()
    }

    fun statOrNull(rootUri: Uri, relativePath: String): LocalFileEntry? {
        val file = find(rootUri, relativePath) ?: return null
        return LocalFileEntry(relativePath, file.isDirectory, file.length(), file.lastModified())
    }
}
