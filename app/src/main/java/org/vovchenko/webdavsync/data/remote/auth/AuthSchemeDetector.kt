package org.vovchenko.webdavsync.data.remote.auth

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.ktor.PROBE_METHOD
import org.vovchenko.webdavsync.data.remote.ktor.metadataCallTimeout
import javax.inject.Inject

/**
 * Probes the server once (unauthenticated) and reads `WWW-Authenticate` on the resulting 401 to
 * pick Basic or Digest automatically (plan §4.4). Prefers Digest when both are offered, since it
 * doesn't send the password in the clear.
 *
 * The client must not have an authenticator. A probe that sends Basic stores the wrong scheme.
 */
class AuthSchemeDetector @Inject constructor() {

    suspend fun detect(baseUrl: String, client: HttpClient): AuthScheme = withContext(Dispatchers.IO) {
        client.prepareRequest(baseUrl) {
            method = PROBE_METHOD
            header("Depth", "0")
            metadataCallTimeout()
        }.execute { response ->
            if (response.status.value != 401) {
                // Server didn't challenge at all (e.g. already trusts anonymous, or misconfigured) —
                // fall back to Basic, the safer/simpler default.
                return@execute AuthScheme.BASIC
            }
            val challenges = response.headers.getAll(HttpHeaders.WWWAuthenticate).orEmpty()
            val hasDigest = challenges.any { it.startsWith("Digest", ignoreCase = true) }
            if (hasDigest) AuthScheme.DIGEST else AuthScheme.BASIC
        }
    }
}
