package org.vovchenko.webdavsync.data.remote.trust

import org.vovchenko.webdavsync.data.local.security.CertificateDescriptions
import java.security.KeyStore
import java.security.cert.CertificateException
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
        val systemTrustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        systemTrustManagerFactory.init(null as KeyStore?)
        val systemTrustManager = systemTrustManagerFactory.trustManagers
            .filterIsInstance<X509TrustManager>()
            .first()
        val pinnedLeaf = customCertificateBytes?.let { bytes ->
            CertificateDescriptions.parse(bytes)?.certificate
                ?: throw CertificateException("Imported certificate is not a valid X.509 certificate")
        }
        // The imported certificate is accepted only as the presented leaf, not as a CA that
        // can sign other hosts. System CAs stay on the platform trust manager.
        val trustManager = if (pinnedLeaf == null) {
            systemTrustManager
        } else {
            leafPinningTrustManager(systemTrustManager, pinnedLeaf)
        }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        return TrustConfig(sslContext.socketFactory, trustManager)
    }

    private fun leafPinningTrustManager(
        system: X509TrustManager,
        pinnedLeaf: X509Certificate,
    ): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            system.checkClientTrusted(chain, authType)
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                system.checkServerTrusted(chain, authType)
            } catch (rejected: CertificateException) {
                val leaf = chain?.firstOrNull() ?: throw rejected
                leaf.checkValidity()
                if (!leaf.publicKey.encoded.contentEquals(pinnedLeaf.publicKey.encoded)) {
                    throw rejected
                }
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }
}
