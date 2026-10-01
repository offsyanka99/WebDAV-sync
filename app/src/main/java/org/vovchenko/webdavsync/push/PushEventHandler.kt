package org.vovchenko.webdavsync.push

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.remote.push.WebDavPushXml
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns UnifiedPush callbacks into state changes and work requests (plan §8.3, §8.4). Database and
 * WorkManager only; network work is left to [PushRegistrationManager].
 */
@Singleton
class PushEventHandler @Inject constructor(
    private val repository: PushRepository,
    private val folderPairRepository: FolderPairRepository,
    private val scheduler: SyncScheduler,
    private val diagnosticLogger: DiagnosticLogger,
) {
    suspend fun onNewEndpoint(
        instance: String,
        url: String,
        pubKey: String?,
        authSecret: String?,
        temporary: Boolean,
    ) {
        val accountId = knownAccount(instance) ?: return
        val existing = repository.getEndpoint(accountId)
        val base = existing ?: PushEndpointEntity(
            accountId = accountId,
            instance = instance,
            state = PushEndpointState.REQUESTED,
            vapidPublicKey = null,
            updatedAt = 0L,
        )
        val next = when {
            pubKey == null || authSecret == null ->
                base.copy(state = PushEndpointState.FAILED, lastError = ERR_NOT_ENCRYPTED)
            url.toHttpUrlOrNull()?.isHttps != true ->
                base.copy(state = PushEndpointState.FAILED, lastError = ERR_NOT_HTTPS)
            else -> base.copy(
                state = PushEndpointState.READY,
                endpointUrl = url,
                pubKey = pubKey,
                authSecret = authSecret,
                temporary = temporary,
                lastError = null,
            )
        }
        // Distributors repeat unchanged endpoints; keeping updatedAt avoids needless renewals.
        if (existing != null && next == existing) return
        repository.upsertEndpoint(next.copy(updatedAt = System.currentTimeMillis()))
        log("Endpoint $instance ${next.state} origin=${origin(url)} temporary=$temporary")
        if (next.state == PushEndpointState.READY) scheduler.enqueuePushReconcile()
    }

    suspend fun onMessage(instance: String, content: ByteArray, decrypted: Boolean) {
        if (!decrypted) {
            log("Push message ignored: not decrypted")
            return
        }
        val accountId = accountIdOf(instance) ?: return log("Push message ignored: unknown instance")
        val message = WebDavPushXml.parseMessage(content) ?: return log("Push message ignored: unreadable")
        if (message.isVapidRotation) {
            repository.markForRediscovery(accountId)
            scheduler.enqueuePushReconcile()
            log("Push key rotation announced for $instance")
            return
        }
        if (!message.contentUpdate) return
        val pairIds = repository.pairIdsForTopics(accountId, message.topics)
        val topicPrefix = message.topics.first().take(TOPIC_LOG_CHARS)
        if (pairIds.isEmpty()) {
            log("Push message for $instance topic=$topicPrefix… matches no folder")
            return
        }
        folderPairRepository.markRemoteChangePending(pairIds, System.currentTimeMillis())
        scheduler.enqueuePushSync(pairIds)
        log("Push message routed $instance topic=$topicPrefix… pairs=$pairIds")
    }

    suspend fun onRegistrationFailed(instance: String, reason: String) {
        val accountId = accountIdOf(instance) ?: return
        val existing = repository.getEndpoint(accountId) ?: return
        val message = when (reason) {
            "NETWORK" -> "The push service could not be reached"
            "ACTION_REQUIRED" -> "The push service app needs attention"
            "VAPID_REQUIRED" -> "The push service requires a server key"
            else -> "The push service reported an error"
        }
        repository.upsertEndpoint(
            existing.copy(state = PushEndpointState.FAILED, lastError = message, updatedAt = System.currentTimeMillis()),
        )
        log("Endpoint $instance registration failed: $reason")
    }

    suspend fun onUnregistered(instance: String) {
        val accountId = accountIdOf(instance) ?: return
        val existing = repository.getEndpoint(accountId) ?: return
        repository.upsertEndpoint(
            existing.copy(state = PushEndpointState.UNREGISTERED, updatedAt = System.currentTimeMillis()),
        )
        // Server registrations point at a dead push resource; they are replaced once a new endpoint arrives.
        repository.markEndpointLost(accountId)
        log("Endpoint $instance unregistered by the push service")
    }

    fun onTempUnavailable(instance: String) {
        log("Push service temporarily unavailable for $instance")
    }

    /** An instance we asked for: it has an endpoint row or registrations. */
    private suspend fun knownAccount(instance: String): Long? {
        val accountId = accountIdOf(instance) ?: return null
        if (repository.getEndpoint(accountId) == null && repository.getRegistrationsForAccount(accountId).isEmpty()) {
            log("Endpoint for unknown instance ignored")
            return null
        }
        return accountId
    }

    private fun origin(url: String): String =
        url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}" } ?: "invalid"

    private fun log(message: String) = diagnosticLogger.i(TAG, message)

    companion object {
        private const val TAG = "Push"
        private const val TOPIC_LOG_CHARS = 6
        private const val INSTANCE_PREFIX = "account-"
        const val ERR_NOT_ENCRYPTED = "The push service does not support encrypted Web Push"
        const val ERR_NOT_HTTPS = "The push service address must use HTTPS"

        fun accountIdOf(instance: String): Long? =
            instance.takeIf { it.startsWith(INSTANCE_PREFIX) }?.removePrefix(INSTANCE_PREFIX)?.toLongOrNull()
    }
}
