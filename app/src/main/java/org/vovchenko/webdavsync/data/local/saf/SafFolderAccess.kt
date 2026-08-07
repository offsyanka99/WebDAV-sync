package org.vovchenko.webdavsync.data.local.saf

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SAF folder-tree access helpers (plan §4.1: SAF-only local folder access, no
 * `MANAGE_EXTERNAL_STORAGE`). The actual `ACTION_OPEN_DOCUMENT_TREE` picker launch is a UI
 * concern (Phase 7); this class handles persisting/checking/releasing the resulting grant.
 */
@Singleton
class SafFolderAccess @Inject constructor(
    private val context: Context,
) {
    /** Persists read/write access to a tree the user just picked via `ACTION_OPEN_DOCUMENT_TREE`. */
    fun persistAccess(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
    }

    fun releaseAccess(treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.releasePersistableUriPermission(treeUri, flags)
    }

    /** True if we still hold a persisted grant for this tree (e.g. user hasn't revoked it via system settings). */
    fun hasAccess(treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }

    fun contentResolver(): ContentResolver = context.contentResolver
}
