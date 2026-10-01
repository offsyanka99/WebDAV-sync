package org.vovchenko.webdavsync.data.remote.push

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.remote.auth.DigestAuthStrategy

class PushDontNotifyTest {

    private lateinit var server: MockWebServer
    private val holder = PushDontNotify()
    private val urlA = "https://dav.example.com/dav.php/push-subscriptions/" + "A".repeat(43)
    private val urlB = "https://dav.example.com/dav.php/push-subscriptions/" + "B".repeat(43)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `header only on mutating methods`() {
        val client = OkHttpClient.Builder().addInterceptor(holder.interceptor).build()
        holder.set(listOf(urlA))
        val expected = "\"$urlA\""
        for (method in listOf("PUT", "MKCOL", "DELETE", "MOVE", "COPY", "PROPPATCH")) {
            assertEquals(method, expected, send(client, method).getHeader(PushDontNotify.HEADER))
        }
        for (method in listOf("PROPFIND", "GET", "POST")) {
            assertNull(method, send(client, method).getHeader(PushDontNotify.HEADER))
        }
    }

    @Test
    fun `quotes multiple URLs and clears`() {
        val client = OkHttpClient.Builder().addInterceptor(holder.interceptor).build()
        holder.set(listOf(urlA, urlB, urlA, "http://insecure/x", "https://x/\"y"))
        assertEquals("\"$urlA\", \"$urlB\"", send(client, "PUT").getHeader(PushDontNotify.HEADER))
        holder.set(emptyList())
        assertNull(send(client, "PUT").getHeader(PushDontNotify.HEADER))
    }

    @Test
    fun `header survives a Digest 401 retry`() {
        val builder = OkHttpClient.Builder().addInterceptor(holder.interceptor)
        DigestAuthStrategy().apply(builder, WebDavCredentials("alice", "secret"))
        val client = builder.build()
        holder.set(listOf(urlA))
        server.enqueue(
            MockResponse().setResponseCode(401)
                .setHeader("WWW-Authenticate", """Digest realm="test", nonce="abc123", qop="auth", algorithm=MD5"""),
        )
        server.enqueue(MockResponse().setResponseCode(201))
        val request = Request.Builder().url(server.url("/dav/a.txt")).put("x".toRequestBody()).build()
        client.newCall(request).execute().use { assertEquals(201, it.code) }
        val first = server.takeRequest()
        val retry = server.takeRequest()
        assertEquals("\"$urlA\"", first.getHeader(PushDontNotify.HEADER))
        assertEquals("\"$urlA\"", retry.getHeader(PushDontNotify.HEADER))
        assertEquals(true, retry.getHeader("Authorization")?.startsWith("Digest"))
    }

    private fun send(client: OkHttpClient, method: String): okhttp3.mockwebserver.RecordedRequest {
        server.enqueue(MockResponse().setResponseCode(204))
        val body = if (method == "GET") null else "".toRequestBody()
        client.newCall(Request.Builder().url(server.url("/dav/x")).method(method, body).build()).execute().close()
        return server.takeRequest()
    }
}
