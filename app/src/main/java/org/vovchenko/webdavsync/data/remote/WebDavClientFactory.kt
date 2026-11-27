package org.vovchenko.webdavsync.data.remote

import io.ktor.client.HttpClient
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.ktor.buildAccountHttpClient
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a per-account [WebDavClient] with the account's auth scheme and trusted certificate wired in.
 * [openSession] reuses one client per account for a sync pass. Callers of [create] close the client.
 */
@Singleton
class WebDavClientFactory @Inject constructor(
    private val inFlightCalls: InFlightCallRegistry,
) {

    fun openSession(): WebDavClientSession = WebDavClientSession(this)

    fun create(
        baseUrl: String,
        authScheme: AuthScheme,
        credentials: WebDavCredentials,
        trustedCertificateBytes: ByteArray?,
    ): WebDavClient {
        val pushDontNotify = PushDontNotify()
        val trust = TrustedCertTrustManagerFactory.build(trustedCertificateBytes)
        val accountClient = buildAccountHttpClient(
            trustManager = trust.trustManager,
            authScheme = authScheme,
            accountCredentials = credentials,
            pushDontNotify = pushDontNotify,
            inFlight = inFlightCalls,
        )
        return Dav4jvmWebDavClient(baseUrl, pushDontNotify, accountClient)
    }

    companion object {
        /** Closes the account client off the caller thread. Add account resumes on the main thread. */
        fun shutdown(client: HttpClient) {
            enqueueClose { runCatching { client.close() } }
        }

        internal fun enqueueClose(block: () -> Unit) {
            closer.execute(block)
        }

        private val closer = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "webdav-client-close").apply { isDaemon = true }
        }
    }

    /**
     * Unauthenticated client for [org.vovchenko.webdavsync.data.remote.auth.AuthSchemeDetector].
     * No Basic header, no Digest provider.
     */
    fun createProbeClient(trustedCertificateBytes: ByteArray?): HttpClient {
        val trust = TrustedCertTrustManagerFactory.build(trustedCertificateBytes)
        return buildAccountHttpClient(
            trustManager = trust.trustManager,
            authScheme = null,
            accountCredentials = null,
            pushDontNotify = null,
            inFlight = inFlightCalls,
        )
    }
}

/** One account client per account for the duration of a sync pass. [close] drops idle sockets. */
class WebDavClientSession(
    private val factory: WebDavClientFactory,
) : java.io.Closeable {
    private val clients = LinkedHashMap<Long, WebDavClient>()

    fun clientFor(
        accountId: Long,
        baseUrl: String,
        authScheme: AuthScheme,
        credentials: WebDavCredentials,
        trustedCertificateBytes: ByteArray?,
    ): WebDavClient = clients.getOrPut(accountId) {
        factory.create(baseUrl, authScheme, credentials, trustedCertificateBytes)
    }

    /** Accounts whose client this session has opened. */
    fun accountIds(): Set<Long> = clients.keys.toSet()

    fun clearPushDontNotify() {
        clients.values.forEach { it.setPushDontNotify(emptyList()) }
    }

    override fun close() {
        clients.values.forEach { client -> runCatching { client.close() } }
        clients.clear()
    }
}
