package org.vovchenko.webdavsync.push

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.vovchenko.webdavsync.data.remote.WebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavClientSession
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Account access for push upkeep; a test seam over credential storage. */
interface PushAccountClients {
    suspend fun displayName(accountId: Long): String?

    /** The account's client inside [session]; null when it cannot sign in. */
    suspend fun clientFor(session: WebDavClientSession, accountId: Long): WebDavClient?
}

@Singleton
class AccountPushClients @Inject constructor(
    private val accountRepository: WebDavAccountRepository,
) : PushAccountClients {
    override suspend fun displayName(accountId: Long): String? =
        accountRepository.observeById(accountId).first()?.displayName

    override suspend fun clientFor(session: WebDavClientSession, accountId: Long): WebDavClient? {
        val account = accountRepository.observeById(accountId).first() ?: return null
        val authScheme = account.authScheme ?: return null
        val credentials = withContext(Dispatchers.IO) { accountRepository.getCredentials(accountId) } ?: return null
        val trustedCert = accountRepository.getTrustedCertificate(accountId)
        return session.clientFor(accountId, account.baseUrl, authScheme, credentials, trustedCert)
    }
}
