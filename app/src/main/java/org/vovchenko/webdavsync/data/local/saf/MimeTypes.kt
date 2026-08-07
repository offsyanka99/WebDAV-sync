package org.vovchenko.webdavsync.data.local.saf

import android.webkit.MimeTypeMap

/** Best-effort content-type guess from a file name's extension, used for both SAF and WebDAV writes. */
object MimeTypes {
    fun guess(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }
}
