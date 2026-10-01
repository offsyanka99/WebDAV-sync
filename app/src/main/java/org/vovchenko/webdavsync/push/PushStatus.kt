package org.vovchenko.webdavsync.push

import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.model.SyncMethod
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** User-facing WebDAV-Push status of a folder pair. Never contains URLs. */
object PushStatus {
    enum class Tone { OK, WARN, ERROR, NEUTRAL }

    data class Line(val text: String, val tone: Tone)

    /** Null while the feature is off: nothing is shown. */
    fun forPair(
        pair: FolderPairEntity,
        settings: AppSettings,
        registrations: List<PushRegistrationEntity>,
        endpoints: List<PushEndpointEntity>,
        formatDate: (Long) -> String,
    ): String? = lineFor(pair, settings, registrations, endpoints, formatDate)?.text

    fun lineFor(
        pair: FolderPairEntity,
        settings: AppSettings,
        registrations: List<PushRegistrationEntity>,
        endpoints: List<PushEndpointEntity>,
        formatDate: (Long) -> String = ::formatDate,
    ): Line? {
        if (!settings.instantDownloadEnabled) return null
        if (pair.syncMethod == SyncMethod.TO_CLOUD) return Line("$PREFIX not used for To cloud", Tone.NEUTRAL)
        if (!pair.enabled) return Line("$PREFIX off while this folder pair is disabled", Tone.NEUTRAL)
        val key = PushRegistrationPlanner.keyFor(pair) ?: return Line("$PREFIX not available for this path", Tone.WARN)
        val row = registrations.firstOrNull { PushRegistrationPlanner.keyOf(it) == key }
            ?: return Line("$PREFIX setting up…", Tone.NEUTRAL)
        val endpoint = endpoints.firstOrNull { it.accountId == pair.accountId }
        return when (row.state) {
            PushRegistrationState.ACTIVE -> Line(
                row.expiresAt?.let { "$PREFIX active until ${formatDate(it)}" } ?: "$PREFIX active",
                if (row.lastError != null) Tone.WARN else Tone.OK,
            )
            PushRegistrationState.DISCOVER -> Line("$PREFIX checking the server…", Tone.NEUTRAL)
            PushRegistrationState.UNSUPPORTED -> Line("$PREFIX not supported by this server", Tone.WARN)
            PushRegistrationState.WAITING_FOR_ROOT -> Line("$PREFIX waiting for first sync", Tone.NEUTRAL)
            PushRegistrationState.WAITING_FOR_ENDPOINT -> when (endpoint?.state) {
                PushEndpointState.FAILED, PushEndpointState.UNREGISTERED ->
                    Line("$PREFIX push service problem: ${endpoint.lastError ?: "unregistered"}", Tone.ERROR)
                else -> Line("$PREFIX waiting for the push service", Tone.NEUTRAL)
            }
            PushRegistrationState.FAILED -> Line("$PREFIX failed: ${row.lastError ?: "unknown error"}", Tone.ERROR)
            PushRegistrationState.PENDING_DELETE -> Line("$PREFIX turning off…", Tone.NEUTRAL)
        }
    }

    fun formatDate(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private const val PREFIX = "Server push:"
}
