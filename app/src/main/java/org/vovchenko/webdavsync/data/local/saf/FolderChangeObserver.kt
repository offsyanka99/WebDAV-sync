package org.vovchenko.webdavsync.data.local.saf

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Watches a SAF folder tree for changes via [ContentResolver.registerContentObserver] (recursive),
 * feeding "instant upload" / "immediately on local changes" (plan §4.3).
 *
 * Callbacks are delivered on the provider's thread (no main-looper [android.os.Handler]) so the
 * coordinator can hop to its own dispatcher without janking the UI.
 */
class FolderChangeObserver(
    private val contentResolver: ContentResolver,
    val treeUri: Uri,
    private val onChange: () -> Unit,
) {
    private var observer: ContentObserver? = null
    private var childrenUri: Uri? = null

    fun start() {
        // Null handler: dispatchChange calls onChange on the notifyChange thread, not the main looper.
        val obs = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                onChange()
            }

            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange()
            }
        }
        observer = obs
        contentResolver.registerContentObserver(treeUri, true, obs)
        // Many providers notify on the tree's child-documents URI rather than the tree root.
        runCatching {
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            contentResolver.registerContentObserver(children, true, obs)
            childrenUri = children
        }
    }

    fun stop() {
        val obs = observer ?: return
        contentResolver.unregisterContentObserver(obs)
        childrenUri?.let { runCatching { contentResolver.unregisterContentObserver(obs) } }
        observer = null
        childrenUri = null
    }
}
