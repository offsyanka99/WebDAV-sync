package org.vovchenko.webdavsync.push

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.remote.WebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.remote.WebDavClientSession
import org.vovchenko.webdavsync.data.remote.WebDavException
import org.vovchenko.webdavsync.data.remote.push.PushDepth
import org.vovchenko.webdavsync.data.remote.push.PushRegistrationResult
import org.vovchenko.webdavsync.data.remote.push.PushSubscriptionRequest
import org.vovchenko.webdavsync.data.repository.PushRegistrationKey
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network side of WebDAV-Push upkeep: discover, request endpoints, register, renew, unregister
 * (plan §8.2, §8.6). Runs are serialized; rows are written with [PushRepository.updateIfUnchanged]
 * so concurrent coordinator or callback changes win over a stale network result.
 */
@Singleton
class PushRegistrationManager @Inject constructor(
    private val repository: PushRepository,
    private val accounts: PushAccountClients,
    private val clientFactory: WebDavClientFactory,
    private val distributor: PushDistributor,
    private val settingsRepository: SettingsRepository,
    private val diagnosticLogger: DiagnosticLogger,
) {
    private val mutex = Mutex()

    /** [retry]: a transient network failure stopped part of the run. */
    data class Outcome(val retry: Boolean)

    suspend fun reconcile(): Outcome = mutex.withLock { runAll(vapidCheck = false) }

    /** Daily fallback: distributor check, VAPID check, and anything still due. */
    suspend fun maintenance(): Outcome = mutex.withLock {
        checkDistributor()
        runAll(vapidCheck = true)
    }

    /**
     * End of a sync pass: renew and retry what is due for [accountIds], reusing the pass's open
     * connections. Skipped while another push run holds the lock.
     */
    suspend fun afterSyncPass(accountIds: Set<Long>, syncedPairIds: Collection<Long>, session: WebDavClientSession) {
        if (accountIds.isEmpty()) return
        if (!settingsRepository.settings.first().instantDownloadEnabled) return
        if (!mutex.tryLock()) return
        try {
            val synced = syncedPairIds.toSet()
            val rootHints = repository.desired().filterValues { ids -> ids.any(synced::contains) }.keys
            for (accountId in accountIds) {
                processAccount(accountId, session, vapidCheck = false, rootHints = rootHints)
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Before an account row is deleted: DELETE its registrations while its credentials still
     * exist, then drop its distributor instance. Best effort; server expiry is the backstop.
     */
    suspend fun unregisterAccount(accountId: Long) {
        withTimeoutOrNull(ACCOUNT_DELETE_TIMEOUT_MS) {
            mutex.withLock {
                val urls = repository.getRegistrationsForAccount(accountId).mapNotNull { it.registrationUrl }
                if (urls.isEmpty()) return@withLock
                clientFactory.openSession().use { session ->
                    val client = accounts.clientFor(session, accountId) ?: return@use
                    val failed = urls.count { url -> client.unregisterPush(url).isFailure }
                    log("Account $accountId push unregister: ${urls.size - failed}/${urls.size} removed")
                }
            }
        }
        if (repository.getEndpoint(accountId) != null) {
            repository.deleteEndpoint(accountId)
            distributor.unregister(PushEndpointEntity.instanceFor(accountId))
        }
    }

    private suspend fun runAll(vapidCheck: Boolean): Outcome {
        repository.applyDesired(repository.desired().keys)
        val rows = repository.getRegistrations()
        val accountIds = rows.map { it.accountId }.distinct()
        if (rows.isNotEmpty()) {
            val now = System.currentTimeMillis()
            val waiting = rows.count { it.nextAttemptAt != null && it.nextAttemptAt > now }
            log("Push run: ${rows.groupingBy { it.state }.eachCount()} in backoff=$waiting")
        }
        var retry = false
        if (accountIds.isNotEmpty()) {
            clientFactory.openSession().use { session ->
                for (accountId in accountIds) {
                    if (processAccount(accountId, session, vapidCheck, rootHints = emptySet())) retry = true
                }
            }
        }
        releaseUnusedEndpoints()
        return Outcome(retry)
    }

    /** Returns true when a transient failure stopped this account. */
    private suspend fun processAccount(
        accountId: Long,
        session: WebDavClientSession,
        vapidCheck: Boolean,
        rootHints: Set<PushRegistrationKey>,
    ): Boolean {
        val now = System.currentTimeMillis()
        var opened = false
        var openedClient: WebDavClient? = null
        suspend fun client(): WebDavClient? {
            if (!opened) {
                opened = true
                openedClient = accounts.clientFor(session, accountId)
                if (openedClient == null) log("Account $accountId cannot sign in; push upkeep skipped")
            }
            return openedClient
        }

        // 1. Unregister rows that are no longer wanted.
        for (row in repository.getRegistrationsForAccount(accountId)) {
            if (row.state != PushRegistrationState.PENDING_DELETE) continue
            val url = row.registrationUrl
            val c = if (url == null) null else client()
            if (url == null || c == null) {
                repository.deleteIfPendingDelete(row.id)
                continue
            }
            val error = c.unregisterPush(url).exceptionOrNull()
            if (error != null && error.isTransient() && (row.expiresAt ?: 0L) > now) return true
            repository.deleteIfPendingDelete(row.id)
            log(
                if (error == null) "Push unregistered id=${row.id}"
                else "Push unregister failed id=${row.id} (${error.javaClass.simpleName}); server expiry cleans up",
            )
        }

        // 2. Discover capabilities.
        var discoveredVapid: String? = null
        val current = repository.getRegistrationsForAccount(accountId)
        val vapidRow = if (vapidCheck) {
            current.filter { PushRegistrationPlanner.isVapidCheckDue(it, now) }.minByOrNull { it.lastCheckedAt ?: 0L }
        } else {
            null
        }
        val toDiscover = current.filter { PushRegistrationPlanner.needsDiscovery(it, now, rootHints) } + listOfNotNull(vapidRow)
        for (row in toDiscover) {
            val c = client() ?: return false
            val result = c.discoverPush(row.remotePath)
            val error = result.exceptionOrNull()
            if (error != null) {
                if (error.isTransient()) return true
                val next = when (error) {
                    is WebDavException.NotFound -> waitingForRoot(row, now)
                    is WebDavException.AuthenticationFailed -> failed(row, ERR_SIGN_IN, now)
                    else -> failed(row, describe(error), now)
                }
                update(row, next, "discover")
                continue
            }
            val capability = result.getOrNull()
            if (capability == null || capability.contentDepth == PushDepth.ZERO) {
                update(row, unsupported(row, now), "discover")
                continue
            }
            if (capability.vapidPublicKey != null) discoveredVapid = capability.vapidPublicKey
            val keepActive = row.state == PushRegistrationState.ACTIVE && row.topic == capability.topic
            update(
                row,
                row.copy(
                    state = if (keepActive) PushRegistrationState.ACTIVE else PushRegistrationState.WAITING_FOR_ENDPOINT,
                    topic = capability.topic,
                    advertisedDepth = capability.contentDepth.wire,
                    vapidPublicKey = capability.vapidPublicKey,
                    lastError = null,
                    nextAttemptAt = null,
                    lastCheckedAt = now,
                ),
                "discover topic=${capability.topic.take(TOPIC_LOG_CHARS)}… depth=${capability.contentDepth.wire}",
            )
        }

        // 3. Make sure the account has an endpoint made with the server's current VAPID key.
        val rows = repository.getRegistrationsForAccount(accountId)
        val wanting = rows.filter {
            it.state == PushRegistrationState.WAITING_FOR_ENDPOINT || it.state == PushRegistrationState.ACTIVE
        }
        if (wanting.isEmpty()) return false
        val vapid = discoveredVapid ?: wanting.maxByOrNull { it.lastCheckedAt ?: 0L }?.vapidPublicKey
        val endpoint = repository.getEndpoint(accountId)
        if (PushRegistrationPlanner.shouldRequestEndpoint(endpoint, vapid, now)) {
            requestEndpoint(accountId, endpoint, vapid, now)
            return false
        }
        val ready = endpoint?.takeIf {
            it.state == PushEndpointState.READY && it.endpointUrl != null && it.pubKey != null && it.authSecret != null
        } ?: return false

        // 4. Register new rows; renew ACTIVE rows that are due or predate the endpoint.
        var newBudget = PushRegistrationPlanner.MAX_NEW_REGISTRATIONS_PER_RUN
        for (row in rows) {
            if (!PushRegistrationPlanner.needsRegister(row, ready, now)) continue
            val c = client() ?: return false
            val previousUrl = row.registrationUrl
            val endpointChanged = previousUrl != null && row.endpointUrl != null && row.endpointUrl != ready.endpointUrl
            val isNew = previousUrl == null || endpointChanged
            if (isNew && newBudget <= 0) {
                log("Push registration budget used; id=${row.id} deferred")
                continue
            }
            // Draft §3.2: drop the subscription of the old push resource before registering the new one.
            if (endpointChanged && previousUrl != null) {
                c.unregisterPush(previousUrl).onFailure {
                    log("Old push registration id=${row.id} not removed (${it.javaClass.simpleName})")
                }
            }
            val request = PushSubscriptionRequest(
                endpointUrl = ready.endpointUrl!!,
                publicKey = ready.pubKey!!,
                authSecret = ready.authSecret!!,
                expiresAtMillis = now + PushRegistrationPlanner.REQUESTED_LIFETIME_MS,
            )
            val result = c.registerPush(row.remotePath, request)
            val error = result.exceptionOrNull()
            if (error != null) {
                if (error.isTransient()) return true
                val next = when (error) {
                    is WebDavException.NotFound -> waitingForRoot(row, now)
                    is WebDavException.AuthenticationFailed -> keepOrFail(row, ERR_SIGN_IN, now)
                    else -> keepOrFail(row, describe(error), now)
                }
                update(row, next, "register")
                continue
            }
            when (val outcome = result.getOrThrow()) {
                is PushRegistrationResult.Registered -> {
                    update(
                        row,
                        row.copy(
                            state = PushRegistrationState.ACTIVE,
                            registrationUrl = outcome.registrationUrl,
                            endpointUrl = ready.endpointUrl,
                            expiresAt = outcome.expiresAtMillis,
                            registeredAt = now,
                            lastError = null,
                            nextAttemptAt = null,
                        ),
                        if (isNew) "registered" else "renewed",
                    )
                    if (isNew) newBudget--
                }
                is PushRegistrationResult.Unsupported -> update(row, unsupported(row, now), "register ${outcome.condition}")
                PushRegistrationResult.InvalidSubscription -> {
                    update(row, failed(row, ERR_REJECTED, now), "register")
                    repository.upsertEndpoint(
                        ready.copy(state = PushEndpointState.FAILED, lastError = ERR_REJECTED, updatedAt = now),
                    )
                    return false
                }
                is PushRegistrationResult.RateLimited -> {
                    val retryAt = now + outcome.retryAfterSeconds * 1000
                    update(
                        row,
                        if (row.state == PushRegistrationState.ACTIVE) {
                            row.copy(lastError = ERR_RATE_LIMITED, nextAttemptAt = retryAt)
                        } else {
                            row.copy(state = PushRegistrationState.FAILED, lastError = ERR_RATE_LIMITED, nextAttemptAt = retryAt)
                        },
                        "register 429",
                    )
                    return false
                }
                is PushRegistrationResult.Failed ->
                    update(row, keepOrFail(row, describe(outcome), now), "register HTTP ${outcome.httpCode}")
            }
        }
        return false
    }

    private suspend fun requestEndpoint(accountId: Long, existing: PushEndpointEntity?, vapid: String?, now: Long) {
        val instance = PushEndpointEntity.instanceFor(accountId)
        val base = (existing ?: PushEndpointEntity(accountId, instance, PushEndpointState.REQUESTED, vapid, updatedAt = now))
            .copy(vapidPublicKey = vapid, updatedAt = now)
        if (distributor.savedDistributor() == null) {
            repository.upsertEndpoint(base.copy(state = PushEndpointState.FAILED, lastError = ERR_NO_DISTRIBUTOR))
            log("Account $accountId: no push service app selected")
            return
        }
        // Saved first: the answer can arrive on another thread before register() returns.
        repository.upsertEndpoint(base.copy(state = PushEndpointState.REQUESTED, lastError = null))
        if (!distributor.register(instance, distributorMessage(accountId), vapid)) {
            repository.upsertEndpoint(base.copy(state = PushEndpointState.FAILED, lastError = ERR_BAD_VAPID))
            log("Account $accountId: server push key rejected by the connector")
            return
        }
        log("Account $accountId: push endpoint requested")
    }

    private suspend fun checkDistributor() {
        val endpoints = repository.getEndpoints()
        if (endpoints.isEmpty()) return
        val now = System.currentTimeMillis()
        val present = distributor.ackDistributor() != null
        for (endpoint in endpoints.filter { it.state == PushEndpointState.READY }) {
            if (present) {
                // UnifiedPush recommends re-registering regularly; an unchanged endpoint is a no-op.
                distributor.register(endpoint.instance, distributorMessage(endpoint.accountId), endpoint.vapidPublicKey)
            } else {
                repository.upsertEndpoint(
                    endpoint.copy(state = PushEndpointState.FAILED, lastError = ERR_DISTRIBUTOR_MISSING, updatedAt = now),
                )
                log("Account ${endpoint.accountId}: push service app missing")
            }
        }
    }

    private suspend fun releaseUnusedEndpoints() {
        val used = repository.getRegistrations().map { it.accountId }.toSet()
        for (endpoint in repository.getEndpoints()) {
            if (endpoint.accountId in used) continue
            repository.deleteEndpoint(endpoint.accountId)
            distributor.unregister(endpoint.instance)
            log("Account ${endpoint.accountId}: push endpoint released")
        }
    }

    /** Shown by the distributor app next to this registration. */
    private suspend fun distributorMessage(accountId: Long): String =
        "WebDAV-sync · ${accounts.displayName(accountId) ?: "WebDAV"}"

    private suspend fun update(before: PushRegistrationEntity, after: PushRegistrationEntity, what: String) {
        val written = repository.updateIfUnchanged(before, after)
        log(
            "Push $what id=${before.id} ${before.state}→${after.state}" +
                (after.lastError?.let { " ($it)" } ?: "") +
                if (written) "" else " skipped (row changed meanwhile)",
        )
    }

    private fun waitingForRoot(row: PushRegistrationEntity, now: Long) = row.copy(
        state = PushRegistrationState.WAITING_FOR_ROOT,
        lastError = ERR_WAITING_FOR_ROOT,
        nextAttemptAt = now + PushRegistrationPlanner.WAITING_FOR_ROOT_RECHECK_MS,
        lastCheckedAt = now,
    )

    private fun unsupported(row: PushRegistrationEntity, now: Long) = row.copy(
        state = PushRegistrationState.UNSUPPORTED,
        lastError = ERR_UNSUPPORTED,
        nextAttemptAt = now + PushRegistrationPlanner.UNSUPPORTED_RECHECK_MS,
        lastCheckedAt = now,
    )

    private fun failed(row: PushRegistrationEntity, message: String, now: Long) = row.copy(
        state = PushRegistrationState.FAILED,
        lastError = message,
        nextAttemptAt = PushRegistrationPlanner.backoffAfter(row, now),
    )

    /** A failed renewal keeps a still-valid ACTIVE row routing pushes until it expires. */
    private fun keepOrFail(row: PushRegistrationEntity, message: String, now: Long): PushRegistrationEntity =
        if (row.state == PushRegistrationState.ACTIVE && (row.expiresAt ?: 0L) > now) {
            row.copy(lastError = message, nextAttemptAt = now + PushRegistrationPlanner.FIRST_BACKOFF_MS)
        } else {
            failed(row, message, now)
        }

    private fun Throwable.isTransient(): Boolean =
        this is WebDavException.NetworkError || this is WebDavException.Timeout

    /** User-safe reason with the HTTP status or failing check; never a URL. */
    private fun describe(error: Throwable): String = when (error) {
        is WebDavException.ServerError -> "$ERR_SERVER (HTTP ${error.httpCode})"
        is WebDavException.CertificateUntrusted -> "Server certificate is not trusted"
        is WebDavException.ForeignOrigin -> "Registration URL is on another server"
        else -> "$ERR_SERVER (${error.javaClass.simpleName})"
    }

    private fun describe(outcome: PushRegistrationResult.Failed): String = when {
        outcome.reason == PushRegistrationResult.NO_LOCATION -> ERR_NO_LOCATION
        outcome.httpCode == 403 -> "Server refused the subscription (HTTP 403)"
        else -> "$ERR_SERVER (HTTP ${outcome.httpCode})"
    }

    private fun log(message: String) = diagnosticLogger.i(TAG, message)

    companion object {
        private const val TAG = "Push"
        private const val TOPIC_LOG_CHARS = 6
        private const val ACCOUNT_DELETE_TIMEOUT_MS = 15_000L

        const val ERR_UNSUPPORTED = "The server does not offer push for this folder"
        const val ERR_WAITING_FOR_ROOT = "Waiting for the first sync to create the folder"
        const val ERR_SIGN_IN = "Sign-in failed"
        const val ERR_SERVER = "Server error"
        const val ERR_NO_LOCATION =
            "Server accepted the subscription but sent no usable https registration URL (server-side issue)"
        const val ERR_RATE_LIMITED = "Server limit for push subscriptions reached"
        const val ERR_REJECTED = "The server rejected the push service address (it must be public HTTPS)"
        const val ERR_NO_DISTRIBUTOR = "No push service app selected"
        const val ERR_DISTRIBUTOR_MISSING = "Push service app missing"
        const val ERR_BAD_VAPID = "The server's push key is not valid"
    }
}
