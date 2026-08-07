package org.vovchenko.webdavsync.data.local

import androidx.room.PrimaryKey
import androidx.room.Entity
import org.vovchenko.webdavsync.data.model.AuthScheme

/**
 * A configured WebDAV server (plan §3, §4.5). Credentials and trusted certificates are
 * intentionally NOT columns here — see [org.vovchenko.webdavsync.data.local.security.CredentialStore]
 * and [org.vovchenko.webdavsync.data.local.security.TrustedCertStore] (plan §4.6).
 */
@Entity(tableName = "webdav_accounts")
data class WebDavAccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val baseUrl: String,
    val authScheme: AuthScheme? = null,
    val storageQuotaBytes: Long? = null,
    val storageAvailableBytes: Long? = null,
)
