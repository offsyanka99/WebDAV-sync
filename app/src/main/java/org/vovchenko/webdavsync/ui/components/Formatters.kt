package org.vovchenko.webdavsync.ui.components

import java.text.DateFormat
import java.util.Date
import kotlin.math.ln
import kotlin.math.pow

/** Small formatting helpers shared by the Overview/Folders/Settings screens. */
object Formatters {

    fun timestamp(millis: Long?): String {
        if (millis == null) return "\u2014"
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
    }

    fun duration(millis: Long?): String {
        if (millis == null) return "\u2014"
        val totalSeconds = (millis.coerceAtLeast(0L) + 500L) / 1000L // round to nearest second
        if (totalSeconds < 60) return "${totalSeconds}s"
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (minutes < 60) return "${minutes}m ${seconds}s"
        val hours = minutes / 60
        val remMin = minutes % 60
        return "${hours}h ${remMin}m ${seconds}s"
    }

    /**
     * Human size. Uses 2 decimal places for GB/TB so e.g. 9.97 GB free is not rounded up to
     * "10.0 GB free of 10.0 GB" (which hides small used amounts like 26 MB).
     */
    fun bytes(value: Long?): String {
        if (value == null) return "\u2014"
        if (value < 0L) return "\u2014"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        val exponent = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val scaled = value / 1024.0.pow(exponent.toDouble())
        val unit = units[exponent - 1]
        return when (unit) {
            "KB" -> "%.0f %s".format(scaled, unit)
            "MB" -> if (scaled >= 100) "%.0f %s".format(scaled, unit) else "%.1f %s".format(scaled, unit)
            else -> "%.2f %s".format(scaled, unit) // GB, TB
        }
    }

    /**
     * Overview Cloud Storage line: "{used} of {total}" (e.g. "4.2 MB of 10.00 GB").
     * [used] is derived from total − available when the server only reports free + capacity.
     */
    fun storageSummary(available: Long?, used: Long?, total: Long?): String {
        val resolvedUsed = used
            ?: if (available != null && total != null && total >= available) total - available else null
        val resolvedTotal = total
            ?: if (available != null && resolvedUsed != null) available + resolvedUsed else null

        if (resolvedUsed == null && resolvedTotal == null) {
            return "Not reported by server"
        }
        if (resolvedUsed == null) {
            return "— of ${bytes(resolvedTotal)}"
        }
        if (resolvedTotal == null) {
            return "${bytes(resolvedUsed)} used"
        }
        return "${bytes(resolvedUsed)} of ${bytes(resolvedTotal)}"
    }
}
