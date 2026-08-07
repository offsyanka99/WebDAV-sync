package org.vovchenko.webdavsync.data.local.security

import android.content.Context
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-account trusted custom CA/self-signed certificate storage (plan §4.6). Not secret, so a
 * plain private-app-storage file is enough — it's tied 1:1 with the account it belongs to.
 */
@Singleton
class TrustedCertStore @Inject constructor(
    context: Context,
) {
    private val dir = File(context.filesDir, "trusted_certs").apply { mkdirs() }

    /** Saves the raw PEM/DER bytes of the certificate the user chose to trust for this account. */
    fun save(accountId: Long, certificateBytes: ByteArray) {
        file(accountId).writeBytes(certificateBytes)
    }

    fun get(accountId: Long): ByteArray? {
        val file = file(accountId)
        return if (file.exists()) file.readBytes() else null
    }

    fun clear(accountId: Long) {
        file(accountId).delete()
    }

    private fun file(accountId: Long) = File(dir, "$accountId.cert")
}
