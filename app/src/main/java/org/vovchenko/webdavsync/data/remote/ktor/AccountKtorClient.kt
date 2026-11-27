package org.vovchenko.webdavsync.data.remote.ktor

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.util.AttributeKey
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.Job
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/** A redirect was refused before another request was sent. */
class RedirectRefusedException(
    val status: Int,
    val location: String?,
) : IOException("Redirect refused: HTTP $status location=$location")

/**
 * dav4jvm follows 301, 302, 307, and 308 after the call returns. Turning off the engine's
 * redirect flag does not stop that loop, so a 3xx fails the call here.
 */
val RefuseRedirects = createClientPlugin("RefuseRedirects") {
    on(io.ktor.client.plugins.api.Send) { request ->
        val call = proceed(request)
        val status = call.response.status.value
        if (status in 300..399) {
            throw RedirectRefusedException(status, call.response.headers[HttpHeaders.Location])
        }
        call
    }
}

private class PushHeaderConfig {
    var holder: PushDontNotify? = null
}

/**
 * Reads [PushDontNotify] on every send, including the authenticator's retry.
 * Installed inside [Auth] so the retry's `proceed` passes through this plugin.
 */
private val PushDontNotifyHeaders = createClientPlugin("PushDontNotifyHeaders", ::PushHeaderConfig) {
    val holder = pluginConfig.holder
    on(io.ktor.client.plugins.api.Send) { request ->
        val value = holder?.current()
        val method = request.method.value.uppercase()
        if (value != null && method in PushDontNotify.MUTATING_METHODS) {
            request.headers[PushDontNotify.HEADER] = value
        } else {
            request.headers.remove(PushDontNotify.HEADER)
        }
        proceed(request)
    }
}

private class InFlightConfig {
    var registry: InFlightCallRegistry? = null
}

/** Holds the call's [Job] for the whole send, including a Digest retry. */
private val TimeoutChosen = AttributeKey<Boolean>("AccountTimeoutChosen")

/**
 * DavResource builds its own requests and does not set a timeout. GET and PUT use the body
 * window. Everything else, including PROPFIND, HEAD, MKCOL, and DELETE, uses the metadata cap.
 * [callTimeouts] marks the builder so this plugin leaves that choice alone.
 */
private val DefaultCallWindows = createClientPlugin("DefaultCallWindows") {
    onRequest { request, _ ->
        if (request.attributes.getOrNull(TimeoutChosen) == true) return@onRequest
        val method = request.method
        if (method == HttpMethod.Get || method == HttpMethod.Put) request.bodyCallTimeout()
        else request.metadataCallTimeout()
    }
}

private val TrackInFlight = createClientPlugin("TrackInFlight", ::InFlightConfig) {
    val registry = pluginConfig.registry
    on(io.ktor.client.plugins.api.Send) { request ->
        val job = coroutineContext[Job]
        if (registry != null && job != null) registry.register(job)
        try {
            proceed(request)
        } finally {
            if (registry != null && job != null) registry.unregister(job)
        }
    }
}

internal object KtorCallWindows {
    const val CONNECT_MILLIS = 30_000L
    const val METADATA_MILLIS = 60_000L
    const val BODY_SOCKET_MILLIS = 30L * 60L * 1000L
    const val BODY_CALL_MILLIS = 6L * 60L * 60L * 1000L
}

/** Metadata calls: list, MKCOL, DELETE, exists, quota, and the auth probe. */
internal fun HttpRequestBuilder.metadataCallTimeout() {
    callTimeouts(
        connectMillis = KtorCallWindows.CONNECT_MILLIS,
        socketMillis = KtorCallWindows.METADATA_MILLIS,
        requestMillis = KtorCallWindows.METADATA_MILLIS,
    )
}

/** GET and PUT. The socket window is 30 minutes. The whole call may last 6 hours. */
internal fun HttpRequestBuilder.bodyCallTimeout() {
    callTimeouts(
        connectMillis = KtorCallWindows.CONNECT_MILLIS,
        socketMillis = KtorCallWindows.BODY_SOCKET_MILLIS,
        requestMillis = KtorCallWindows.BODY_CALL_MILLIS,
    )
}

internal fun HttpRequestBuilder.callTimeouts(
    connectMillis: Long,
    socketMillis: Long,
    requestMillis: Long,
) {
    attributes.put(TimeoutChosen, true)
    timeout {
        connectTimeoutMillis = connectMillis
        socketTimeoutMillis = socketMillis
        requestTimeoutMillis = requestMillis
    }
}

/**
 * One OkHttp client for an account. [authScheme] null builds the unauthenticated probe.
 * Exactly one of Basic or Digest is installed, never both.
 *
 * Send order is outer to inner: in-flight tracking, redirect refusal, auth, then the
 * push header. Auth retries by calling `proceed` on a copy, which still runs the header plugin.
 */
internal fun buildAccountHttpClient(
    trustManager: X509TrustManager,
    authScheme: AuthScheme?,
    accountCredentials: WebDavCredentials?,
    pushDontNotify: PushDontNotify?,
    inFlight: InFlightCallRegistry?,
): HttpClient {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(trustManager), null)
    return HttpClient(OkHttp) {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout)
        install(DefaultCallWindows)
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                connectTimeout(KtorCallWindows.CONNECT_MILLIS, TimeUnit.MILLISECONDS)
                readTimeout(KtorCallWindows.BODY_SOCKET_MILLIS, TimeUnit.MILLISECONDS)
                writeTimeout(KtorCallWindows.BODY_SOCKET_MILLIS, TimeUnit.MILLISECONDS)
                callTimeout(0, TimeUnit.MILLISECONDS)
                sslSocketFactory(sslContext.socketFactory, trustManager)
            }
        }
        install(TrackInFlight) { registry = inFlight }
        install(RefuseRedirects)
        if (authScheme != null && accountCredentials != null) {
            install(Auth) {
                when (authScheme) {
                    AuthScheme.BASIC -> basic {
                        credentials {
                            BasicAuthCredentials(
                                username = accountCredentials.username,
                                password = accountCredentials.password,
                            )
                        }
                        sendWithoutRequest { true }
                    }
                    AuthScheme.DIGEST -> digest {
                        credentials {
                            DigestAuthCredentials(
                                username = accountCredentials.username,
                                password = accountCredentials.password,
                            )
                        }
                    }
                }
            }
        }
        install(PushDontNotifyHeaders) { holder = pushDontNotify }
    }
}

/** PROPFIND the probe sends. Depth 0, no body, no Authorization. */
internal val PROBE_METHOD = HttpMethod("PROPFIND")
