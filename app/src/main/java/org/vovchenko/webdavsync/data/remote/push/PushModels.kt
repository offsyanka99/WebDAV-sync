package org.vovchenko.webdavsync.data.remote.push

/** WebDAV-Push trigger depth (draft-bitfire-webdav-push §5). */
enum class PushDepth(val wire: String) {
    ZERO("0"),
    ONE("1"),
    INFINITY("infinity"),
    ;

    companion object {
        fun fromWire(value: String?): PushDepth? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Push support advertised by a collection: a `web-push` transport, a topic, and a `content-update` trigger. */
data class PushCapability(
    val topic: String,
    /** Base64url VAPID key (`p256ecdsa`), when the server advertises one. */
    val vapidPublicKey: String?,
    val contentDepth: PushDepth,
)

/** A Web Push subscription (RFC 8291 keys) to register on a collection. */
data class PushSubscriptionRequest(
    val endpointUrl: String,
    /** Uncompressed P-256 public key, base64url. */
    val publicKey: String,
    /** 16-byte auth secret, base64url. */
    val authSecret: String,
    val expiresAtMillis: Long,
    val contentDepth: PushDepth = PushDepth.INFINITY,
)

sealed interface PushRegistrationResult {
    /** [registrationUrl] is the exact, validated `Location` header value. */
    data class Registered(val registrationUrl: String, val expiresAtMillis: Long) : PushRegistrationResult

    /** `push-not-available` or `no-trigger-supported`: the collection does not offer push. */
    data class Unsupported(val condition: String?) : PushRegistrationResult

    /** The server rejected the push resource or keys (for example, not public HTTPS). */
    data object InvalidSubscription : PushRegistrationResult

    data class RateLimited(val retryAfterSeconds: Long) : PushRegistrationResult

    /** Any other HTTP outcome, including a success without a usable `Location`. */
    data class Failed(val httpCode: Int?, val reason: String) : PushRegistrationResult

    companion object {
        const val NO_LOCATION = "Server sent no usable registration URL"
    }
}

/** Parsed `<push-message>` (draft §6). */
data class PushMessageContent(
    val topics: List<String>,
    val contentUpdate: Boolean,
    val syncToken: String?,
    val propertyUpdate: Boolean,
    /** `property-update` naming `transports`: the server's VAPID key changed. */
    val isVapidRotation: Boolean,
)
