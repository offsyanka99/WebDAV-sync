package org.vovchenko.webdavsync.data.remote.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric supplies the platform XmlPullParser the parsers run on. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WebDavPushXmlTest {

    @Test
    fun `discovery body asks for the three push properties`() {
        val body = WebDavPushXml.DISCOVERY_BODY
        assertTrue(body.contains("""xmlns:P="https://bitfire.at/webdav-push""""))
        assertTrue(body.contains("<P:transports/>"))
        assertTrue(body.contains("<P:topic/>"))
        assertTrue(body.contains("<P:supported-triggers/>"))
    }

    @Test
    fun `parses AngaraDAV multistatus`() {
        val capability = WebDavPushXml.parseCapability(angaraMultistatus())
        assertNotNull(capability)
        assertEquals("AbCdEfGhIjKlMnOpQrStUv", capability!!.topic)
        assertEquals("BKey_-abc", capability.vapidPublicKey)
        assertEquals(PushDepth.INFINITY, capability.contentDepth)
    }

    @Test
    fun `parses default-namespace multistatus`() {
        val xml = """<?xml version="1.0"?>
<multistatus xmlns="DAV:">
  <response>
    <href>/dav/Sync/</href>
    <propstat>
      <prop>
        <transports xmlns="https://bitfire.at/webdav-push"><web-push/></transports>
        <topic xmlns="https://bitfire.at/webdav-push">topic-1</topic>
        <supported-triggers xmlns="https://bitfire.at/webdav-push">
          <content-update><depth xmlns="DAV:">1</depth></content-update>
        </supported-triggers>
      </prop>
      <status>HTTP/1.1 200 OK</status>
    </propstat>
  </response>
</multistatus>"""
        val capability = WebDavPushXml.parseCapability(xml)
        assertEquals("topic-1", capability?.topic)
        assertNull(capability?.vapidPublicKey)
        assertEquals(PushDepth.ONE, capability?.contentDepth)
    }

    @Test
    fun `prefix must map to the push namespace`() {
        val xml = angaraMultistatus().replace("https://bitfire.at/webdav-push", "urn:other")
        assertNull(WebDavPushXml.parseCapability(xml))
    }

    @Test
    fun `404 propstat means no push`() {
        val xml = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:P="https://bitfire.at/webdav-push">
  <d:response>
    <d:href>/dav.php/files/alice/Sync/</d:href>
    <d:propstat>
      <d:prop><P:transports/><P:topic/><P:supported-triggers/></d:prop>
      <d:status>HTTP/1.1 404 Not Found</d:status>
    </d:propstat>
  </d:response>
</d:multistatus>"""
        assertNull(WebDavPushXml.parseCapability(xml))
    }

    @Test
    fun `missing topic or trigger means no push`() {
        assertNull(WebDavPushXml.parseCapability(angaraMultistatus().replace(Regex("<x1:topic>.*</x1:topic>"), "")))
        assertNull(
            WebDavPushXml.parseCapability(
                angaraMultistatus().replace(Regex("<x1:content-update>.*?</x1:content-update>"), ""),
            ),
        )
        assertNull(WebDavPushXml.parseCapability(angaraMultistatus().replace(Regex("<x1:web-push>.*</x1:web-push>"), "")))
    }

    @Test
    fun `multistatus with a DOCTYPE or over the cap is rejected`() {
        val withDoctype = angaraMultistatus().replace(
            """<?xml version="1.0"?>""",
            """<?xml version="1.0"?><!DOCTYPE x [<!ENTITY e "boom">]>""",
        )
        assertNull(WebDavPushXml.parseCapability(withDoctype))
        val padded = angaraMultistatus().replace("<d:response>", "<d:response><!--" + "x".repeat(70_000) + "-->")
        assertNull(WebDavPushXml.parseCapability(padded))
    }

    @Test
    fun `register body escapes the endpoint and requests depth infinity`() {
        val body = WebDavPushXml.registerBody(
            PushSubscriptionRequest(
                endpointUrl = "https://push.example/up?a=1&b=<2>",
                publicKey = "BPubKey",
                authSecret = "AuthSecret",
                expiresAtMillis = 1_790_000_000_000L,
            ),
        )
        assertTrue(body.contains("<P:push-resource>https://push.example/up?a=1&amp;b=&lt;2&gt;</P:push-resource>"))
        assertTrue(body.contains("<P:content-encoding>aes128gcm</P:content-encoding>"))
        assertTrue(body.contains("""<P:subscription-public-key type="p256dh">BPubKey</P:subscription-public-key>"""))
        assertTrue(body.contains("<P:auth-secret>AuthSecret</P:auth-secret>"))
        assertTrue(body.contains("<P:content-update><D:depth>infinity</D:depth></P:content-update>"))
        assertFalse(body.contains("property-update"))
        assertTrue(body.contains("<P:expires>Mon, 21 Sep 2026 14:13:20 GMT</P:expires>"))
    }

    @Test
    fun `parses AngaraDAV file message`() {
        val message = WebDavPushXml.parseMessage(
            """<?xml version="1.0" encoding="utf-8"?>
<push-message xmlns="https://bitfire.at/webdav-push" xmlns:D="DAV:"><topic>t1</topic><content-update/></push-message>"""
                .toByteArray(),
        )
        assertEquals(listOf("t1"), message?.topics)
        assertTrue(message!!.contentUpdate)
        assertNull(message.syncToken)
        assertFalse(message.propertyUpdate)
        assertFalse(message.isVapidRotation)
    }

    @Test
    fun `parses sync token and multiple topics`() {
        val message = WebDavPushXml.parseMessage(
            """<P:push-message xmlns:P="https://bitfire.at/webdav-push" xmlns:D="DAV:">
<P:topic>a</P:topic><P:topic>b</P:topic>
<P:content-update><D:sync-token>http://sabre.io/ns/sync/7</D:sync-token></P:content-update>
</P:push-message>""".toByteArray(),
        )
        assertEquals(listOf("a", "b"), message?.topics)
        assertEquals("http://sabre.io/ns/sync/7", message?.syncToken)
    }

    @Test
    fun `property update only, and key rotation`() {
        val propertyOnly = WebDavPushXml.parseMessage(
            """<push-message xmlns="https://bitfire.at/webdav-push"><topic>t</topic><property-update/></push-message>"""
                .toByteArray(),
        )
        assertFalse(propertyOnly!!.contentUpdate)
        assertTrue(propertyOnly.propertyUpdate)
        assertFalse(propertyOnly.isVapidRotation)

        val rotation = WebDavPushXml.parseMessage(
            """<push-message xmlns="https://bitfire.at/webdav-push" xmlns:D="DAV:"><topic>t</topic>
<property-update><D:prop><transports/></D:prop></property-update></push-message>""".toByteArray(),
        )
        assertTrue(rotation!!.isVapidRotation)
    }

    @Test
    fun `rejects hostile or unusable messages`() {
        val doctype = """<?xml version="1.0"?><!DOCTYPE push-message [<!ENTITY x "y">]>
<push-message xmlns="https://bitfire.at/webdav-push"><topic>&x;</topic><content-update/></push-message>"""
        assertNull(WebDavPushXml.parseMessage(doctype.toByteArray()))
        val oversize = """<push-message xmlns="https://bitfire.at/webdav-push"><topic>t</topic><content-update/>""" +
            "<!--" + "x".repeat(5000) + "--></push-message>"
        assertNull(WebDavPushXml.parseMessage(oversize.toByteArray()))
        assertNull(WebDavPushXml.parseMessage("not xml at all".toByteArray()))
        assertNull(WebDavPushXml.parseMessage("""<push-message xmlns="urn:x"><topic>t</topic></push-message>""".toByteArray()))
        assertNull(
            WebDavPushXml.parseMessage(
                """<push-message xmlns="https://bitfire.at/webdav-push"><content-update/></push-message>""".toByteArray(),
            ),
        )
    }

    @Test
    fun `parses preconditions in both spellings`() {
        fun error(condition: String) =
            """<?xml version="1.0" encoding="utf-8"?>
<D:error xmlns:D="DAV:" xmlns:P="https://bitfire.at/webdav-push"><P:$condition/></D:error>"""
        assertEquals("push-not-available", WebDavPushXml.parsePrecondition(error("push-not-available")))
        assertEquals("no-trigger-supported", WebDavPushXml.parsePrecondition(error("no-trigger-supported")))
        assertEquals("no-supported-trigger", WebDavPushXml.parsePrecondition(error("no-supported-trigger")))
        assertEquals("invalid-subscription", WebDavPushXml.parsePrecondition(error("invalid-subscription")))
        assertNull(WebDavPushXml.parsePrecondition("<html/>"))
    }

    @Test
    fun `registration URL validation`() {
        val token = "a".repeat(43)
        assertTrue(PushHeaders.isValidRegistrationUrl("https://dav.example.com/dav.php/push-subscriptions/$token"))
        assertFalse(PushHeaders.isValidRegistrationUrl("http://dav.example.com/dav.php/push-subscriptions/$token"))
        assertFalse(PushHeaders.isValidRegistrationUrl("/dav.php/push-subscriptions/$token"))
        assertFalse(PushHeaders.isValidRegistrationUrl("https://dav.example.com/a\"b"))
        assertFalse(PushHeaders.isValidRegistrationUrl("https://dav.example.com/a\\b"))
        assertFalse(PushHeaders.isValidRegistrationUrl("https://dav.example.com/a\r\nX-Evil: 1"))
        assertFalse(PushHeaders.isValidRegistrationUrl("https://dav.example.com/a b"))
        assertFalse(PushHeaders.isValidRegistrationUrl("https://dav.example.com/" + "a".repeat(2048)))
        assertFalse(PushHeaders.isValidRegistrationUrl(null))
    }

    @Test
    fun `http dates and retry-after`() {
        val now = 1_790_000_000_000L
        assertEquals(now, PushHeaders.parseHttpDate(PushHeaders.formatHttpDate(now)))
        assertEquals(now, PushHeaders.parseHttpDate("Mon, 21 Sep 2026 14:13:20 GMT"))
        assertNull(PushHeaders.parseHttpDate("tomorrow"))
        assertEquals(120L, PushHeaders.parseRetryAfterSeconds("120", now))
        assertEquals(600L, PushHeaders.parseRetryAfterSeconds(PushHeaders.formatHttpDate(now + 600_000), now))
        assertEquals(3600L, PushHeaders.parseRetryAfterSeconds(null, now))
        assertEquals(3600L, PushHeaders.parseRetryAfterSeconds("soon", now))
    }

    private fun angaraMultistatus() = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:x1="https://bitfire.at/webdav-push">
 <d:response>
  <d:href>/dav.php/files/alice/Sync/</d:href>
  <d:propstat>
   <d:prop>
    <x1:transports><x1:web-push><x1:vapid-public-key type="p256ecdsa">BKey_-abc</x1:vapid-public-key></x1:web-push></x1:transports>
    <x1:topic>AbCdEfGhIjKlMnOpQrStUv</x1:topic>
    <x1:supported-triggers><x1:content-update><d:depth>infinity</d:depth></x1:content-update><x1:property-update><d:depth>0</d:depth></x1:property-update></x1:supported-triggers>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>"""
}
