package org.vovchenko.webdavsync.data.local.security

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Parses and describes a user-imported server certificate before it is trusted. */
object CertificateDescriptions {
    const val MAX_BYTES = 1024 * 1024

    data class Described(
        val certificate: X509Certificate,
        val subject: String,
        val sha256Fingerprint: String,
    )

    fun parse(bytes: ByteArray): Described? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(bytes)) as X509Certificate
        }.getOrNull() ?: return null
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(certificate.encoded)
            .joinToString(":") { byte -> "%02X".format(byte) }
        return Described(
            certificate = certificate,
            subject = certificate.subjectX500Principal.name,
            sha256Fingerprint = fingerprint,
        )
    }
}
