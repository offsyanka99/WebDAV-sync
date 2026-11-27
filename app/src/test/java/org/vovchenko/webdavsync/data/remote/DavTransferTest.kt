package org.vovchenko.webdavsync.data.remote

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.ktor.buildAccountHttpClient
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory
import java.io.ByteArrayInputStream

/** Robolectric supplies the platform XmlPullParser the quota multistatus needs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DavTransferTest {

    private val payload = "photo-bytes".toByteArray()
    private val registration = "https://dav.example.com/dav.php/push-subscriptions/" + "C".repeat(43)
    private lateinit var server: MockWebServer
    private lateinit var push: PushDontNotify
    private lateinit var client: Dav4jvmWebDavClient
    private var opens = 0

    @Before
    fun setUp() {
        opens = 0
        server = MockWebServer()
        server.start()
        push = PushDontNotify()
        push.set(listOf(registration))
        val trust = TrustedCertTrustManagerFactory.build(null)
        val account = buildAccountHttpClient(
            trust.trustManager,
            AuthScheme.DIGEST,
            WebDavCredentials("alice", "secret"),
            push,
            null,
        )
        client = Dav4jvmWebDavClient(server.url("/dav/").toString(), push, account)
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    @Test
    fun `Digest retry resends the same PUT body`() = runBlocking {
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
        val etag = client.upload("a.txt", "application/octet-stream", payload.size.toLong()) {
            opens += 1
            ByteArrayInputStream(payload)
        }.getOrThrow()
        assertEquals("\"v1\"", etag)
        assertEquals(2, opens)
        assertEquals(2, server.requestCount)
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertEquals("PUT", first.method)
        assertEquals(payload.size.toString(), first.headers["Content-Length"])
        assertEquals(payload.size.toString(), second.headers["Content-Length"])
        assertTrue(first.body!!.toByteArray().contentEquals(payload))
        assertTrue(second.body!!.toByteArray().contentEquals(payload))
        assertEquals("\"$registration\"", first.headers[PushDontNotify.HEADER])
        assertEquals("\"$registration\"", second.headers[PushDontNotify.HEADER])
    }

    @Test
    fun `missing ETag is null`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(204).build())
        val etag = client.upload("empty.txt", "text/plain", 0L) {
            ByteArrayInputStream(ByteArray(0))
        }.getOrThrow()
        assertNull(etag)
    }

    @Test
    fun `MOVE sends Overwrite F`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(201).build())
        client.move("old name.txt", "new name.txt").getOrThrow()
        val request = server.takeRequest()
        assertEquals("MOVE", request.method)
        assertEquals("F", request.headers["Overwrite"])
        assertEquals("/dav/old%20name.txt", request.target)
        val destination = request.headers["Destination"].orEmpty()
        assertTrue(destination, destination.endsWith("/dav/new%20name.txt"))
        assertEquals("\"$registration\"", request.headers[PushDontNotify.HEADER])
    }

    @Test
    fun `quota sentinels and an omitted property are missing`() = runBlocking {
        server.enqueue(quotaResponse("-3", "10"))
        val negative = client.getQuota().getOrThrow()
        assertNull(negative.availableBytes)
        assertEquals(10L, negative.usedBytes)
        val propfind = server.takeRequest()
        assertEquals("PROPFIND", propfind.method)
        assertEquals("0", propfind.headers["Depth"])
        assertNull(propfind.headers[PushDontNotify.HEADER])

        server.enqueue(quotaResponse(null, "-1"))
        val omitted = client.getQuota().getOrThrow()
        assertNull(omitted.availableBytes)
        assertNull(omitted.usedBytes)
    }

    private fun quotaResponse(available: String?, used: String?): MockResponse {
        val availableXml = available?.let { "<d:quota-available-bytes>$it</d:quota-available-bytes>" }.orEmpty()
        val usedXml = used?.let { "<d:quota-used-bytes>$it</d:quota-used-bytes>" }.orEmpty()
        return MockResponse.Builder()
            .code(207)
            .setHeader("Content-Type", "application/xml")
            .body(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <d:multistatus xmlns:d="DAV:">
                  <d:response>
                    <d:href>/dav/</d:href>
                    <d:propstat>
                      <d:prop>$availableXml$usedXml</d:prop>
                      <d:status>HTTP/1.1 200 OK</d:status>
                    </d:propstat>
                  </d:response>
                </d:multistatus>
                """.trimIndent(),
            )
            .build()
    }
}
