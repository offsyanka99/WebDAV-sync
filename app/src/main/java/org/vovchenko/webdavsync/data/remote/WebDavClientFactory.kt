package org.vovchenko.webdavsync.data.remote

import android.content.Context
import okhttp3.OkHttpClient
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.auth.BasicAuthStrategy
import org.vovchenko.webdavsync.data.remote.auth.DigestAuthStrategy
import org.vovchenko.webdavsync.data.remote.auth.WebDavAuthStrategy
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a per-account [WebDavClient] with the account's auth scheme and trusted certificate wired in.
 * [openSession] reuses one client per account for a sync pass. Callers of [create] close the client.
 */
@Singleton
class WebDavClientFactory @Inject constructor(
    private val context: Context,
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
        val bodyClient = buildOkHttpClient(authScheme, credentials, trustedCertificateBytes, pushDontNotify)
        val metaClient = bodyClient.newBuilder()
            .readTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(METADATA_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        val uploadCache = File(context.cacheDir, "webdav-uploads")
        return SardineWebDavClient(bodyClient, metaClient, baseUrl, uploadCache, pushDontNotify)
    }

    companion object {
        const val METADATA_TIMEOUT_SECONDS = 60L

        fun shutdown(client: OkHttpClient) {
            client.dispatcher.cancelAll()
            val executor = client.dispatcher.executorService
            // evictAll closes pooled sockets and writes on the caller. Add account resumes on
            // the main thread, and Android throws NetworkOnMainThreadException there.
            val closePool = Runnable {
                runCatching { client.connectionPool.evictAll() }
                runCatching { executor.shutdown() }
            }
            if (executor.isShutdown) {
                closePool.run()
            } else {
                runCatching { executor.execute(closePool) }.onFailure { closePool.run() }
            }
        }
    }

    /** Builds a bare client (no auth applied yet) for the auth-scheme detection probe. */
    fun createProbeClient(trustedCertificateBytes: ByteArray?): OkHttpClient {
        val trust = TrustedCertTrustManagerFactory.build(trustedCertificateBytes)
        return baseBuilder()
            .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
            .build()
    }

    private fun buildOkHttpClient(
        authScheme: AuthScheme,
        credentials: WebDavCredentials,
        trustedCertificateBytes: ByteArray?,
        pushDontNotify: PushDontNotify,
    ): OkHttpClient {
        val trust = TrustedCertTrustManagerFactory.build(trustedCertificateBytes)
        val builder = baseBuilder()
            .sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
            .addInterceptor(pushDontNotify.interceptor)
        val authStrategy: WebDavAuthStrategy = when (authScheme) {
            AuthScheme.BASIC -> BasicAuthStrategy()
            AuthScheme.DIGEST -> DigestAuthStrategy()
        }
        authStrategy.apply(builder, credentials)
        return builder.build()
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Long body timeouts for multi-MB photos/APKs on mobile — but not infinite: a stuck
        // socket used to leave Status "Sync in process..." with no server traffic forever.
        // 30 minutes per read/write window is enough for large files on slow links.
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        // Bounds a trickle that never goes idle long enough to trip the read/write window.
        .callTimeout(6, TimeUnit.HOURS)
        .addInterceptor { chain ->
            val call = chain.call()
            inFlightCalls.register(call)
            try {
                chain.proceed(chain.request())
            } finally {
                inFlightCalls.unregister(call)
            }
        }
        // Security audit finding #6: don't silently follow a redirect to another host/scheme —
        // that could otherwise leak Basic/Digest credentials to an attacker-controlled origin.
        .followRedirects(false)
        .followSslRedirects(false)
}

/** One OkHttp client per account for the duration of a sync pass. [close] drops idle sockets. */
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
