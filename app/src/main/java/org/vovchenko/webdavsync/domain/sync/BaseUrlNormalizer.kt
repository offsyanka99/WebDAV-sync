package org.vovchenko.webdavsync.domain.sync

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Canonical https base URL stored on accounts and used to match backup restores. */
object BaseUrlNormalizer {
    fun normalize(raw: String): String {
        val trimmed = raw.trim()
        require(trimmed.startsWith("https://", ignoreCase = true)) {
            "Server address must start with https://"
        }
        val url = trimmed.toHttpUrl()
        require(url.username.isEmpty() && url.password.isEmpty()) {
            "Server URL must not include a username or password"
        }
        require(url.host.isNotEmpty()) { "Server URL is missing a host" }
        return url.newBuilder()
            .scheme("https")
            .host(url.host.lowercase())
            .query(null)
            .fragment(null)
            .build()
            .toString()
            .trimEnd('/')
    }
}
