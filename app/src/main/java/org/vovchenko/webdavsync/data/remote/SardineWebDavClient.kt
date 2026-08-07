package org.vovchenko.webdavsync.data.remote

import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val uploadCacheDir: File,
) : WebDavClient {

    private val sardine by lazy { OkHttpSardine(okHttpClient) }

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
        sardine.list(resolve("", asCollection = true))
        Unit
    }

    override suspend fun list(remotePath: String): Result<List<WebDavResource>> = runCatchingWebDav {
        val targetUrl = resolve(remotePath, asCollection = true)
        try {
            // Depth:1 PROPFIND always echoes the collection itself as the first multistatus
            // response. Filter it out using encoding-tolerant path comparison — a strict string
            // match fails when the server returns `test 2` and we requested `test%202`, which
            // previously made the scanner recurse into `test 2/test 2` and 404.
            sardine.list(targetUrl)
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

    override suspend fun upload(remotePath: String, contentType: String, content: InputStream): Result<Unit> =
        runCatchingWebDav {
            // P0: never wrap a one-shot SAF InputStream directly in RequestBody.
            // OkHttp may call writeTo() more than once (Digest 401 challenge/retry, internal
            // retries). The first pass drained the stream; the second sent 0 bytes → empty files
            // on the server, then two-way sync could download that empty file back and wipe local.
            // Copy to a cache file once so the body is re-readable and has a known Content-Length.
            if (!uploadCacheDir.exists()) uploadCacheDir.mkdirs()
            val temp = File.createTempFile("webdav-upload-", ".bin", uploadCacheDir)
            try {
                content.use { input ->
                    temp.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                val length = temp.length()
                if (length == 0L) {
                    throw java.io.IOException("Refusing to upload empty body for $remotePath (source produced 0 bytes)")
                }
                val body = temp.asRequestBody(contentType.toMediaTypeOrNull())
                val request = Request.Builder()
                    .url(resolve(remotePath, asCollection = false))
                    .put(body)
                    .header("Content-Length", length.toString())
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw java.io.IOException("PUT failed: HTTP ${response.code} for $remotePath (${length} bytes)")
                    }
                }
            } finally {
                temp.delete()
            }
        }

    override suspend fun download(remotePath: String): Result<InputStream> = runCatchingWebDav {
        sardine.get(resolve(remotePath, asCollection = false))
    }

    override suspend fun delete(remotePath: String): Result<Unit> = runCatchingWebDav {
        // Prefer no trailing slash for deletes; works for both files and most collection DELETEs.
        sardine.delete(resolve(remotePath, asCollection = false))
    }

    override suspend fun createDirectory(remotePath: String): Result<Unit> = runCatchingWebDav {
        sardine.createDirectory(resolve(remotePath, asCollection = true))
    }

    override suspend fun exists(remotePath: String): Result<Boolean> = runCatchingWebDav {
        // Collections are the usual exists() target; trailing slash avoids false 404s.
        sardine.exists(resolve(remotePath, asCollection = true))
    }

    override suspend fun getQuota(): Result<WebDavQuota> = runCatchingWebDav {
        val requestBody = QUOTA_PROPFIND_BODY.toRequestBody("text/xml".toMediaTypeOrNull())
        val request = Request.Builder()
            .url(resolve("", asCollection = true))
            .method("PROPFIND", requestBody)
            .header("Depth", "0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
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
