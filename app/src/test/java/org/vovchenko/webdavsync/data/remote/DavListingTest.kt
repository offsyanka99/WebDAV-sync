package org.vovchenko.webdavsync.data.remote

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.model.AuthScheme
import org.vovchenko.webdavsync.data.remote.ktor.buildAccountHttpClient
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import org.vovchenko.webdavsync.data.remote.trust.TrustedCertTrustManagerFactory

/** Robolectric supplies the platform XmlPullParser dav4jvm uses to read multistatus. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DavListingTest {

    private lateinit var server: MockWebServer
    private lateinit var push: PushDontNotify
    private lateinit var client: Dav4jvmWebDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        push = PushDontNotify()
        val trust = TrustedCertTrustManagerFactory.build(null)
        val account = buildAccountHttpClient(trust.trustManager, AuthScheme.BASIC, null, push, null)
        client = Dav4jvmWebDavClient(server.url("/dav/").toString(), push, account)
    }

    @After
    fun tearDown() {
        client.close()
        server.close()
    }

    @Test
    fun `depth 1 listing keeps a spaced name and drops the collection itself`() = runBlocking {
        push.set(listOf("https://dav.example.com/dav.php/push-subscriptions/" + "A".repeat(43)))
        server.enqueue(
            MockResponse.Builder()
            .code(207)
            .setHeader("Content-Type", "application/xml")
            .body(MULTISTATUS)
            .build(),
        )
        val listed = client.list("test 2").getOrThrow()
        val request = server.takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("1", request.headers["Depth"])
        assertEquals("/dav/test%202/", request.target)
        assertNull(request.headers[PushDontNotify.HEADER])
        val body = request.body!!.utf8()
        assertTrue(body.contains("resourcetype"))
        assertTrue(body.contains("getcontentlength"))
        assertTrue(body.contains("getlastmodified"))
        assertTrue(body.contains("getetag"))
        assertTrue(body.contains("urn:ietf:params:xml:ns:caldav"))
        assertTrue(body.contains("urn:ietf:params:xml:ns:carddav"))
        assertEquals(listOf("my file.txt", "photo.bin"), listed.map { DavHref.childName(it.path) })
        val file = listed.first { DavHref.childName(it.path) == "my file.txt" }
        assertFalse(file.isDirectory)
        assertEquals(12L, file.sizeBytes)
        assertEquals("\"abc\"", file.etag)
        assertTrue(file.lastModifiedEpochMillis != null)
        val photo = listed.first { DavHref.childName(it.path) == "photo.bin" }
        assertEquals(404L, photo.sizeBytes)
        assertNull(photo.etag)
    }

    @Test
    fun `list 404 names the collection url`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        val failure = client.list("missing").exceptionOrNull()
        assertTrue(failure.toString(), failure is WebDavException.NotFound)
        assertTrue(failure!!.message!!.contains("/dav/missing/"))
    }

    @Test
    fun `mkcol and delete carry the push header and a single slash`() = runBlocking {
        val registration = "https://dav.example.com/dav.php/push-subscriptions/" + "B".repeat(43)
        push.set(listOf(registration))
        server.enqueue(MockResponse.Builder().code(201).build())
        client.createDirectory("test 2/sub").getOrThrow()
        val mkcol = server.takeRequest()
        assertEquals("MKCOL", mkcol.method)
        assertEquals("/dav/test%202/sub/", mkcol.target)
        assertFalse(mkcol.target.contains("//"))
        assertEquals("\"$registration\"", mkcol.headers[PushDontNotify.HEADER])

        server.enqueue(MockResponse.Builder().code(204).build())
        client.delete("test 2/sub/file.txt").getOrThrow()
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("/dav/test%202/sub/file.txt", delete.target)
        assertEquals("\"$registration\"", delete.headers[PushDontNotify.HEADER])
    }

    @Test
    fun `exists is HEAD and 404 is false`() = runBlocking {
        server.enqueue(MockResponse.Builder().code(404).build())
        assertEquals(false, client.exists("test 2").getOrThrow())
        val missing = server.takeRequest()
        assertEquals("HEAD", missing.method)
        assertEquals("/dav/test%202/", missing.target)

        server.enqueue(MockResponse.Builder().code(200).build())
        assertEquals(true, client.exists("test 2").getOrThrow())
    }

    @Test
    fun `download returns the body the caller closes`() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
            .code(200)
            .body("hello-bytes")
            .build(),
        )
        val stream = client.download("test 2/my file.txt").getOrThrow()
        val text = stream.use { it.readBytes().decodeToString() }
        assertEquals("hello-bytes", text)
        val get = server.takeRequest()
        assertEquals("GET", get.method)
        assertEquals("/dav/test%202/my%20file.txt", get.target)
        assertEquals("identity", get.headers["Accept-Encoding"])
    }

    companion object {
        private val MULTISTATUS = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/test%202/</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>http://evil.example/elsewhere</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/test%202/my%20file.txt</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>12</d:getcontentlength>
                    <d:getlastmodified>Wed, 01 Oct 2025 00:00:00 GMT</d:getlastmodified>
                    <d:getetag>&quot;abc&quot;</d:getetag>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/test%202/photo.bin</d:href>
                <d:propstat>
                  <d:prop>
                    <d:resourcetype/>
                    <d:getcontentlength>404</d:getcontentlength>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
                <d:propstat>
                  <d:prop><d:getetag/></d:prop>
                  <d:status>HTTP/1.1 404 Not Found</d:status>
                </d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/test%202/..</d:href>
                <d:propstat>
                  <d:prop><d:resourcetype/></d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
    }
}
