package org.vovchenko.webdavsync.data.remote.push

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.remote.SardineWebDavClient
import org.vovchenko.webdavsync.data.remote.WebDavException
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PushProtocolClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: SardineWebDavClient

    private val registrationUrl = "https://dav.example.com/dav.php/push-subscriptions/" + "T".repeat(43)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val http = OkHttpClient.Builder().followRedirects(false).build()
        client = SardineWebDavClient(http, http, server.url("/dav.php/").toString(), File("build/tmp"))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `discover sends Depth 0 PROPFIND to the collection and parses 207`() = runTest {
        server.enqueue(MockResponse().setResponseCode(207).setBody(MULTISTATUS))
        val capability = client.discoverPush("Sync/My Files").getOrThrow()
        assertEquals("topic-22-chars-abcdefg", capability?.topic)
        assertEquals(PushDepth.INFINITY, capability?.contentDepth)
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav.php/Sync/My%20Files/", request.path)
        assertEquals("0", request.getHeader("Depth"))
        assertTrue(request.body.readUtf8().contains("supported-triggers"))
    }

    @Test
    fun `discover 404 fails with NotFound and 403 with auth failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(client.discoverPush("Gone").exceptionOrNull() is WebDavException.NotFound)
        server.enqueue(MockResponse().setResponseCode(403))
        assertTrue(client.discoverPush("Forbidden").exceptionOrNull() is WebDavException.AuthenticationFailed)
    }

    @Test
    fun `discover without push properties succeeds with null`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(207).setBody(
                """<d:multistatus xmlns:d="DAV:"><d:response><d:href>/x/</d:href></d:response></d:multistatus>""",
            ),
        )
        assertNull(client.discoverPush("Sync").getOrThrow())
    }

    @Test
    fun `register 204 returns Location and Expires`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(204)
                .setHeader("Location", registrationUrl)
                .setHeader("Expires", "Fri, 01 Jan 2100 00:00:00 GMT"),
        )
        val result = client.registerPush("Sync", subscription()).getOrThrow()
        assertEquals(PushRegistrationResult.Registered(registrationUrl, 4_102_444_800_000L), result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/dav.php/Sync/", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/xml"))
        assertTrue(request.body.readUtf8().contains("<P:push-register"))
    }

    @Test
    fun `register 201 without Expires falls back to one day`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setHeader("Location", registrationUrl))
        val before = System.currentTimeMillis()
        val result = client.registerPush("Sync", subscription()).getOrThrow() as PushRegistrationResult.Registered
        val day = 24L * 60 * 60 * 1000
        assertTrue(result.expiresAtMillis in (before + day)..(System.currentTimeMillis() + day))
    }

    @Test
    fun `register with an unusable Location fails`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204).setHeader("Location", "http://dav.example.com/x"))
        assertTrue(client.registerPush("Sync", subscription()).getOrThrow() is PushRegistrationResult.Failed)
        server.enqueue(MockResponse().setResponseCode(204))
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
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(PushRegistrationResult.Failed(403, "Forbidden"), client.registerPush("Sync", subscription()).getOrThrow())
    }

    @Test
    fun `register 429 honours Retry-After`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
        assertEquals(PushRegistrationResult.RateLimited(120), client.registerPush("Sync", subscription()).getOrThrow())
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(PushRegistrationResult.RateLimited(3600), client.registerPush("Sync", subscription()).getOrThrow())
    }

    @Test
    fun `unregister treats 204 and 404 as success`() = runTest {
        val sameOriginUrl = server.url("/dav.php/push-subscriptions/" + "T".repeat(43)).toString()
        server.enqueue(MockResponse().setResponseCode(204))
        assertTrue(client.unregisterPush(sameOriginUrl).isSuccess)
        server.enqueue(MockResponse().setResponseCode(404))
        assertTrue(client.unregisterPush(sameOriginUrl).isSuccess)
        assertEquals("DELETE", server.takeRequest().method)
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(client.unregisterPush(sameOriginUrl).isFailure)
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

    private fun precondition(condition: String) = MockResponse()
        .setResponseCode(403)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(
            """<?xml version="1.0" encoding="utf-8"?>
<D:error xmlns:D="DAV:" xmlns:P="https://bitfire.at/webdav-push"><P:$condition/></D:error>""",
        )

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
