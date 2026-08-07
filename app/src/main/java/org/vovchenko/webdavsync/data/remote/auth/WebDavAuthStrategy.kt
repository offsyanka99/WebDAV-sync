package org.vovchenko.webdavsync.data.remote.auth

import okhttp3.OkHttpClient
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials

/**
 * Applies a WebDAV auth scheme to an OkHttp client builder (plan §4.4).
 * Basic/Digest are the initial implementations; Bearer/OAuth2/mTLS can be added later as new
 * implementations without touching [org.vovchenko.webdavsync.data.remote.WebDavClient].
 */
interface WebDavAuthStrategy {
    fun apply(builder: OkHttpClient.Builder, credentials: WebDavCredentials)
}
