package org.vovchenko.webdavsync.data.remote

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.InputStream
import java.io.StringReader

/**
 * [WebDavClient] backed by Sardine-Android for standard DAV verbs, plus a raw `PROPFIND` for
 * quota (RFC 4331), which Sardine doesn't expose directly.
 */
class SardineWebDavClient(
    private val bodyClient: OkHttpClient,
    /** Listings, MKCOL, DELETE, exists, and quota. Shares [bodyClient]'s pool and dispatcher. */
    private val metaClient: OkHttpClient,
    private val baseUrl: String,
    private val uploadCacheDir: File,
) : WebDavClient {

    private val bodySardine by lazy { OkHttpSardine(bodyClient) }
    private val metaSardine by lazy { OkHttpSardine(metaClient) }

    override fun close() {
        // metaClient is a newBuilder() of bodyClient, so they share the pool and dispatcher.
        WebDavClientFactory.shutdown(bodyClient)
    }

    /**
     * Builds an absolute request URL under [baseUrl].
     *
     * - Sanitizes path segments (no `..`).
     * - Encodes spaces/special characters per segment (e.g. `Test 1` → `Test%201`).
     * - For collections (list / MKCOL / Depth-0 PROPFIND), appends a trailing `/` — many WebDAV
     *   servers (including Apache + Nextcloud/ownCloud-style frontends) return **404** on
     *   collection PROPFIND when the slash is missing.
     */
    private fun resolve(remotePath: String, asCollection: Boolean = false): String {
        val baseHttp = baseUrl.trimEnd('/').toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid base URL: $baseUrl")
        val relative = WebDavPathSafety.sanitize(remotePath)
        val builder = baseHttp.newBuilder()
        if (relative.isNotEmpty()) {
            for (segment in relative.split('/')) {
                builder.addPathSegment(segment)
            }
        }
        val absolute = builder.build().toString().trimEnd('/')
        return if (asCollection) "$absolute/" else absolute
    }

    override suspend fun testConnection(): Result<Unit> = runCatchingWebDav {
        metaSardine.list(resolve("", asCollection = true))
        Unit
    }

    override suspend fun list(remotePath: String): Result<List<WebDavResource>> = runCatchingWebDav {
        val targetUrl = resolve(remotePath, asCollection = true)
        try {
            // Depth:1 PROPFIND always echoes the collection itself as the first multistatus
            // response. Filter it out using encoding-tolerant path comparison — a strict string
            // match fails when the server returns `test 2` and we requested `test%202`, which
            // previously made the scanner recurse into `test 2/test 2` and 404.
            metaSardine.list(targetUrl)
                .filter { resource -> !DavHref.isSelf(resource.path, targetUrl) }
                .map { resource ->
                    WebDavResource(
                        path = resource.path,
                        isDirectory = resource.isDirectory,
                        sizeBytes = resource.contentLength ?: 0L,
                        lastModifiedEpochMillis = resource.modified?.time,
                        etag = resource.etag,
                    )
                }
        } catch (e: Exception) {
            // Surface the URL that 404'd so diagnostic logs are actionable.
            if (httpCodeFromMessage(e.message) == 404) {
                throw WebDavException.NotFound(targetUrl)
            }
            throw e
        }
    }

    override suspend fun upload(
        remotePath: String,
        contentType: String,
        contentLength: Long,
        openContent: () -> InputStream,
    ): Result<String?> =
        runCatchingWebDav {
            // Stream from the source (SAF) with known Content-Length. [openContent] is called on
            // every writeTo() so Digest 401 retries re-read from the start without copying the
            // whole file into cache (parallel multi‑100MB staging was OOMing / hanging sync).
            val mediaType = contentType.toMediaTypeOrNull()
            val body = object : RequestBody() {
                override fun contentType(): MediaType? = mediaType
                override fun contentLength(): Long = contentLength
                override fun writeTo(sink: BufferedSink) {
                    openContent().use { input ->
                        val buffer = ByteArray(UPLOAD_COPY_BUFFER)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            sink.write(buffer, 0, n)
                        }
                    }
                }
            }
            val url = resolve(remotePath, asCollection = false)
            val request = Request.Builder()
                .url(url)
                .put(body)
                .build()
            bodyClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw java.io.IOException(
                        "PUT failed: HTTP ${response.code} for $remotePath ($contentLength bytes)",
                    )
                }
                response.header("ETag")
            }
        }

    override suspend fun download(remotePath: String): Result<InputStream> = runCatchingWebDav {
        bodySardine.get(resolve(remotePath, asCollection = false))
    }

    override suspend fun delete(remotePath: String): Result<Unit> = runCatchingWebDav {
        // Prefer no trailing slash for deletes; works for both files and most collection DELETEs.
        metaSardine.delete(resolve(remotePath, asCollection = false))
    }

    override suspend fun createDirectory(remotePath: String): Result<Unit> = runCatchingWebDav {
        metaSardine.createDirectory(resolve(remotePath, asCollection = true))
    }

    override suspend fun move(fromRemotePath: String, toRemotePath: String): Result<Unit> = runCatchingWebDav {
        val source = resolve(fromRemotePath, asCollection = false)
        val destination = resolve(toRemotePath, asCollection = false)
        val request = Request.Builder()
            .url(source)
            .method("MOVE", null)
            .header("Destination", destination)
            .header("Overwrite", "F")
            .build()
        metaClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("MOVE failed: HTTP ${response.code} $fromRemotePath -> $toRemotePath")
            }
        }
    }

    override suspend fun exists(remotePath: String): Result<Boolean> = runCatchingWebDav {
        // Collections are the usual exists() target; trailing slash avoids false 404s.
        metaSardine.exists(resolve(remotePath, asCollection = true))
    }

    override suspend fun getQuota(): Result<WebDavQuota> = runCatchingWebDav {
        val requestBody = QUOTA_PROPFIND_BODY.toRequestBody("text/xml".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(resolve("", asCollection = true))
            .method("PROPFIND", requestBody)
            .header("Depth", "0")
            .build()

        metaClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw java.io.IOException("PROPFIND quota failed: HTTP ${response.code}")
            }
            parseQuota(response.body?.string().orEmpty())
        }
    }

    private fun parseQuota(xml: String): WebDavQuota {
        var available: Long? = null
        var used: Long? = null

        val parser: XmlPullParser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                when (parser.name.substringAfterLast(':').lowercase()) {
                    "quota-available-bytes" -> {
                        val text = runCatching { parser.nextText().trim() }.getOrNull()
                        available = parseQuotaNumber(text) ?: available
                        eventType = parser.eventType
                        continue
                    }
                    "quota-used-bytes" -> {
                        val text = runCatching { parser.nextText().trim() }.getOrNull()
                        used = parseQuotaNumber(text) ?: used
                        eventType = parser.eventType
                        continue
                    }
                }
            }
            eventType = parser.next()
        }

        return WebDavQuota(availableBytes = available, usedBytes = used)
    }

    /**
     * RFC 4331: negative sentinels mean unknown/unlimited (-1 unknown, -2 not determined, -3 unlimited).
     * Treat those as missing so we do not show "10 GB free of 10 GB" from a bogus total.
     */
    private fun parseQuotaNumber(text: String?): Long? {
        if (text.isNullOrBlank()) return null
        val value = text.toLongOrNull() ?: return null
        return if (value < 0L) null else value
    }

    private companion object {
        const val UPLOAD_COPY_BUFFER = 64 * 1024
        const val QUOTA_PROPFIND_BODY = """<?xml version="1.0" encoding="utf-8" ?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:quota-available-bytes/>
    <D:quota-used-bytes/>
  </D:prop>
</D:propfind>"""
    }
}

/** Same status-code scrape used by [toWebDavException]; kept package-visible for the list() 404 path. */
internal fun httpCodeFromMessage(message: String?): Int? =
    message?.let { Regex("""\b(4\d\d|5\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
