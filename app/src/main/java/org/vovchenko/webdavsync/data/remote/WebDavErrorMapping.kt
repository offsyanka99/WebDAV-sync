package org.vovchenko.webdavsync.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/** Maps low-level transport exceptions to [WebDavException] (plan Phase 2 "Error mapping"). */
internal fun Throwable.toWebDavException(): WebDavException = when (this) {
    is WebDavException -> this
    is SSLHandshakeException, is SSLPeerUnverifiedException -> WebDavException.CertificateUntrusted(this)
    is SSLException -> WebDavException.CertificateUntrusted(this)
    is SocketTimeoutException -> WebDavException.Timeout(this)
    is UnknownHostException, is ConnectException -> WebDavException.NetworkError(this)
    is IOException -> httpCodeFromMessage(message)?.let { code -> toServerOrAuthError(code, this) }
        ?: WebDavException.NetworkError(this)
    else -> WebDavException.Unknown(this)
}

private fun toServerOrAuthError(code: Int, cause: Throwable): WebDavException = when (code) {
    401, 403 -> WebDavException.AuthenticationFailed(cause)
    404 -> WebDavException.NotFound(cause.message?.take(200) ?: "")
    else -> WebDavException.ServerError(code, cause)
}

internal suspend fun <T> runCatchingWebDav(block: suspend () -> T): Result<T> = withContext(Dispatchers.IO) {
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e.toWebDavException())
    }
}
