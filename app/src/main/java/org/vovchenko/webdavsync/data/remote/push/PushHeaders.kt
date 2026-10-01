package org.vovchenko.webdavsync.data.remote.push

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/** HTTP header helpers for WebDAV-Push: dates, `Retry-After`, and registration URL validation. */
object PushHeaders {
    const val MAX_REGISTRATION_URL_LENGTH = 2048
    const val DEFAULT_RETRY_AFTER_SECONDS = 3600L
    private const val MAX_RETRY_AFTER_SECONDS = 7L * 24 * 3600

    private val imfFixdate: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).withZone(ZoneOffset.UTC)

    fun formatHttpDate(epochMillis: Long): String = imfFixdate.format(Instant.ofEpochMilli(epochMillis))

    fun parseHttpDate(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return try {
            ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** `Retry-After` as delta-seconds or an HTTP date; [DEFAULT_RETRY_AFTER_SECONDS] when absent or unreadable. */
    fun parseRetryAfterSeconds(value: String?, nowMillis: Long): Long {
        val trimmed = value?.trim().orEmpty()
        val seconds = trimmed.toLongOrNull()
            ?: parseHttpDate(trimmed)?.let { (it - nowMillis) / 1000 }
            ?: DEFAULT_RETRY_AFTER_SECONDS
        return seconds.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
    }

    /**
     * A registration URL is stored and later echoed in `Push-Dont-Notify`, so it must be an
     * absolute `https` URL of printable ASCII with no quote or backslash.
     */
    fun isValidRegistrationUrl(value: String?): Boolean =
        isHeaderSafeUrl(value) && value?.toHttpUrlOrNull()?.isHttps == true

    /** Absolute http(s) URL of printable ASCII, no quote or backslash, at most 2048 chars. */
    fun isHeaderSafeUrl(value: String?): Boolean {
        if (value.isNullOrEmpty() || value.length > MAX_REGISTRATION_URL_LENGTH) return false
        if (value.any { it.code !in 0x21..0x7e || it == '"' || it == '\\' }) return false
        return value.toHttpUrlOrNull() != null
    }
}
