package org.vovchenko.webdavsync.push

import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.data.remote.WebDavPathSafety
import org.vovchenko.webdavsync.data.repository.PushRegistrationKey

/** Pure WebDAV-Push decisions: which roots to subscribe, when to renew, and what to suppress. */
object PushRegistrationPlanner {
    const val REQUESTED_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000
    const val RENEW_WINDOW_MAX_MS = 3L * 24 * 60 * 60 * 1000
    const val UNSUPPORTED_RECHECK_MS = 7L * 24 * 60 * 60 * 1000
    const val WAITING_FOR_ROOT_RECHECK_MS = 24L * 60 * 60 * 1000
    const val VAPID_CHECK_MS = 7L * 24 * 60 * 60 * 1000
    const val FIRST_BACKOFF_MS = 15L * 60 * 1000
    const val REPEATED_BACKOFF_MS = 6L * 60 * 60 * 1000
    /** A distributor that never answered a request is asked again after this. */
    const val ENDPOINT_REQUEST_TIMEOUT_MS = 24L * 60 * 60 * 1000
    /** The server allows 30 new subscriptions per hour per user. */
    const val MAX_NEW_REGISTRATIONS_PER_RUN = 10

    /** To-cloud pairs never act on remote changes, so they are not subscribed. */
    fun isEligible(pair: FolderPairEntity, settings: AppSettings): Boolean =
        settings.instantDownloadEnabled && pair.enabled && pair.syncMethod != SyncMethod.TO_CLOUD

    fun keyFor(pair: FolderPairEntity): PushRegistrationKey? =
        runCatching { WebDavPathSafety.sanitize(pair.remoteFolderPath) }.getOrNull()
            ?.let { PushRegistrationKey(pair.accountId, it) }

    /** One subscription per distinct (account, remote root), mapped to the pairs it serves. */
    fun desired(pairs: List<FolderPairEntity>, settings: AppSettings): Map<PushRegistrationKey, Set<Long>> {
        if (!settings.instantDownloadEnabled) return emptyMap()
        val result = LinkedHashMap<PushRegistrationKey, MutableSet<Long>>()
        for (pair in pairs) {
            if (!isEligible(pair, settings)) continue
            val key = keyFor(pair) ?: continue
            result.getOrPut(key) { linkedSetOf() } += pair.id
        }
        return result
    }

    fun keyOf(row: PushRegistrationEntity) = PushRegistrationKey(row.accountId, row.remotePath)

    /** Renew when less than min(3 days, half the granted lifetime) remains. */
    fun isRenewDue(row: PushRegistrationEntity, nowMillis: Long): Boolean {
        if (row.state != PushRegistrationState.ACTIVE) return false
        val expires = row.expiresAt ?: return true
        val remaining = expires - nowMillis
        if (remaining <= 0) return true
        val granted = row.registeredAt?.let { expires - it }?.takeIf { it > 0 } ?: REQUESTED_LIFETIME_MS
        return remaining < minOf(RENEW_WINDOW_MAX_MS, granted / 2)
    }

    /** An ACTIVE row must be POSTed again: due, made with another endpoint, or older than the endpoint. */
    fun needsRegister(row: PushRegistrationEntity, endpoint: PushEndpointEntity, nowMillis: Long): Boolean = when (row.state) {
        PushRegistrationState.WAITING_FOR_ENDPOINT -> true
        PushRegistrationState.ACTIVE ->
            isDue(row.nextAttemptAt, nowMillis) && (
                isRenewDue(row, nowMillis) ||
                    row.endpointUrl != endpoint.endpointUrl ||
                    (row.registeredAt ?: 0L) < endpoint.updatedAt
                )
        else -> false
    }

    /** Rows that need a capability PROPFIND now. */
    fun needsDiscovery(
        row: PushRegistrationEntity,
        nowMillis: Long,
        rootHints: Set<PushRegistrationKey>,
    ): Boolean = when (row.state) {
        PushRegistrationState.DISCOVER -> true
        PushRegistrationState.UNSUPPORTED, PushRegistrationState.FAILED -> isDue(row.nextAttemptAt, nowMillis)
        PushRegistrationState.WAITING_FOR_ROOT -> isDue(row.nextAttemptAt, nowMillis) || keyOf(row) in rootHints
        else -> false
    }

    fun isVapidCheckDue(row: PushRegistrationEntity, nowMillis: Long): Boolean =
        row.state == PushRegistrationState.ACTIVE &&
            (row.lastCheckedAt == null || nowMillis - row.lastCheckedAt >= VAPID_CHECK_MS)

    fun isDue(nextAttemptAt: Long?, nowMillis: Long): Boolean = nextAttemptAt == null || nextAttemptAt <= nowMillis

    fun backoffAfter(row: PushRegistrationEntity, nowMillis: Long): Long =
        nowMillis + if (row.state == PushRegistrationState.FAILED) REPEATED_BACKOFF_MS else FIRST_BACKOFF_MS

    /** True when the account needs a fresh endpoint request to the distributor. */
    fun shouldRequestEndpoint(endpoint: PushEndpointEntity?, vapid: String?, nowMillis: Long): Boolean {
        if (endpoint == null) return true
        if (vapid != null && vapid != endpoint.vapidPublicKey) return true
        val age = nowMillis - endpoint.updatedAt
        return when (endpoint.state) {
            PushEndpointState.READY -> false
            PushEndpointState.REQUESTED -> age >= ENDPOINT_REQUEST_TIMEOUT_MS
            PushEndpointState.FAILED, PushEndpointState.UNREGISTERED -> age >= REPEATED_BACKOFF_MS
        }
    }

    /**
     * Registration URLs to send as `Push-Dont-Notify` while [pairId] syncs: only registrations that
     * serve this pair alone, so a shared root still wakes the other pair.
     */
    fun suppressionUrls(
        pairId: Long,
        rows: List<PushRegistrationEntity>,
        desired: Map<PushRegistrationKey, Set<Long>>,
    ): List<String> = rows
        .filter { it.state == PushRegistrationState.ACTIVE && desired[keyOf(it)] == setOf(pairId) }
        .mapNotNull { it.registrationUrl }
}
