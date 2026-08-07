package org.vovchenko.webdavsync.data.remote.auth

import okhttp3.Credentials
import okhttp3.OkHttpClient
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials

/** Sends the `Authorization: Basic` header preemptively on every request (avoids an extra 401 round trip). */
class BasicAuthStrategy : WebDavAuthStrategy {
    override fun apply(builder: OkHttpClient.Builder, credentials: WebDavCredentials) {
        val header = Credentials.basic(credentials.username, credentials.password)
        builder.addInterceptor { chain ->
            val request = chain.request().newBuilder()
                .header("Authorization", header)
                .build()
            chain.proceed(request)
        }
    }
}
