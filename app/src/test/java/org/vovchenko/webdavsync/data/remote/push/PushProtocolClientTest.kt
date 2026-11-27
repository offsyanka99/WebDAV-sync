package org.vovchenko.webdavsync.data.remote.push

import kotlinx.coroutines.test.runTest
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
import org.vovchenko.webdavsync.data.remote.Dav4jvmWebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavException
import org.vovchenko.webdavsync.data.remote.ktor.buildAccountHttpClient
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushProtocolClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: Dav4jvmWebDavClient

    private val registrationUrl = "https://dav.example.com/dav.php/push-subscriptions/" + "T".repeat(43)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val push = PushDontNotify()
        val account = buildAccountHttpClient(
            TrustedCertTrustManagerFactory.build(null).trustManager,
            null,
            null,
            push,
            null,
        )
        client = Dav4jvmWebDavClient(server.url("/dav.php/").toString(), push, account)
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    @Test
    fun `discover sends Depth 0 PROPFIND to the collection and parses 207`() = runTest {
        server.enqueue(MockResponse.Builder().code(207).body(MULTISTATUS).build())
        val capability = client.discoverPush("Sync/My Files").getOrThrow()
        assertEquals("topic-22-chars-abcdefg", capability?.topic)
        assertEquals(PushDepth.INFINITY, capability?.contentDepth)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav.php/Sync/My%20Files/", request.target)
        assertEquals("0", request.headers["Depth"])
        assertTrue(request.body!!.utf8().contains("supported-triggers"))
    }

    @Test
    fun `discover 404 fails with NotFound and 403 with auth failure`() = runTest {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertTrue(client.discoverPush("Gone").exceptionOrNull() is WebDavException.NotFound)
        server.enqueue(MockResponse.Builder().code(403).build())
        assertTrue(client.discoverPush("Forbidden").exceptionOrNull() is WebDavException.AuthenticationFailed)
    }

    @Test
    fun `discover without push properties succeeds with null`() = runTest {
        server.enqueue(
            MockResponse.Builder()
            .code(207)
            .body(
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/x/</d:href></d:response></d:multistatus>""",
            )
            .build(),
        )
        assertNull(client.discoverPush("Sync").getOrThrow())
    }

    @Test
    fun `register 204 returns Location and Expires`() = runTest {
        server.enqueue(
            MockResponse.Builder()
            .code(204)
            .setHeader("Location", registrationUrl)
            .setHeader("Expires", "Fri, 01 Jan 2100 00:00:00 GMT")
            .build(),
        )
        val result = client.registerPush("Sync", subscription()).getOrThrow()
        assertEquals(PushRegistrationResult.Registered(registrationUrl, 4_102_444_800_000L), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/dav.php/Sync/", request.target)
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/xml"))
        assertTrue(request.body!!.utf8().contains("<P:push-register"))
    }

    @Test
    fun `register 201 without Expires falls back to one day`() = runTest {
        server.enqueue(MockResponse.Builder().code(201).setHeader("Location", registrationUrl).build())
        val before = System.currentTimeMillis()
        val result = client.registerPush("Sync", subscription()).getOrThrow() as PushRegistrationResult.Registered
        val day = 24L * 60 * 60 * 1000
        assertTrue(result.expiresAtMillis in (before + day)..(System.currentTimeMillis() + day))
    }

    @Test
    fun `register with an unusable Location fails`() = runTest {
        server.enqueue(MockResponse.Builder().code(204).setHeader("Location", "http://dav.example.com/x").build())
        assertTrue(client.registerPush("Sync", subscription()).getOrThrow() is PushRegistrationResult.Failed)
        server.enqueue(MockResponse.Builder().code(204).build())
        assertTrue(client.registerPush("Sync", subscription()).getOrThrow() is PushRegistrationResult.Failed)
    }

    @Test
    fun `register 403 maps preconditions`() = runTest {
        server.enqueue(precondition("push-not-available"))
        assertEquals(PushRegistrationResult.Unsupported("push-not-available"), client.registerPush("Sync", subscription()).getOrThrow())
        server.enqueue(precondition("no-trigger-supported"))
        assertEquals(PushRegistrationResult.Unsupported("no-trigger-supported"), client.registerPush("Sync", subscription()).getOrThrow())
        server.enqueue(precondition("no-supported-trigger"))
        assertEquals(PushRegistrationResult.Unsupported("no-supported-trigger"), client.registerPush("Sync", subscription()).getOrThrow())
        server.enqueue(precondition("invalid-subscription"))
        assertEquals(PushRegistrationResult.InvalidSubscription, client.registerPush("Sync", subscription()).getOrThrow())
        server.enqueue(MockResponse.Builder().code(403).build())
        assertEquals(PushRegistrationResult.Failed(403, "Forbidden"), client.registerPush("Sync", subscription()).getOrThrow())
    }

    @Test
    fun `register 429 honours Retry-After`() = runTest {
        server.enqueue(MockResponse.Builder().code(429).setHeader("Retry-After", "120").build())
        assertEquals(PushRegistrationResult.RateLimited(120), client.registerPush("Sync", subscription()).getOrThrow())
        server.enqueue(MockResponse.Builder().code(429).build())
        assertEquals(PushRegistrationResult.RateLimited(3600), client.registerPush("Sync", subscription()).getOrThrow())
    }

    @Test
    fun `unregister treats 204 and 404 as success`() = runTest {
        val sameOriginUrl = server.url("/dav.php/push-subscriptions/" + "T".repeat(43)).toString()
        server.enqueue(MockResponse.Builder().code(204).build())
        assertTrue(client.unregisterPush(sameOriginUrl).isSuccess)
        server.enqueue(MockResponse.Builder().code(404).build())
        assertTrue(client.unregisterPush(sameOriginUrl).isSuccess)
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(MockResponse.Builder().code(500).build())
        assertTrue(client.unregisterPush(sameOriginUrl).isFailure)
    }

    @Test
    fun `discover omits Push-Dont-Notify and a Digest unregister retry keeps it`() = runTest {
        val registration = "https://dav.example.com/dav.php/push-subscriptions/" + "A".repeat(43)
        val push = PushDontNotify()
        push.set(listOf(registration))
        val account = buildAccountHttpClient(
            TrustedCertTrustManagerFactory.build(null).trustManager,
            AuthScheme.DIGEST,
            WebDavCredentials("alice", "secret"),
            push,
            null,
        )
        val digestClient = Dav4jvmWebDavClient(server.url("/dav.php/").toString(), push, account)
        try {
            server.enqueue(MockResponse.Builder().code(204).build())
            assertNull(digestClient.discoverPush("Sync").getOrThrow())
            val propfind = server.takeRequest()
            assertEquals("PROPFIND", propfind.method)
            assertNull(propfind.headers[PushDontNotify.HEADER])

            val sameOrigin = server.url("/dav.php/push-subscriptions/" + "T".repeat(43)).toString()
            server.enqueue(
                MockResponse.Builder()
            .code(401)
            .setHeader(
                    "WWW-Authenticate",
                    """Digest realm="test", nonce="abc123", qop="auth", algorithm=MD5""",
                )
            .build(),
            )
            server.enqueue(MockResponse.Builder().code(204).build())
            assertTrue(digestClient.unregisterPush(sameOrigin).isSuccess)
            val first = server.takeRequest()
            val second = server.takeRequest()
            assertEquals("DELETE", first.method)
            assertEquals("DELETE", second.method)
            assertEquals("\"$registration\"", first.headers[PushDontNotify.HEADER])
            assertEquals("\"$registration\"", second.headers[PushDontNotify.HEADER])
            assertTrue(second.headers["Authorization"].orEmpty().startsWith("Digest "))
            assertEquals(3, server.requestCount)
        } finally {
            digestClient.close()
        }
    }

    @Test
    fun `unregister on a foreign origin sends no request`() = runTest {
        val result = client.unregisterPush(registrationUrl)
        assertTrue(result.exceptionOrNull() is WebDavException.ForeignOrigin)
        val otherPort = "http://${server.hostName}:${server.port + 1}/dav.php/push-subscriptions/x"
        assertTrue(client.unregisterPush(otherPort).exceptionOrNull() is WebDavException.ForeignOrigin)
        assertEquals(0, server.requestCount)
    }

    private fun subscription() = PushSubscriptionRequest(
        endpointUrl = "https://push.example/up/abc",
        publicKey = "BPub",
        authSecret = "Auth",
        expiresAtMillis = System.currentTimeMillis() + 7L * 24 * 60 * 60 * 1000,
    )

    private fun precondition(condition: String) = MockResponse.Builder()
            .code(403)
            .setHeader("Content-Type", "application/xml; charset=utf-8")
            .body(
            """<?xml version="1.0" encoding="utf-8"?>
<D:error xmlns:D="DAV:" xmlns:P="https://bitfire.at/webdav-push"><P:$condition/></D:error>""",
        )
            .build()

    private companion object {
        const val MULTISTATUS = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:x1="https://bitfire.at/webdav-push">
 <d:response>
  <d:href>/dav.php/Sync/My%20Files/</d:href>
  <d:propstat>
   <d:prop>
    <x1:transports><x1:web-push><x1:vapid-public-key type="p256ecdsa">BVapid</x1:vapid-public-key></x1:web-push></x1:transports>
    <x1:topic>topic-22-chars-abcdefg</x1:topic>
    <x1:supported-triggers><x1:content-update><d:depth>infinity</d:depth></x1:content-update></x1:supported-triggers>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>"""
    }
}
