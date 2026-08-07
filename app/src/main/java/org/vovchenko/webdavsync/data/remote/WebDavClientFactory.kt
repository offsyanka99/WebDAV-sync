package org.vovchenko.webdavsync.data.remote

import android.content.Context
import okhttp3.OkHttpClient
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.auth.BasicAuthStrategy
import org.vovchenko.webdavsync.data.remote.auth.DigestAuthStrategy
import org.vovchenko.webdavsync.data.remote.auth.WebDavAuthStrategy
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Builds a per-account [WebDavClient] with the account's auth scheme and trusted certificate wired in. */
class WebDavClientFactory @Inject constructor(
    private val context: Context,
) {

    fun create(
        baseUrl: String,
        authScheme: AuthScheme,
        credentials: WebDavCredentials,
        trustedCertificateBytes: ByteArray?,
    ): WebDavClient {
        val okHttpClient = buildOkHttpClient(authScheme, credentials, trustedCertificateBytes)
        val uploadCache = File(context.cacheDir, "webdav-uploads")
        return SardineWebDavClient(okHttpClient, baseUrl, uploadCache)
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
    ): OkHttpClient {
        val trust = TrustedCertTrustManagerFactory.build(trustedCertificateBytes)
        val builder = baseBuilder().sslSocketFactory(trust.sslSocketFactory, trust.trustManager)
        val authStrategy: WebDavAuthStrategy = when (authScheme) {
            AuthScheme.BASIC -> BasicAuthStrategy()
            AuthScheme.DIGEST -> DigestAuthStrategy()
        }
        authStrategy.apply(builder, credentials)
        return builder.build()
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Security audit finding #6: don't silently follow a redirect to another host/scheme —
        // that could otherwise leak Basic/Digest credentials to an attacker-controlled origin.
        .followRedirects(false)
        .followSslRedirects(false)
}
