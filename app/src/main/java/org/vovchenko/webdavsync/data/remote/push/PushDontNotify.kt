package org.vovchenko.webdavsync.data.remote.push

import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-client `Push-Dont-Notify` value (draft §4.4). Installed as an OkHttp application
 * interceptor, so authenticator retries (Digest 401) rebuild from a request that already carries it.
 */
class PushDontNotify {
    private val header = AtomicReference<String?>(null)

    /** Sets the registration URLs to suppress; invalid URLs are dropped and an empty list clears it. */
    fun set(registrationUrls: List<String>) {
        header.set(format(registrationUrls))
    }

    fun current(): String? = header.get()

    val interceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val value = header.get()
        if (value == null || request.method.uppercase() !in MUTATING_METHODS) {
            chain.proceed(request)
        } else {
            chain.proceed(request.newBuilder().header(HEADER, value).build())
        }
    }

    companion object {
        const val HEADER = "Push-Dont-Notify"
        val MUTATING_METHODS = setOf("PUT", "MKCOL", "DELETE", "MOVE", "COPY", "PROPPATCH")
        /** AngaraDAV reads at most 100 URLs from the header. */
        private const val MAX_URLS = 100

        internal fun format(registrationUrls: List<String>): String? =
            registrationUrls
                .filter(PushHeaders::isValidRegistrationUrl)
                .distinct()
                .take(MAX_URLS)
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", ") { "\"$it\"" }
    }
}
