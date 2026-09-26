package org.vovchenko.webdavsync.domain.sync

import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 of a stream, hex-encoded. Used when the server does not send an ETag. */
object ContentHash {
    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val DEFAULT_BUFFER = 8192
}
