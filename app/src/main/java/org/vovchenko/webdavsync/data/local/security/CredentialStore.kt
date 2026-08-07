package org.vovchenko.webdavsync.data.local.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypted, per-account credential storage (plan §4.6). Never store WebDAV
 * username/password in Room or DataStore — use this instead.
 */
@Singleton
class CredentialStore @Inject constructor(
    context: Context,
) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun save(accountId: Long, credentials: WebDavCredentials) {
        prefs.edit()
            .putString(usernameKey(accountId), credentials.username)
            .putString(passwordKey(accountId), credentials.password)
            .apply()
    }

    fun get(accountId: Long): WebDavCredentials? {
        val username = prefs.getString(usernameKey(accountId), null) ?: return null
        val password = prefs.getString(passwordKey(accountId), null) ?: return null
        return WebDavCredentials(username, password)
    }

    fun clear(accountId: Long) {
        prefs.edit()
            .remove(usernameKey(accountId))
            .remove(passwordKey(accountId))
            .apply()
    }

    private fun usernameKey(accountId: Long) = "account_${accountId}_username"
    private fun passwordKey(accountId: Long) = "account_${accountId}_password"

    private companion object {
        const val PREFS_NAME = "webdav_credentials"
    }
}
