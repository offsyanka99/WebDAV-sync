package org.vovchenko.webdavsync.data.local.push

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity

enum class PushRegistrationState {
    /** Desired; capability not known yet. */
    DISCOVER,
    /** No push properties, `push-not-available`, or `no-trigger-supported`. */
    UNSUPPORTED,
    /** PROPFIND 404: the first sync has not created the remote root yet. */
    WAITING_FOR_ROOT,
    /** Push-capable; no READY endpoint for this account yet. */
    WAITING_FOR_ENDPOINT,
    /** Registered and not expired. */
    ACTIVE,
    /** See [PushRegistrationEntity.lastError] and [PushRegistrationEntity.nextAttemptAt]. */
    FAILED,
    /** No longer desired; DELETE the registration URL, then drop the row. */
    PENDING_DELETE,
}

/**
 * One server subscription per distinct `(accountId, remotePath)` of eligible pairs. The pairs a
 * row serves are computed from the folder pairs, not stored.
 */
@Entity(
    tableName = "push_registrations",
    foreignKeys = [
        ForeignKey(
            entity = WebDavAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["accountId", "remotePath"], unique = true),
        Index(value = ["accountId", "topic"]),
    ],
)
data class PushRegistrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: Long,
    /** `WebDavPathSafety.sanitize(pair.remoteFolderPath)`. */
    val remotePath: String,
    val state: PushRegistrationState,
    val topic: String? = null,
    /** "0", "1", or "infinity". */
    val advertisedDepth: String? = null,
    /** VAPID key advertised with [topic]; compared with the endpoint's key to detect rotation. */
    val vapidPublicKey: String? = null,
    /** Exact `Location` header value. */
    val registrationUrl: String? = null,
    /** Push resource this registration was made with. */
    val endpointUrl: String? = null,
    val expiresAt: Long? = null,
    /** When the server last granted [expiresAt]; the renewal window is half of that lifetime. */
    val registeredAt: Long? = null,
    /** User-safe text; never contains URLs. */
    val lastError: String? = null,
    /** Backoff or `Retry-After`. */
    val nextAttemptAt: Long? = null,
    /** Last capability PROPFIND. */
    val lastCheckedAt: Long? = null,
) {
    // Keeps the push resource and registration token out of logs.
    override fun toString(): String =
        "PushRegistrationEntity(id=$id, accountId=$accountId, state=$state, advertisedDepth=$advertisedDepth, " +
            "expiresAt=$expiresAt, lastError=$lastError, nextAttemptAt=$nextAttemptAt)"
}
