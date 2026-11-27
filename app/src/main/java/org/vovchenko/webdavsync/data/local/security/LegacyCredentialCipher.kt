package org.vovchenko.webdavsync.data.local.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.subtle.Base64
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Reads the EncryptedSharedPreferences file `webdav_credentials` written by
 * `androidx.security:security-crypto:1.1.0-alpha06`.
 *
 * Keys are AES-256-SIV with the file name as associated data. Values are AES-256-GCM
 * with the encrypted key string as associated data. A string value is a big-endian
 * type id `0`, a big-endian byte length, then UTF-8. This matches that library's
 * `encryptKey` and `putString` byte layout, so the security-crypto dependency is not
 * required to open an existing file.
 */
internal object LegacyCredentialCipher {
    const val FILE_NAME = "webdav_credentials"
    const val KEY_KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"
    const val VALUE_KEYSET = "__androidx_security_crypto_encrypted_prefs_value_keyset__"
    const val MASTER_KEY_URI = "android-keystore://_androidx_security_master_key_"

    private const val NULL_VALUE = "__NULL__"
    private const val STRING_TYPE = 0
    private val KEY_PATTERN = Regex("""account_(\d+)_(username|password)""")

    fun decryptAccounts(
        entries: Map<String, String>,
        keyAead: DeterministicAead,
        valueAead: Aead,
    ): Map<Long, WebDavCredentials> {
        val usernames = HashMap<Long, String>()
        val passwords = HashMap<Long, String>()
        for ((encryptedKey, encryptedValue) in entries) {
            if (encryptedKey == KEY_KEYSET || encryptedKey == VALUE_KEYSET) continue
            val key = decryptKey(encryptedKey, keyAead) ?: continue
            val match = KEY_PATTERN.matchEntire(key) ?: continue
            val accountId = match.groupValues[1].toLong()
            val plain = decryptString(encryptedKey, encryptedValue, valueAead)
            when (match.groupValues[2]) {
                "username" -> usernames[accountId] = plain
                "password" -> passwords[accountId] = plain
            }
        }
        val ids = usernames.keys.intersect(passwords.keys)
        return ids.associateWith { id -> WebDavCredentials(usernames.getValue(id), passwords.getValue(id)) }
    }

    private fun decryptKey(encryptedKey: String, keyAead: DeterministicAead): String? {
        val plain = keyAead.decryptDeterministically(
            Base64.decode(encryptedKey, Base64.DEFAULT),
            FILE_NAME.toByteArray(StandardCharsets.UTF_8),
        )
        val key = String(plain, StandardCharsets.UTF_8)
        return if (key == NULL_VALUE) null else key
    }

    private fun decryptString(encryptedKey: String, encryptedValue: String, valueAead: Aead): String {
        val plain = valueAead.decrypt(
            Base64.decode(encryptedValue, Base64.DEFAULT),
            encryptedKey.toByteArray(StandardCharsets.UTF_8),
        )
        val buffer = ByteBuffer.wrap(plain)
        val type = buffer.int
        val length = buffer.int
        check(type == STRING_TYPE) { "Unexpected credential value type $type" }
        check(length >= 0 && buffer.remaining() >= length) { "Credential value length is out of range" }
        val bytes = ByteArray(length)
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }
}
