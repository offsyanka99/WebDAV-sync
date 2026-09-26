package org.vovchenko.webdavsync.data.repository

import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.remote.WebDavClientSession
import org.vovchenko.webdavsync.domain.sync.BaseUrlNormalizer
import org.vovchenko.webdavsync.data.remote.auth.AuthSchemeDetector
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates "Add account" / "test connection" / quota refresh: auth-scheme auto-detection
 * (§4.4), connecting through [WebDavClientFactory], and persisting via [WebDavAccountRepository].
 */
@Singleton
class WebDavConnectionRepository @Inject constructor(
    private val accountRepository: WebDavAccountRepository,
    private val clientFactory: WebDavClientFactory,
    private val authSchemeDetector: AuthSchemeDetector,
    private val diagnosticLogger: DiagnosticLogger,
) {
    /**
     * Detects the auth scheme, verifies the connection, and — on success — saves the account,
     * its credentials, its trusted certificate (if any), and its initial quota.
     */
    suspend fun addAccount(
        displayName: String,
        baseUrl: String,
        credentials: WebDavCredentials,
        trustedCertificateBytes: ByteArray?,
    ): Result<Long> {
        val normalizedUrl = runCatching { BaseUrlNormalizer.normalize(baseUrl) }.getOrElse {
            diagnosticLogger.w(TAG, "Rejected baseUrl: ${it.message}")
            return Result.failure(it)
        }
        diagnosticLogger.i(TAG, "Add account name='$displayName' baseUrl='$normalizedUrl' customCert=${trustedCertificateBytes != null}")

        val probeClient = clientFactory.createProbeClient(trustedCertificateBytes)
        try {
            val authScheme = runCatching { authSchemeDetector.detect(normalizedUrl, probeClient) }
                .getOrElse {
                    diagnosticLogger.e(TAG, "Auth scheme detection failed for $normalizedUrl", it)
                    return Result.failure(it)
                }
            diagnosticLogger.i(TAG, "Detected auth scheme=$authScheme")

            val client = clientFactory.create(normalizedUrl, authScheme, credentials, trustedCertificateBytes)
            try {
                client.testConnection().onFailure {
                    diagnosticLogger.e(TAG, "Connection test failed for $normalizedUrl", it)
                    return Result.failure(it)
                }

                val quota = client.getQuota().getOrNull()
                diagnosticLogger.i(TAG, "Quota available=${quota?.availableBytes} total=${quota?.totalBytes}")

                val account = WebDavAccountEntity(
                    displayName = displayName,
                    baseUrl = normalizedUrl,
                    authScheme = authScheme,
                    storageQuotaBytes = quota?.totalBytes,
                    storageAvailableBytes = quota?.availableBytes,
                )
                val accountId = accountRepository.addAccount(account, credentials)
                trustedCertificateBytes?.let { accountRepository.saveTrustedCertificate(accountId, it) }
                diagnosticLogger.i(TAG, "Account saved id=$accountId")
                return Result.success(accountId)
            } finally {
                client.close()
            }
        } finally {
            WebDavClientFactory.shutdown(probeClient)
        }
    }

    /** Refreshes the stored quota for an existing account, used by the Overview Cloud Storage card. */
    suspend fun refreshQuota(
        account: WebDavAccountEntity,
        session: WebDavClientSession? = null,
    ): Result<Unit> {
        val credentials = accountRepository.getCredentials(account.id)
            ?: return Result.failure(IllegalStateException("No stored credentials for account ${account.id}"))
        val authScheme = account.authScheme
            ?: return Result.failure(IllegalStateException("Account ${account.id} has no detected auth scheme yet"))
        val trustedCert = accountRepository.getTrustedCertificate(account.id)

        val owned = session == null
        val client = session?.clientFor(account.id, account.baseUrl, authScheme, credentials, trustedCert)
            ?: clientFactory.create(account.baseUrl, authScheme, credentials, trustedCert)
        if (owned) {
            return try {
                refreshQuotaWith(account, client)
            } finally {
                client.close()
            }
        }
        return refreshQuotaWith(account, client)
    }

    private suspend fun refreshQuotaWith(
        account: WebDavAccountEntity,
        client: org.vovchenko.webdavsync.data.remote.WebDavClient,
    ): Result<Unit> {
        val quota = client.getQuota().getOrElse {
            diagnosticLogger.w(TAG, "Quota refresh failed for account id=${account.id}: ${it.message}")
            return Result.failure(it)
        }

        // Keep previous values if the server omits RFC 4331 quota properties (common on some
        // dav.php / limited WebDAV stacks) so the UI does not flip to "—" after a successful sync.
        val available = quota.availableBytes ?: account.storageAvailableBytes
        val total = quota.totalBytes ?: account.storageQuotaBytes
        accountRepository.updateAccount(
            account.copy(
                storageQuotaBytes = total,
                storageAvailableBytes = available,
            ),
        )
        diagnosticLogger.i(
            TAG,
            "Quota refreshed accountId=${account.id} available=${quota.availableBytes} " +
                "used=${quota.usedBytes} total=${quota.totalBytes} " +
                "(stored available=$available total=$total)",
        )
        return Result.success(Unit)
    }

    private companion object {
        const val TAG = "WebDavConnection"
    }
}
