package org.vovchenko.webdavsync.push

import okhttp3.mockwebserver.MockResponse
import org.vovchenko.webdavsync.data.remote.push.PushHeaders

/** Response bodies shaped like AngaraDAV 2.5.3 (Sabre/DAV serializer, `x1` push prefix). */
object AngaraDavFixtures {
    const val VAPID = "BVapidKey_-123"

    fun registrationUrl(token: String = "R"): String =
        "https://dav.example.com/dav.php/push-subscriptions/" + token.padEnd(43, 'x').take(43)

    fun multistatus(topic: String = "topic-1", href: String = "/dav.php/files/alice/Sync/", depth: String = "infinity") =
        """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:s="http://sabredav.org/ns" xmlns:x1="https://bitfire.at/webdav-push">
 <d:response>
  <d:href>$href</d:href>
  <d:propstat>
   <d:prop>
    <x1:transports><x1:web-push><x1:vapid-public-key type="p256ecdsa">$VAPID</x1:vapid-public-key></x1:web-push></x1:transports>
    <x1:topic>$topic</x1:topic>
    <x1:supported-triggers><x1:content-update><d:depth>$depth</d:depth></x1:content-update><x1:property-update><d:depth>0</d:depth></x1:property-update></x1:supported-triggers>
   </d:prop>
   <d:status>HTTP/1.1 200 OK</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>"""

    /** File push switched off, or a file: the props come back 404. */
    fun multistatusWithoutPush(href: String = "/dav.php/files/alice/Sync/") = """<?xml version="1.0"?>
<d:multistatus xmlns:d="DAV:" xmlns:x1="https://bitfire.at/webdav-push">
 <d:response>
  <d:href>$href</d:href>
  <d:propstat>
   <d:prop><x1:transports/><x1:topic/><x1:supported-triggers/></d:prop>
   <d:status>HTTP/1.1 404 Not Found</d:status>
  </d:propstat>
 </d:response>
</d:multistatus>"""

    /** `PushPlugin::sendPrecondition`. */
    fun precondition(condition: String) = """<?xml version="1.0" encoding="utf-8"?>
<D:error xmlns:D="DAV:" xmlns:P="https://bitfire.at/webdav-push"><P:$condition/></D:error>"""

    /** `PushWorker::buildMessage` for a file folder: no sync-token. */
    fun contentUpdateMessage(topic: String) = """<?xml version="1.0" encoding="utf-8"?>
<push-message xmlns="https://bitfire.at/webdav-push" xmlns:D="DAV:"><topic>$topic</topic><content-update/></push-message>"""
        .toByteArray()

    fun discovered(topic: String = "topic-1") = MockResponse().setResponseCode(207)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(multistatus(topic))

    fun registered(location: String = registrationUrl(), lifetimeMillis: Long = 7L * 24 * 60 * 60 * 1000) =
        MockResponse().setResponseCode(204)
            .setHeader("Location", location)
            .setHeader("Expires", PushHeaders.formatHttpDate(System.currentTimeMillis() + lifetimeMillis))

    fun rejected(condition: String) = MockResponse().setResponseCode(403)
        .setHeader("Content-Type", "application/xml; charset=utf-8")
        .setBody(precondition(condition))

    fun rateLimited() = MockResponse().setResponseCode(429).setHeader("Retry-After", "3600")
}
