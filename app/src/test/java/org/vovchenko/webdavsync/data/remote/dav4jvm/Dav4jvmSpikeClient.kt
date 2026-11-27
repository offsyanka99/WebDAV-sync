package org.vovchenko.webdavsync.data.remote.dav4jvm

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import org.vovchenko.webdavsync.data.remote.ktor.RefuseRedirects
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Same OkHttp engine as the account client, with the redirect rule installed.
 */
internal fun dav4jvmSpikeClient(
    trustManager: X509TrustManager? = null,
    configure: HttpClientConfig<*>.() -> Unit = {},
): HttpClient = HttpClient(OkHttp) {
    followRedirects = false
    install(RefuseRedirects)
    configure()
    engine {
        config {
            followRedirects(false)
            followSslRedirects(false)
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(60, TimeUnit.SECONDS)
            writeTimeout(60, TimeUnit.SECONDS)
            callTimeout(0, TimeUnit.MILLISECONDS)
            if (trustManager != null) {
                val context = SSLContext.getInstance("TLS")
                context.init(null, arrayOf(trustManager), null)
                sslSocketFactory(context.socketFactory, trustManager)
            }
        }
    }
}
