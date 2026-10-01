package org.vovchenko.webdavsync.data.local.push

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity

enum class PushEndpointState { REQUESTED, READY, FAILED, UNREGISTERED }

/** The UnifiedPush endpoint of one account (instance `account-{accountId}`). */
@Entity(
    tableName = "push_endpoints",
    foreignKeys = [
        ForeignKey(
            entity = WebDavAccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PushEndpointEntity(
    @PrimaryKey val accountId: Long,
    val instance: String,
    val state: PushEndpointState,
    /** VAPID key the endpoint was requested with. */
    val vapidPublicKey: String?,
    val endpointUrl: String? = null,
    /** p256dh, base64url. */
    val pubKey: String? = null,
    /** 16 bytes, base64url. */
    val authSecret: String? = null,
    val temporary: Boolean = false,
    val lastError: String? = null,
    val updatedAt: Long,
) {
    // Keeps the endpoint URL and keys out of logs.
    override fun toString(): String =
        "PushEndpointEntity(accountId=$accountId, instance=$instance, state=$state, temporary=$temporary, " +
            "lastError=$lastError, updatedAt=$updatedAt)"

    companion object {
        fun instanceFor(accountId: Long): String = "account-$accountId"
    }
}
