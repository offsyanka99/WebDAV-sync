package org.vovchenko.webdavsync.data.remote.dav4jvm

import at.bitfire.dav4jvm.ktor.DavResource
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.DigestAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.auth.providers.digest
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.remote.ktor.RedirectRefusedException
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory
import java.io.InputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.jar.JarFile
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Engine regression for the account client: redirects stay refused and a Digest PUT
 * resends the same body.
 */
class Dav4jvmEngineSpikeTest {

    @Test
    fun `dav4jvm 5_0_0 class files are Java 21`() {
        val (major, offender) = dav4jvmClassVersion()
        // Java 21 is major 65. This app's unit-test JVM is 17 (major 61). Loading the
        // jar throws UnsupportedClassVersionError, which is the PR 1 stop rule.
        assertEquals("$offender", 65, major)
    }

    @Test(timeout = 20_000)
    fun `redirect to another host is not followed and carries no second request`() = runBlocking {
        assumeJvmCanLoadDav4jvm()
        val server = MockWebServer()
        server.start()
        try {
            val client = dav4jvmSpikeClient {
                install(Auth) {
                    basic {
                        credentials { BasicAuthCredentials(username = "alice", password = "secret") }
                        sendWithoutRequest { true }
                    }
                }
            }
            server.enqueue(
                MockResponse.Builder()
            .code(302)
            .setHeader("Location", "http://evil.example/steal")
            .build(),
            )
            try {
                client.use { http ->
                    DavResource(http, Url(server.url("/dav/").toString())).get { response ->
                        fail("DavResource saw HTTP ${response.status}")
                    }
                }
                fail("redirect was accepted")
            } catch (refused: RedirectRefusedException) {
                assertEquals(302, refused.status)
                assertEquals("http://evil.example/steal", refused.location)
            }
            assertEquals(1, server.requestCount)
            val sent = server.takeRequest()
            assertTrue(sent.headers["Authorization"].orEmpty().startsWith("Basic "))
        } finally {
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `same host redirect is not followed`() = runBlocking {
        assumeJvmCanLoadDav4jvm()
        val server = MockWebServer()
        server.start()
        try {
            val client = dav4jvmSpikeClient()
            val elsewhere = server.url("/elsewhere").toString()
            server.enqueue(MockResponse.Builder().code(302).setHeader("Location", elsewhere).build())
            try {
                client.use { http ->
                    DavResource(http, Url(server.url("/dav/").toString())).get { response ->
                        fail("DavResource saw HTTP ${response.status}")
                    }
                }
                fail("redirect was accepted")
            } catch (refused: RedirectRefusedException) {
                assertEquals(302, refused.status)
                assertEquals(elsewhere, refused.location)
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `PUT body is reopened with the same bytes after a Digest 401`() = runBlocking {
        assumeJvmCanLoadDav4jvm()
        val server = MockWebServer()
        server.start()
        val payload = "photo-bytes-v1".toByteArray()
        var opens = 0
        val body = object : OutgoingContent.ReadChannelContent() {
            override val contentLength: Long = payload.size.toLong()
            override fun readFrom(): ByteReadChannel {
                opens += 1
                return ByteReadChannel(payload)
            }
        }
        try {
            val client = dav4jvmSpikeClient {
                install(Auth) {
                    digest {
                        credentials { DigestAuthCredentials(username = "alice", password = "secret") }
                        realm = "test"
                    }
                }
            }
            server.enqueue(
                MockResponse.Builder()
            .code(401)
            .setHeader(
                    "WWW-Authenticate",
                    """Digest realm="test", nonce="abc123", qop="auth", algorithm=MD5""",
                )
            .build(),
            )
            server.enqueue(MockResponse.Builder().code(201).setHeader("ETag", "\"v1\"").build())
            client.use { http ->
                DavResource(http, Url(server.url("/dav/a.txt").toString())).put(body) { response ->
                    assertEquals(201, response.status.value)
                }
            }
            assertEquals(2, opens)
            assertEquals(2, server.requestCount)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertArrayEquals(payload, first.body!!.toByteArray())
            assertArrayEquals(payload, second.body!!.toByteArray())
            assertEquals(payload.size.toString(), first.headers["Content-Length"])
            assertEquals(payload.size.toString(), second.headers["Content-Length"])
            assertTrue(second.headers["Authorization"].orEmpty().startsWith("Digest "))
        } finally {
            server.close()
        }
    }

    @Test(timeout = 20_000)
    fun `imported leaf is trusted and a different leaf is not`() = runBlocking {
        assumeJvmCanLoadDav4jvm()
        val trusted = material("dav4jvm/localhost.cer", "dav4jvm/localhost.pk8")
        val other = material("dav4jvm/other.cer", "dav4jvm/other.pk8")
        val trust = TrustedCertTrustManagerFactory.build(trusted.certificate.encoded)
        val server = httpsServer(trusted)
        try {
            dav4jvmSpikeClient(trust.trustManager).use { http ->
                server.enqueue(MockResponse.Builder().code(204).build())
                DavResource(http, Url(server.url("/dav/").toString())).options()
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }

        val untrusted = httpsServer(other)
        try {
            var failed = false
            try {
                dav4jvmSpikeClient(trust.trustManager).use { http ->
                    DavResource(http, Url(untrusted.url("/dav/").toString())).options()
                }
            } catch (_: Exception) {
                failed = true
            }
            assertTrue(failed)
            assertEquals(0, untrusted.requestCount)
        } finally {
            untrusted.close()
        }
    }

    private fun assumeJvmCanLoadDav4jvm() {
        val (major, _) = dav4jvmClassVersion()
        val runtime = System.getProperty("java.specification.version").substringBefore('.').toInt()
        val required = major - 44
        assumeTrue(
            "dav4jvm class-file major $major needs Java $required; this runtime is $runtime",
            runtime >= required,
        )
    }

    private fun dav4jvmClassVersion(): Pair<Int, String> {
        val resource = checkNotNull(
            javaClass.classLoader!!.getResource("at/bitfire/dav4jvm/ktor/DavResource.class"),
        ) { "dav4jvm is not on the test classpath" }
        val path = resource.path.substringAfter("file:").substringBefore("!")
        val jar = JarFile(java.net.URLDecoder.decode(path, Charsets.UTF_8))
        var highest = 0
        var offender = ""
        jar.use { file ->
            file.entries().asIterator().forEach { entry ->
                if (!entry.name.endsWith(".class")) return@forEach
                file.getInputStream(entry).use { input ->
                    val header = input.readNBytes(8)
                    check(header.size == 8 && header[0] == 0xCA.toByte() && header[1] == 0xFE.toByte()) {
                        "Not a class file: ${entry.name}"
                    }
                    val major = ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
                    if (major > highest) {
                        highest = major
                        offender = entry.name
                    }
                }
            }
        }
        return highest to offender
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
        val key = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(bytes(keyResource)))
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
        val stream: InputStream = checkNotNull(javaClass.classLoader!!.getResourceAsStream(resource)) {
            "Missing test resource $resource"
        }
        return stream.use { it.readBytes() }
    }

    private class ServerMaterial(
        val certificate: X509Certificate,
        val sslContext: SSLContext,
    )
}
