package org.vovchenko.webdavsync.data.repository

import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
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
        diagnosticLogger.i(TAG, "Add account name='$displayName' baseUrl='$baseUrl' customCert=${trustedCertificateBytes != null}")
        // Security audit finding #5: reject cleartext HTTP so Basic/Digest credentials are never
        // sent unencrypted. Custom CA trust (§4.6) is still supported for self-signed HTTPS servers.
        if (!baseUrl.startsWith("https://", ignoreCase = true)) {
            diagnosticLogger.w(TAG, "Rejected non-HTTPS baseUrl")
            return Result.failure(IllegalArgumentException("Server address must start with https://"))
        }

        val probeClient = clientFactory.createProbeClient(trustedCertificateBytes)
        val authScheme = runCatching { authSchemeDetector.detect(baseUrl, probeClient) }
            .getOrElse {
                diagnosticLogger.e(TAG, "Auth scheme detection failed for $baseUrl", it)
                return Result.failure(it)
            }
        diagnosticLogger.i(TAG, "Detected auth scheme=$authScheme")

        val client = clientFactory.create(baseUrl, authScheme, credentials, trustedCertificateBytes)
        client.testConnection().onFailure {
            diagnosticLogger.e(TAG, "Connection test failed for $baseUrl", it)
            return Result.failure(it)
        }

        val quota = client.getQuota().getOrNull()
        diagnosticLogger.i(TAG, "Quota available=${quota?.availableBytes} total=${quota?.totalBytes}")

        val account = WebDavAccountEntity(
            displayName = displayName,
            baseUrl = baseUrl,
            authScheme = authScheme,
            storageQuotaBytes = quota?.totalBytes,
            storageAvailableBytes = quota?.availableBytes,
        )
        val accountId = accountRepository.addAccount(account, credentials)
        trustedCertificateBytes?.let { accountRepository.saveTrustedCertificate(accountId, it) }
        diagnosticLogger.i(TAG, "Account saved id=$accountId")
        return Result.success(accountId)
    }

    /** Refreshes the stored quota for an existing account, used by the Overview Cloud Storage card. */
    suspend fun refreshQuota(account: WebDavAccountEntity): Result<Unit> {
        val credentials = accountRepository.getCredentials(account.id)
            ?: return Result.failure(IllegalStateException("No stored credentials for account ${account.id}"))
        val authScheme = account.authScheme
            ?: return Result.failure(IllegalStateException("Account ${account.id} has no detected auth scheme yet"))
        val trustedCert = accountRepository.getTrustedCertificate(account.id)

        val client = clientFactory.create(account.baseUrl, authScheme, credentials, trustedCert)
        val quota = client.getQuota().getOrElse {
            diagnosticLogger.w(TAG, "Quota refresh failed for account id=${account.id}: ${it.message}")
            return Result.failure(it)
        }

        accountRepository.updateAccount(
            account.copy(
                storageQuotaBytes = quota.totalBytes,
                storageAvailableBytes = quota.availableBytes,
            ),
        )
        diagnosticLogger.i(TAG, "Quota refreshed accountId=${account.id} available=${quota.availableBytes}")
        return Result.success(Unit)
    }

    private companion object {
        const val TAG = "WebDavConnection"
    }
}
