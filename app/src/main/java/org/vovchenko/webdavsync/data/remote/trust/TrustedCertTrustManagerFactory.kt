package org.vovchenko.webdavsync.data.remote.trust

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds an [SSLSocketFactory]/[X509TrustManager] that trusts the system CA store *plus* one
 * optional per-account imported certificate (plan §4.6). Deliberately never trusts-all — that
 * disables MITM protection entirely and is an OWASP-flagged anti-pattern.
 */
object TrustedCertTrustManagerFactory {

    data class TrustConfig(
        val sslSocketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    fun build(customCertificateBytes: ByteArray?): TrustConfig {
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
        }

        // Seed with the system's default trusted CAs.
        val systemTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTrustManagerFactory.init(null as KeyStore?)
        val systemTrustManager = systemTrustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
        systemTrustManager.acceptedIssuers.forEachIndexed { index, cert ->
            keyStore.setCertificateEntry("system-$index", cert)
        }

        if (customCertificateBytes != null) {
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(customCertificateBytes)) as X509Certificate
            keyStore.setCertificateEntry("custom-trusted-cert", certificate)
        }

        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(keyStore)
        val trustManager = trustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        return TrustConfig(sslContext.socketFactory, trustManager)
    }
}
