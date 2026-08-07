package org.vovchenko.webdavsync.data.remote.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vovchenko.webdavsync.data.model.AuthScheme
import javax.inject.Inject

/**
 * Probes the server once (unauthenticated) and reads `WWW-Authenticate` on the resulting 401 to
 * pick Basic or Digest automatically (plan §4.4). Prefers Digest when both are offered, since it
 * doesn't send the password in the clear.
 */
class AuthSchemeDetector @Inject constructor() {

    suspend fun detect(baseUrl: String, okHttpClient: OkHttpClient): AuthScheme = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl)
            .method("PROPFIND", null)
            .header("Depth", "0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.code != 401) {
                // Server didn't challenge at all (e.g. already trusts anonymous, or misconfigured) —
                // fall back to Basic, the safer/simpler default.
                return@withContext AuthScheme.BASIC
            }
            val challenges = response.headers("WWW-Authenticate")
            val hasDigest = challenges.any { it.startsWith("Digest", ignoreCase = true) }
            if (hasDigest) AuthScheme.DIGEST else AuthScheme.BASIC
        }
    }
}
