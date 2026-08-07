package org.vovchenko.webdavsync.data.remote

/**
 * Rejects path-traversal / escape attempts before a remote path is resolved into a request URL
 * (security audit finding #1). Remote path segments can originate from untrusted input — a
 * malicious/misconfigured WebDAV server's PROPFIND response names, or a restored backup JSON
 * (plan §4.8) — so every path must be normalized and checked here, at the single chokepoint all
 * [WebDavClient] operations funnel through ([SardineWebDavClient.resolve]).
 */
object WebDavPathSafety {
    /**
     * Splits [path] on `/`, drops empty segments, and rejects `.` / `..` segments (which could
     * otherwise walk the resolved URL outside the account's configured base URL). Returns the
     * normalized, slash-joined path (no leading/trailing slash).
     *
     * @throws IllegalArgumentException if [path] contains an unsafe segment.
     */
    fun sanitize(path: String): String {
        val segments = path.split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." }) { "Unsafe WebDAV path segment in: $path" }
        return segments.joinToString("/")
    }
}
