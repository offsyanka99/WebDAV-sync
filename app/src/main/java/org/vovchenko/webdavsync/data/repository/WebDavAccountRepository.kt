package org.vovchenko.webdavsync.data.repository

import kotlinx.coroutines.flow.Flow
import org.vovchenko.webdavsync.data.local.WebDavAccountDao
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.security.CredentialStore
import org.vovchenko.webdavsync.data.local.security.TrustedCertStore
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import javax.inject.Inject
import javax.inject.Singleton

/** Aggregates the account DB row with its (encrypted) credentials and trusted cert (plan §4.5, §4.6). */
@Singleton
class WebDavAccountRepository @Inject constructor(
    private val dao: WebDavAccountDao,
    private val credentialStore: CredentialStore,
    private val trustedCertStore: TrustedCertStore,
) {
    fun observeAll(): Flow<List<WebDavAccountEntity>> = dao.observeAll()

    fun observeById(id: Long): Flow<WebDavAccountEntity?> = dao.observeById(id)

    suspend fun addAccount(account: WebDavAccountEntity, credentials: WebDavCredentials): Long {
        val id = dao.insert(account)
        credentialStore.save(id, credentials)
        return id
    }

    suspend fun updateAccount(account: WebDavAccountEntity, credentials: WebDavCredentials? = null) {
        dao.update(account)
        if (credentials != null) {
            credentialStore.save(account.id, credentials)
        }
    }

    fun getCredentials(accountId: Long): WebDavCredentials? = credentialStore.get(accountId)

    fun saveTrustedCertificate(accountId: Long, certificateBytes: ByteArray) {
        trustedCertStore.save(accountId, certificateBytes)
    }

    fun getTrustedCertificate(accountId: Long): ByteArray? = trustedCertStore.get(accountId)

    /** Number of folder pairs currently attached to this account — used for the delete-confirmation flow (plan §4.5). */
    suspend fun folderPairCount(accountId: Long): Int = dao.folderPairCountForAccount(accountId)

    /**
     * Deletes the account row (cascades to its folder pairs at the DB level) plus its credentials
     * and trusted certificate. Callers are responsible for the double-confirmation UX and for
     * deciding whether to also delete the underlying files — that's a Phase 4/7 concern.
     */
    suspend fun deleteAccount(account: WebDavAccountEntity) {
        dao.delete(account)
        credentialStore.clear(account.id)
        trustedCertStore.clear(account.id)
    }
}
