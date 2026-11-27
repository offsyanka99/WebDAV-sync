package org.vovchenko.webdavsync.data.local.security

import com.google.crypto.tink.Aead
import com.google.crypto.tink.DeterministicAead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.subtle.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

class LegacyCredentialCipherTest {

    private lateinit var keyAead: DeterministicAead
    private lateinit var valueAead: Aead

    @Before
    fun setUp() {
        AeadConfig.register()
        DeterministicAeadConfig.register()
        keyAead = KeysetHandle.generateNew(KeyTemplates.get("AES256_SIV"))
            .getPrimitive(DeterministicAead::class.java)
        valueAead = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM"))
            .getPrimitive(Aead::class.java)
    }

    @Test
    fun `reads username and password sealed the way security-crypto did`() {
        val entries = linkedMapOf(
            LegacyCredentialCipher.KEY_KEYSET to "keyset-not-a-credential",
            LegacyCredentialCipher.VALUE_KEYSET to "value-keyset-not-a-credential",
            sealKey("account_7_username") to sealString("account_7_username", "ada"),
            sealKey("account_7_password") to sealString("account_7_password", "s3cret"),
            sealKey("other") to sealString("other", "ignore"),
        )

        val accounts = LegacyCredentialCipher.decryptAccounts(entries, keyAead, valueAead)

        assertEquals(mapOf(7L to WebDavCredentials("ada", "s3cret")), accounts)
    }

    @Test
    fun `an account missing one field is not returned`() {
        val entries = mapOf(
            sealKey("account_3_username") to sealString("account_3_username", "ada"),
        )

        assertTrue(LegacyCredentialCipher.decryptAccounts(entries, keyAead, valueAead).isEmpty())
    }

    /** Same layout as EncryptedSharedPreferences.encryptKey: SIV over the file name. */
    private fun sealKey(plainKey: String): String {
        val cipher = keyAead.encryptDeterministically(
            plainKey.toByteArray(StandardCharsets.UTF_8),
            LegacyCredentialCipher.FILE_NAME.toByteArray(StandardCharsets.UTF_8),
        )
        return Base64.encode(cipher)
    }

    /** Same layout as EncryptedSharedPreferences.putString: type 0, length, UTF-8, GCM over the encrypted key. */
    private fun sealString(plainKey: String, value: String): String {
        val encryptedKey = sealKey(plainKey)
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteBuffer.allocate(8 + utf8.size)
            .putInt(0)
            .putInt(utf8.size)
            .put(utf8)
            .array()
        val cipher = valueAead.encrypt(payload, encryptedKey.toByteArray(StandardCharsets.UTF_8))
        return Base64.encode(cipher)
    }
}
