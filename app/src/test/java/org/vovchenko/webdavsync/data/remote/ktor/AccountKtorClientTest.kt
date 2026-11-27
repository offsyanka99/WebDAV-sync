package org.vovchenko.webdavsync.data.remote.ktor

import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.remote.auth.AuthSchemeDetector
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class AccountKtorClientTest {

    private val alice = WebDavCredentials("alice", "secret")
    private val digestChallenge =
        """Digest realm="test", nonce="abc123", qop="auth", algorithm=MD5"""

    @Test(timeout = 20_000)
    fun `probe is unauthenticated and prefers Digest`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val registry = InFlightCallRegistry()
        val trust = TrustedCertTrustManagerFactory.build(null)
        val client = buildAccountHttpClient(trust.trustManager, null, null, null, registry)
        try {
            server.enqueue(MockResponse.Builder().code(401).setHeader("WWW-Authenticate", digestChallenge).build())
            assertEquals(
                AuthScheme.DIGEST,
                AuthSchemeDetector().detect(server.url("/dav/").toString(), client),
            )
            val probe = server.takeRequest()
            assertEquals("PROPFIND", probe.method)
            assertEquals("0", probe.headers["Depth"])
            assertNull(probe.headers["Authorization"])

            server.enqueue(MockResponse.Builder().code(401).setHeader("WWW-Authenticate", "Basic realm=\"test\"").build())
            assertEquals(AuthScheme.BASIC, AuthSchemeDetector().detect(server.url("/dav/").toString(), client))

            server.enqueue(MockResponse.Builder().code(204).build())
            assertEquals(AuthScheme.BASIC, AuthSchemeDetector().detect(server.url("/dav/").toString(), client))
        } finally {
            client.close()
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `Digest header uses unquoted qop auth and algorithm MD5`() = runBlocking {
        val ktorHeader = ktorDigestHeader(null)
        val ktor = digestParams(ktorHeader)
        assertEquals("alice", ktor["username"])
        assertEquals("test", ktor["realm"])
        assertEquals("abc123", ktor["nonce"])
        assertTrue(ktor["uri"]!!.endsWith("/dav/a.txt"))
        assertEquals("auth", ktor["qop"])
        assertEquals("MD5", ktor["algorithm"])
        assertTrue(ktor["nc"]!!.matches(Regex("[0-9a-f]{8}")))
        assertTrue(ktorHeader.startsWith("Digest "))
        assertFalse(ktorHeader.contains("qop=\""))
        assertFalse(ktorHeader.contains("algorithm=\""))
    }

    @Test(timeout = 20_000)
    fun `push header is on both Digest attempts and not on PROPFIND`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val push = PushDontNotify()
        val url = "https://dav.example.com/dav.php/push-subscriptions/" + "A".repeat(43)
        push.set(listOf(url))
        val trust = TrustedCertTrustManagerFactory.build(null)
        val client = buildAccountHttpClient(trust.trustManager, AuthScheme.DIGEST, alice, push, null)
        try {
            server.enqueue(MockResponse.Builder().code(401).setHeader("WWW-Authenticate", digestChallenge).build())
            server.enqueue(MockResponse.Builder().code(201).build())
            client.prepareRequest(server.url("/dav/a.txt").toString()) {
                method = HttpMethod.Put
                setBody("photo")
                bodyCallTimeout()
            }.execute { response ->
                assertEquals(201, response.status.value)
            }
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("\"$url\"", first.headers[PushDontNotify.HEADER])
            assertEquals("\"$url\"", second.headers[PushDontNotify.HEADER])
            assertTrue(second.headers["Authorization"].orEmpty().startsWith("Digest "))
            assertFalse(second.headers["Authorization"].orEmpty().startsWith("Basic "))

            server.enqueue(MockResponse.Builder().code(207).body("<multistatus/>").build())
            client.prepareRequest(server.url("/dav/").toString()) {
                method = PROBE_METHOD
                header("Depth", "0")
                metadataCallTimeout()
            }.execute { }
            assertNull(server.takeRequest().headers[PushDontNotify.HEADER])
        } finally {
            client.close()
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `redirect to another host is not followed`() = runBlocking {
        assertRedirectRefused("http://evil.example/steal")
    }

    @Test(timeout = 20_000)
    fun `same host redirect is not followed`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            assertRedirectRefused(server.url("/elsewhere").toString(), server)
        } finally {
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `metadata timeout fires and a body call is still waiting`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val trust = TrustedCertTrustManagerFactory.build(null)
        val client = buildAccountHttpClient(trust.trustManager, null, null, null, null)
        try {
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
            val started = System.nanoTime()
            val failed = runCatching {
                client.prepareRequest(server.url("/dav/").toString()) {
                    method = HttpMethod.Get
                    callTimeouts(1_000, 800, 800)
                }.execute { }
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            assertTrue(failed.exceptionOrNull()?.toString(), failed.isFailure)
            assertTrue("metadata call took ${elapsedMs}ms", elapsedMs < 3_000)

            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
            val bodyStillWaiting = runCatching {
                withTimeout(1_200) {
                    client.prepareRequest(server.url("/dav/file").toString()) {
                        method = HttpMethod.Get
                        callTimeouts(1_000, 10_000, 10_000)
                    }.execute { }
                }
            }
            assertTrue(bodyStillWaiting.isFailure)
            assertTrue(bodyStillWaiting.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `cancelAll aborts a hung call`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val registry = InFlightCallRegistry()
        val trust = TrustedCertTrustManagerFactory.build(null)
        val client = buildAccountHttpClient(trust.trustManager, null, null, null, registry)
        try {
            server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
            val call = launch {
                client.prepareRequest(server.url("/dav/hang").toString()) {
                    method = HttpMethod.Get
                    callTimeouts(5_000, 30_000, 30_000)
                }.execute { }
            }
            delay(300)
            registry.cancelAll()
            val finished = withTimeout(3_000) { runCatching { call.join() } }
            assertTrue(call.isCompleted)
            assertTrue(finished.isSuccess)
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `ktor shutdown runs off the caller thread`() {
        val caller = Thread.currentThread()
        val closer = AtomicReference<Thread>()
        val started = CountDownLatch(1)
        WebDavClientFactory.enqueueClose {
            closer.set(Thread.currentThread())
            started.countDown()
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals("webdav-client-close", closer.get().name)
        assertNotEquals(caller, closer.get())
    }

    @Test(timeout = 20_000)
    fun `imported leaf is trusted and a different leaf is not`() = runBlocking {
        val trusted = material("dav4jvm/localhost.cer", "dav4jvm/localhost.pk8")
        val other = material("dav4jvm/other.cer", "dav4jvm/other.pk8")
        val trust = TrustedCertTrustManagerFactory.build(trusted.certificate.encoded)
        val server = httpsServer(trusted)
        val client = buildAccountHttpClient(trust.trustManager, null, null, null, null)
        try {
            server.enqueue(MockResponse.Builder().code(204).build())
            client.prepareRequest(server.url("/dav/").toString()) {
                method = HttpMethod.Get
                metadataCallTimeout()
            }.execute { response ->
                assertEquals(204, response.status.value)
            }
        } finally {
            client.close()
            server.close()
        }
        val untrusted = httpsServer(other)
        val again = buildAccountHttpClient(trust.trustManager, null, null, null, null)
        try {
            val failed = runCatching {
                again.prepareRequest(untrusted.url("/dav/").toString()) {
                    method = HttpMethod.Get
                    metadataCallTimeout()
                }.execute { }
            }
            assertTrue(failed.exceptionOrNull()?.toString(), failed.isFailure)
            assertEquals(0, untrusted.requestCount)
        } finally {
            again.close()
            untrusted.close()
        }
    }

    private suspend fun ktorDigestHeader(push: PushDontNotify?): String {
        val server = MockWebServer()
        server.start()
        val trust = TrustedCertTrustManagerFactory.build(null)
        val client = buildAccountHttpClient(trust.trustManager, AuthScheme.DIGEST, alice, push, null)
        try {
            server.enqueue(MockResponse.Builder().code(401).setHeader("WWW-Authenticate", digestChallenge).build())
            server.enqueue(MockResponse.Builder().code(204).build())
            client.prepareRequest(server.url("/dav/a.txt").toString()) {
                method = HttpMethod.Put
                setBody("x")
                bodyCallTimeout()
            }.execute { }
            server.takeRequest()
            return server.takeRequest().headers["Authorization"]!!
        } finally {
            client.close()
            server.close()
        }
    }

    private suspend fun assertRedirectRefused(location: String, existing: MockWebServer? = null) {
        val server = existing ?: MockWebServer().also { it.start() }
        val trust = TrustedCertTrustManagerFactory.build(null)
        val client = buildAccountHttpClient(trust.trustManager, AuthScheme.BASIC, alice, null, null)
        try {
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", location).build())
            val failed = runCatching {
                client.prepareRequest(server.url("/dav/").toString()) {
                    method = HttpMethod.Get
                    metadataCallTimeout()
                }.execute { response ->
                    error("saw HTTP ${response.status}")
                }
            }
            val refused = failed.exceptionOrNull()
            assertTrue(refused.toString(), refused is RedirectRefusedException)
            refused as RedirectRefusedException
            assertEquals(302, refused.status)
            assertEquals(location, refused.location)
            assertEquals(1, server.requestCount)
            val sent = server.takeRequest()
            assertTrue(sent.headers["Authorization"].orEmpty().startsWith("Basic "))
        } finally {
            client.close()
            if (existing == null) server.close()
        }
    }

    private fun digestParams(header: String): Map<String, String> {
        val body = header.removePrefix("Digest ").trim()
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        for (ch in body) {
            if (ch == '"') quoted = !quoted
            if (ch == ',' && !quoted) {
                parts += current.toString().trim()
                current.clear()
            } else {
                current.append(ch)
            }
        }
        if (current.isNotBlank()) parts += current.toString().trim()
        return parts.associate { part ->
            val eq = part.indexOf('=')
            part.substring(0, eq).trim() to part.substring(eq + 1).trim().removeSurrounding("\"")
        }
    }

    private fun httpsServer(material: ServerMaterial): MockWebServer {
        val server = MockWebServer()
        server.useHttps(material.sslContext.socketFactory)
        server.start()
        return server
    }

    private fun material(certResource: String, keyResource: String): ServerMaterial {
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(bytes(certResource).inputStream()) as X509Certificate
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes(keyResource)))
        val store = KeyStore.getInstance(KeyStore.getDefaultType())
        store.load(null, null)
        store.setKeyEntry("server", key, CharArray(0), arrayOf(certificate))
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, CharArray(0))
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, null, null)
        return ServerMaterial(certificate, context)
    }

    private fun bytes(resource: String): ByteArray {
        val stream: InputStream = checkNotNull(javaClass.classLoader!!.getResourceAsStream(resource))
        return stream.use { it.readBytes() }
    }

    private class ServerMaterial(
        val certificate: X509Certificate,
        val sslContext: SSLContext,
    )
}
