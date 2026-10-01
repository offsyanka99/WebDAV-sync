package org.vovchenko.webdavsync.data.remote.push

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Builders and parsers for WebDAV-Push XML (draft-bitfire-webdav-push).
 *
 * Elements are matched on namespace URI plus local name, never on prefixes. Input with a DOCTYPE
 * is rejected, and every parse is size- and depth-capped. Parsers return null on anything they
 * cannot trust.
 */
object WebDavPushXml {
    const val NS_PUSH = "https://bitfire.at/webdav-push"
    const val NS_DAV = "DAV:"

    const val MAX_MULTISTATUS_BYTES = 64 * 1024
    const val MAX_ERROR_BYTES = 64 * 1024
    const val MAX_MESSAGE_BYTES = 4 * 1024

    private const val MAX_DEPTH = 32
    private const val MAX_ELEMENTS = 4096
    private const val MAX_TOPIC_LENGTH = 256
    private const val MAX_VAPID_KEY_LENGTH = 256

    const val DISCOVERY_BODY = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:" xmlns:P="https://bitfire.at/webdav-push">
  <D:prop><P:transports/><P:topic/><P:supported-triggers/></D:prop>
</D:propfind>"""

    fun registerBody(request: PushSubscriptionRequest): String = buildString {
        append("""<?xml version="1.0" encoding="utf-8"?>""").append('\n')
        append("""<P:push-register xmlns:P="$NS_PUSH" xmlns:D="DAV:">""").append('\n')
        append("  <P:subscription>\n")
        append("    <P:web-push-subscription>\n")
        append("      <P:push-resource>").append(escape(request.endpointUrl)).append("</P:push-resource>\n")
        append("      <P:content-encoding>aes128gcm</P:content-encoding>\n")
        append("""      <P:subscription-public-key type="p256dh">""")
            .append(escape(request.publicKey)).append("</P:subscription-public-key>\n")
        append("      <P:auth-secret>").append(escape(request.authSecret)).append("</P:auth-secret>\n")
        append("    </P:web-push-subscription>\n")
        append("  </P:subscription>\n")
        append("  <P:trigger>\n")
        append("    <P:content-update><D:depth>").append(request.contentDepth.wire).append("</D:depth></P:content-update>\n")
        append("  </P:trigger>\n")
        append("  <P:expires>").append(PushHeaders.formatHttpDate(request.expiresAtMillis)).append("</P:expires>\n")
        append("</P:push-register>")
    }

    /**
     * Reads a Depth-0 PROPFIND multistatus. Returns a capability only when a `200` propstat holds
     * a non-empty topic, a `web-push` transport, and a `content-update` trigger.
     */
    fun parseCapability(xml: String): PushCapability? {
        val root = parse(xml, MAX_MULTISTATUS_BYTES) ?: return null
        if (!root.isA(NS_DAV, "multistatus")) return null
        var topic: String? = null
        var webPush = false
        var vapidKey: String? = null
        var contentDepth: PushDepth? = null
        for (response in root.children(NS_DAV, "response")) {
            for (propstat in response.children(NS_DAV, "propstat")) {
                if (!isOkStatus(propstat.child(NS_DAV, "status")?.text())) continue
                val prop = propstat.child(NS_DAV, "prop") ?: continue
                prop.child(NS_PUSH, "topic")?.text()?.takeIf(::isSafeTopic)?.let { topic = it }
                prop.child(NS_PUSH, "transports")?.child(NS_PUSH, "web-push")?.let { transport ->
                    webPush = true
                    transport.children(NS_PUSH, "vapid-public-key")
                        .firstOrNull { key -> key.attribute("type").let { it == null || it.equals("p256ecdsa", ignoreCase = true) } }
                        ?.text()
                        ?.takeIf(::isSafeVapidKey)
                        ?.let { vapidKey = it }
                }
                prop.child(NS_PUSH, "supported-triggers")?.child(NS_PUSH, "content-update")?.let { trigger ->
                    // A trigger without a readable depth is still a trigger; assume the narrowest useful one.
                    contentDepth = PushDepth.fromWire(trigger.child(NS_DAV, "depth")?.text()) ?: PushDepth.ONE
                }
            }
        }
        val resolvedTopic = topic ?: return null
        val depth = contentDepth ?: return null
        if (!webPush) return null
        return PushCapability(topic = resolvedTopic, vapidPublicKey = vapidKey, contentDepth = depth)
    }

    /** Local name of the precondition element in a `{DAV:}error` body (RFC 4918 §16), or null. */
    fun parsePrecondition(xml: String): String? {
        val root = parse(xml, MAX_ERROR_BYTES) ?: return null
        if (!root.isA(NS_DAV, "error")) return null
        return root.children.firstOrNull()?.name
    }

    fun parseMessage(bytes: ByteArray): PushMessageContent? {
        if (bytes.size > MAX_MESSAGE_BYTES) return null
        val root = parse(bytes.toString(Charsets.UTF_8), MAX_MESSAGE_BYTES) ?: return null
        if (!root.isA(NS_PUSH, "push-message")) return null
        val topics = root.children(NS_PUSH, "topic").mapNotNull { it.text().takeIf(::isSafeTopic) }.distinct()
        if (topics.isEmpty()) return null
        val contentUpdate = root.child(NS_PUSH, "content-update")
        val propertyUpdate = root.child(NS_PUSH, "property-update")
        return PushMessageContent(
            topics = topics,
            contentUpdate = contentUpdate != null,
            syncToken = contentUpdate?.child(NS_DAV, "sync-token")?.text()?.takeIf { it.isNotEmpty() },
            propertyUpdate = propertyUpdate != null,
            isVapidRotation = propertyUpdate?.child(NS_DAV, "prop")?.child(NS_PUSH, "transports") != null,
        )
    }

    private fun isOkStatus(status: String?): Boolean =
        status != null && Regex("""^HTTP/\d(\.\d)?\s+200\b""").containsMatchIn(status.trim())

    private fun isSafeTopic(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_TOPIC_LENGTH && value.all { it.code in 0x21..0x7e }

    private fun isSafeVapidKey(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_VAPID_KEY_LENGTH && value.all { it.isLetterOrDigit() || it in "-_=" }

    private fun escape(value: String): String = buildString(value.length) {
        for (c in value) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    internal class Node(val namespace: String, val name: String, private val attributes: Map<String, String>) {
        val children = mutableListOf<Node>()
        val content = StringBuilder()

        fun isA(ns: String, local: String) = namespace == ns && name == local
        fun child(ns: String, local: String): Node? = children.firstOrNull { it.isA(ns, local) }
        fun children(ns: String, local: String): List<Node> = children.filter { it.isA(ns, local) }
        fun attribute(local: String): String? = attributes[local]
        fun text(): String = content.toString().trim()
    }

    internal fun parse(xml: String, maxBytes: Int): Node? {
        if (xml.length > maxBytes || xml.toByteArray(Charsets.UTF_8).size > maxBytes) return null
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) return null
        return try {
            val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            val stack = ArrayDeque<Node>()
            var root: Node? = null
            var elements = 0
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.DOCDECL -> return null
                    XmlPullParser.START_TAG -> {
                        elements++
                        if (elements > MAX_ELEMENTS || stack.size >= MAX_DEPTH) return null
                        val attributes = HashMap<String, String>()
                        for (i in 0 until parser.attributeCount) {
                            attributes[parser.getAttributeName(i)] = parser.getAttributeValue(i)
                        }
                        val node = Node(parser.namespace.orEmpty(), parser.name, attributes)
                        val parent = stack.lastOrNull()
                        if (parent == null) {
                            if (root != null) return null
                            root = node
                        } else {
                            parent.children += node
                        }
                        stack.addLast(node)
                    }
                    XmlPullParser.TEXT -> stack.lastOrNull()?.content?.append(parser.text)
                    XmlPullParser.END_TAG -> stack.removeLastOrNull()
                }
                event = parser.next()
            }
            root
        } catch (e: Exception) {
            null
        }
    }
}
