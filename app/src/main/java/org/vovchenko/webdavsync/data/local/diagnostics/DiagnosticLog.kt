package org.vovchenko.webdavsync.data.local.diagnostics

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * Paths and share helper for the diagnostic log file under `files/diagnostics/`.
 * Writing is done by [DiagnosticLogger] when Settings → Diagnostic log is enabled.
 */
object DiagnosticLog {
    private const val FILE_NAME = "diagnostic.log"
    private const val DIR_NAME = "diagnostics"

    fun file(context: Context): File = File(File(context.filesDir, DIR_NAME), FILE_NAME)

    fun exists(context: Context): Boolean = exists(file(context))

    fun exists(file: File): Boolean = file.exists() && file.length() > 0L

    fun shareIntent(context: Context): Intent? {
        val logFile = file(context)
        if (!exists(logFile)) return null
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", logFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "WebDAV-sync diagnostic log")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
