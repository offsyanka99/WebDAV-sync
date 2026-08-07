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
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
    }

    fun bytes(value: Long?): String {
        if (value == null) return "\u2014"
        if (value < 1024) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        val exponent = (ln(value.toDouble()) / ln(1024.0)).toInt().coerceIn(1, units.size)
        val scaled = value / 1024.0.pow(exponent.toDouble())
        return "%.1f %s".format(scaled, units[exponent - 1])
    }
}
