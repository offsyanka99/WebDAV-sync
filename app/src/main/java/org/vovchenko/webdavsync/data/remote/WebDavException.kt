package org.vovchenko.webdavsync.data.remote

/** Domain-level errors surfaced by [WebDavClient] (plan Phase 2 "Error mapping"). */
sealed class WebDavException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthenticationFailed(cause: Throwable? = null) :
        WebDavException("Authentication failed — check username/password", cause)

    class CertificateUntrusted(cause: Throwable? = null) :
        WebDavException("Server certificate is not trusted", cause)

    class NotFound(path: String) : WebDavException("Not found: $path")

    class ServerError(val httpCode: Int, cause: Throwable? = null) :
        WebDavException("Server returned HTTP $httpCode", cause)

    class Timeout(cause: Throwable? = null) : WebDavException("Connection timed out", cause)

    class NetworkError(cause: Throwable? = null) : WebDavException("Network error", cause)

    class Unknown(cause: Throwable) : WebDavException(cause.message ?: "Unknown error", cause)
}
