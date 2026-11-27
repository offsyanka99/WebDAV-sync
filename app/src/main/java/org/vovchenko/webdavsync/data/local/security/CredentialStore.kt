package org.vovchenko.webdavsync.data.local.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.daead.DeterministicAeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.subtle.Base64
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-account WebDAV passwords. Ciphertext lives in the private prefs file
 * [PREFS_NAME], encrypted with Tink AES-256-GCM. The Tink keyset is wrapped by a
 * key in the Android Keystore. Values are not written to Room or DataStore.
 *
 * An install that still has [LegacyCredentialCipher.FILE_NAME] is copied once.
 * The old file is removed only after every account is readable from this store.
 * A locked keystore leaves the old file in place so the next open can retry.
 */
@Singleton
class CredentialStore @Inject constructor(
    private val context: Context,
) {
    private val lock = Any()
    private var aead: Aead? = null

    fun save(accountId: Long, credentials: WebDavCredentials) {
        synchronized(lock) {
            migrateLegacy()
            val editor = prefs().edit()
                .putString(usernameKey(accountId), encrypt(usernameKey(accountId), credentials.username))
                .putString(passwordKey(accountId), encrypt(passwordKey(accountId), credentials.password))
            check(editor.commit()) { "Could not store credentials for account $accountId" }
        }
    }

    fun get(accountId: Long): WebDavCredentials? {
        synchronized(lock) {
            migrateLegacy()
            if (!prefsFile().exists()) return null
            val username = decrypt(usernameKey(accountId)) ?: return null
            val password = decrypt(passwordKey(accountId)) ?: return null
            return WebDavCredentials(username, password)
        }
    }

    fun clear(accountId: Long) {
        synchronized(lock) {
            migrateLegacy()
            if (!prefsFile().exists()) return
            prefs().edit()
                .remove(usernameKey(accountId))
                .remove(passwordKey(accountId))
                .commit()
        }
    }

    /**
     * Copies [LegacyCredentialCipher.FILE_NAME] into this store. Returns without
     * deleting that file when the keystore cannot decrypt it or a copied account
     * cannot be read back.
     */
    private fun migrateLegacy() {
        if (!legacyFile().exists()) return
        val copied = try {
            readLegacyAccounts()
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Leaving encrypted credentials in place until the keystore can open them", e)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Leaving encrypted credentials in place; legacy read failed", e)
            return
        }
        for ((accountId, credentials) in copied) {
            if (prefsFile().exists() &&
                decrypt(usernameKey(accountId)) != null &&
                decrypt(passwordKey(accountId)) != null
            ) {
                continue
            }
            val editor = prefs().edit()
                .putString(usernameKey(accountId), encrypt(usernameKey(accountId), credentials.username))
                .putString(passwordKey(accountId), encrypt(passwordKey(accountId), credentials.password))
            if (!editor.commit()) {
                Log.w(TAG, "Legacy credential copy did not commit for account $accountId")
                return
            }
        }
        val readable = copied.all { (accountId, _) ->
            decrypt(usernameKey(accountId)) != null && decrypt(passwordKey(accountId)) != null
        }
        if (!readable) return
        context.deleteSharedPreferences(LegacyCredentialCipher.FILE_NAME)
        if (legacyFile().exists() && !legacyFile().delete()) {
            Log.w(TAG, "Legacy credential prefs were copied but the file is still present")
        }
    }

    private fun readLegacyAccounts(): Map<Long, WebDavCredentials> {
        val legacy = context.getSharedPreferences(LegacyCredentialCipher.FILE_NAME, Context.MODE_PRIVATE)
        if (!legacy.contains(LegacyCredentialCipher.KEY_KEYSET) ||
            !legacy.contains(LegacyCredentialCipher.VALUE_KEYSET)
        ) {
            throw GeneralSecurityException("Legacy credential keyset is missing")
        }
        registerTink()
        val keyAead = AndroidKeysetManager.Builder()
            .withSharedPref(context, LegacyCredentialCipher.KEY_KEYSET, LegacyCredentialCipher.FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_SIV"))
            .withMasterKeyUri(LegacyCredentialCipher.MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(com.google.crypto.tink.DeterministicAead::class.java)
        val valueAead = AndroidKeysetManager.Builder()
            .withSharedPref(context, LegacyCredentialCipher.VALUE_KEYSET, LegacyCredentialCipher.FILE_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(LegacyCredentialCipher.MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
        val entries = legacy.all.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        return LegacyCredentialCipher.decryptAccounts(entries, keyAead, valueAead)
    }

    private fun prefs(): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun cipher(): Aead {
        aead?.let { return it }
        registerTink()
        val created = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_ALIAS, PREFS_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
        aead = created
        return created
    }

    private fun encrypt(key: String, value: String): String {
        val cipherText = cipher().encrypt(
            value.toByteArray(StandardCharsets.UTF_8),
            key.toByteArray(StandardCharsets.UTF_8),
        )
        return Base64.encode(cipherText)
    }

    private fun decrypt(key: String): String? {
        val encoded = prefs().getString(key, null) ?: return null
        val plain = cipher().decrypt(
            Base64.decode(encoded, Base64.DEFAULT),
            key.toByteArray(StandardCharsets.UTF_8),
        )
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun prefsFile(): File = prefsFile(PREFS_NAME)

    private fun legacyFile(): File = prefsFile(LegacyCredentialCipher.FILE_NAME)

    private fun prefsFile(name: String): File =
        File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")

    private fun usernameKey(accountId: Long) = "account_${accountId}_username"
    private fun passwordKey(accountId: Long) = "account_${accountId}_password"

    private companion object {
        const val TAG = "CredentialStore"
        const val PREFS_NAME = "webdav_credentials_v2"
        const val KEYSET_ALIAS = "aead_keyset"
        const val MASTER_KEY_URI = "android-keystore://webdavsync_credential_master_key"

        @Volatile private var tinkRegistered = false

        fun registerTink() {
            if (tinkRegistered) return
            synchronized(CredentialStore::class.java) {
                if (tinkRegistered) return
                AeadConfig.register()
                DeterministicAeadConfig.register()
                tinkRegistered = true
            }
        }
    }
}
