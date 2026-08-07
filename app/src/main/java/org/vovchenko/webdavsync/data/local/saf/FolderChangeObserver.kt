package org.vovchenko.webdavsync.data.local.saf

import android.content.ContentResolver
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper

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

    fun start() {
        val obs = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onChange()
            }
        }
        observer = obs
        contentResolver.registerContentObserver(treeUri, true, obs)
    }

    fun stop() {
        observer?.let(contentResolver::unregisterContentObserver)
        observer = null
    }
}
