package org.vovchenko.webdavsync.data.local.security

/** WebDAV account credentials, kept out of Room entirely (plan §4.6). */
data class WebDavCredentials(
    val username: String,
    val password: String,
) {
    // Security audit finding #13: default data class toString() would print the password verbatim
    // if this were ever logged (directly, or via a containing object's default toString()).
    override fun toString(): String = "WebDavCredentials(username='$username', password='***')"
}
