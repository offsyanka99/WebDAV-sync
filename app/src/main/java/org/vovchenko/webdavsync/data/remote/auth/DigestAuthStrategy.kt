package org.vovchenko.webdavsync.data.remote.auth

import com.burgstaller.okhttp.AuthenticationCacheInterceptor
import com.burgstaller.okhttp.CachingAuthenticatorDecorator
import com.burgstaller.okhttp.digest.CachingAuthenticator
import com.burgstaller.okhttp.digest.Credentials as DigestCredentials
import com.burgstaller.okhttp.digest.DigestAuthenticator
import okhttp3.OkHttpClient
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import java.util.concurrent.ConcurrentHashMap

/** Handles the Digest challenge/response (RFC 7616) via `okhttp-digest`, with per-realm caching. */
class DigestAuthStrategy : WebDavAuthStrategy {
    override fun apply(builder: OkHttpClient.Builder, credentials: WebDavCredentials) {
        val digestCredentials = DigestCredentials(credentials.username, credentials.password)
        val authenticator = DigestAuthenticator(digestCredentials)
        val authCache = ConcurrentHashMap<String, CachingAuthenticator>()
        builder
            .authenticator(CachingAuthenticatorDecorator(authenticator, authCache))
            .addInterceptor(AuthenticationCacheInterceptor(authCache))
    }
}
