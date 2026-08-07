package org.vovchenko.webdavsync.data.local.saf

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract

/**
 * Watches a SAF folder tree for changes via [ContentResolver.registerContentObserver] (recursive),
 * feeding "instant upload" / "immediately on local changes" (plan §4.3).
 */
class FolderChangeObserver(
    private val contentResolver: ContentResolver,
    private val treeUri: Uri,
    private val onChange: () -> Unit,
) {
    private var observer: ContentObserver? = null
    private var childrenUri: Uri? = null

    fun start() {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
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
