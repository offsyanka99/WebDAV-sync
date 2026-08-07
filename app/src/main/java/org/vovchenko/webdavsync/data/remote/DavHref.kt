package org.vovchenko.webdavsync.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Helpers for comparing / parsing WebDAV `href` values. Servers and clients disagree on
 * percent-encoding (e.g. `test%202` vs `test 2`), trailing slashes, and absolute vs path-only
 * hrefs — which previously caused the collection itself to be treated as a child and produced
 * doubled paths like `test 2/test 2`.
 */
object DavHref {

    /** Path form suitable for equality checks: decoded, no trailing slash, no scheme/host. */
    fun normalizePath(hrefOrPath: String): String {
        val raw = hrefOrPath.trim()
        if (raw.isEmpty()) return ""
        val pathOnly = when {
            raw.contains("://") -> raw.toHttpUrlOrNull()?.encodedPath ?: raw
            else -> raw
        }
        return decode(pathOnly).trimEnd('/')
    }

    /**
     * True when [resourceHref] is the collection that was listed (the Depth:1 self entry),
     * not a real child.
     */
    fun isSelf(resourceHref: String, listedCollectionUrl: String): Boolean {
        val resource = normalizePath(resourceHref)
        val listed = normalizePath(listedCollectionUrl)
        if (resource.isEmpty() || listed.isEmpty()) return false
        if (resource == listed) return true
        // Some servers return a relative last-segment-only href for the collection itself.
        val listedName = listed.substringAfterLast('/')
        val resourceName = resource.substringAfterLast('/')
        return resource == resourceName && resourceName == listedName && !resource.contains('/')
    }

    /**
     * Display / relative-path segment for a child resource (last path segment, decoded).
     * Returns null if the name is empty (should be skipped).
     */
    fun childName(resourceHref: String): String? {
        val name = normalizePath(resourceHref).substringAfterLast('/')
        return name.takeIf { it.isNotEmpty() }
    }

    fun decode(value: String): String = try {
        // Don't treat '+' as space in path segments.
        URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8.name())
    } catch (_: Exception) {
        value
    }
}
