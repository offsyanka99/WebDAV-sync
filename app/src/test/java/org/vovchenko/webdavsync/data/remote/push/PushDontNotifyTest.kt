package org.vovchenko.webdavsync.data.remote.push

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.vovchenko.webdavsync.data.remote.ktor.buildAccountHttpClient
import org.vovchenko.webdavsync.data.remote.ktor.metadataCallTimeout
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory

/** Header value and method filter on the account client. */
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
        server.close()
    }

    @Test
    fun `header only on mutating methods`() = runBlocking {
        val client = accountClient()
        try {
            holder.set(listOf(urlA))
            val expected = "\"$urlA\""
            for (method in listOf("PUT", "MKCOL", "DELETE", "MOVE", "COPY", "PROPPATCH")) {
                assertEquals(method, expected, send(client, method).headers[PushDontNotify.HEADER])
            }
            for (method in listOf("PROPFIND", "GET", "POST")) {
                assertNull(method, send(client, method).headers[PushDontNotify.HEADER])
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `quotes multiple URLs and clears`() = runBlocking {
        val client = accountClient()
        try {
            holder.set(listOf(urlA, urlB, urlA, "http://insecure/x", "https://x/\"y"))
            assertEquals("\"$urlA\", \"$urlB\"", send(client, "PUT").headers[PushDontNotify.HEADER])
            holder.set(emptyList())
            assertNull(send(client, "PUT").headers[PushDontNotify.HEADER])
        } finally {
            client.close()
        }
    }

    private fun accountClient(): HttpClient = buildAccountHttpClient(
        TrustedCertTrustManagerFactory.build(null).trustManager,
        null,
        null,
        holder,
        null,
    )

    private suspend fun send(client: HttpClient, method: String): RecordedRequest {
        server.enqueue(MockResponse.Builder().code(204).build())
        client.prepareRequest(server.url("/dav/x").toString()) {
            this.method = HttpMethod(method)
            if (method != "GET") setBody("")
            metadataCallTimeout()
        }.execute { }
        return server.takeRequest()
    }
}
