package org.vovchenko.webdavsync.data.local.diagnostics

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Appends timestamped diagnostic lines to [DiagnosticLog] when the user enables
 * "Diagnostic log" in Settings (plan Phase 8). Thread-safe; redacts common secret patterns.
 */
@Singleton
class DiagnosticLogger @Inject constructor(
    context: Context,
    settingsRepository: SettingsRepository,
    appScope: CoroutineScope,
) {
    private val logFile: File = DiagnosticLog.file(context)
    private val enabled = AtomicBoolean(false)
    private val writeLock = Any()

    init {
        appScope.launch {
            // Seed from the current DataStore value so early process-start logs are not dropped.
            runCatching {
                enabled.set(settingsRepository.settings.first().diagnosticLogEnabled)
            }
            settingsRepository.settings
                .map { it.diagnosticLogEnabled }
                .distinctUntilChanged()
                .collect { enabled.set(it) }
        }
    }

    /** Immediate enable/disable so the toggle path does not race the settings Flow. */
    fun setEnabled(value: Boolean) {
        enabled.set(value)
    }

    fun isEnabled(): Boolean = enabled.get()

    fun exists(): Boolean = DiagnosticLog.exists(logFile)

    fun clear() {
        synchronized(writeLock) {
            if (logFile.exists()) logFile.writeText("")
        }
    }

    fun i(tag: String, message: String) = write("I", tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = write("W", tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = write("E", tag, message, throwable)

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        if (!enabled.get()) return

        val safeMessage = DiagnosticRedactor.redact(message)
        val error = throwable
        val safeThrowableMessage = error?.message?.let(DiagnosticRedactor::redact)
        val timestamp = TIMESTAMP_FORMAT.format(Date())
        val line = buildString {
            append(timestamp)
            append(' ')
            append(level)
            append('/')
            append(tag)
            append(": ")
            append(safeMessage)
            if (error != null && safeThrowableMessage != null) {
                append(" | ")
                append(error::class.java.simpleName)
                append(": ")
                append(safeThrowableMessage)
            } else if (error != null) {
                append(" | ")
                append(error::class.java.simpleName)
            }
            append('\n')
        }

        // Always mirror to logcat so adb/logcat and in-app export see the same progress
        // (helps debug hangs where the process dies mid-batch before file is re-read).
        when (level) {
            "E" -> Log.e(tag, safeMessage, throwable)
            "W" -> Log.w(tag, safeMessage, throwable)
            else -> Log.i(tag, safeMessage)
        }

        try {
            synchronized(writeLock) {
                ensureParent()
                rotateIfNeeded()
                // Write + fsync so a killed process still leaves the last progress line on disk.
                FileOutputStream(logFile, /* append = */ true).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.fd.sync()
                }
            }
        } catch (t: Throwable) {
            // Never let logging crash the app; fall back to logcat only.
            Log.w(TAG, "Failed to write diagnostic log", t)
        }
    }

    private fun ensureParent() {
        val parent = logFile.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
    }

    /** Keep the log bounded so a long-running device does not fill storage. */
    private fun rotateIfNeeded() {
        if (!logFile.exists() || logFile.length() < MAX_BYTES) return
        val content = logFile.readText()
        val keepFrom = (content.length / 2).coerceAtLeast(0)
        val trimmed = content.substring(keepFrom).substringAfter('\n', missingDelimiterValue = content.substring(keepFrom))
        logFile.writeText(
            "${TIMESTAMP_FORMAT.format(Date())} I/DiagnosticLogger: --- log rotated (size limit) ---\n$trimmed",
        )
    }

    private companion object {
        const val TAG = "DiagnosticLogger"
        const val MAX_BYTES = 2L * 1024L * 1024L // 2 MiB
        val TIMESTAMP_FORMAT = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}
