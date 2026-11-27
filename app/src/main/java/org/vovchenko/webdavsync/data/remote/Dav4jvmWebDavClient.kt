package org.vovchenko.webdavsync.data.remote

import at.bitfire.dav4jvm.ktor.DavResource
import at.bitfire.dav4jvm.ktor.exception.HttpException
import at.bitfire.dav4jvm.ktor.responses
import at.bitfire.dav4jvm.property.webdav.GetContentLength
import at.bitfire.dav4jvm.property.webdav.GetETag
import at.bitfire.dav4jvm.property.webdav.GetLastModified
import at.bitfire.dav4jvm.property.webdav.QuotaAvailableBytes
import at.bitfire.dav4jvm.property.webdav.QuotaUsedBytes
import at.bitfire.dav4jvm.property.webdav.ResourceType
import at.bitfire.dav4jvm.property.webdav.WebDAV
import kotlinx.coroutines.flow.firstOrNull
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.http.withCharset
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.io.readByteArray
import java.io.FilterInputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.vovchenko.webdavsync.data.remote.push.PushCapability
import org.vovchenko.webdavsync.data.remote.push.PushDontNotify
import org.vovchenko.webdavsync.data.remote.push.PushHeaders
import org.vovchenko.webdavsync.data.remote.push.PushRegistrationResult
import org.vovchenko.webdavsync.data.remote.push.PushSubscriptionRequest
import org.vovchenko.webdavsync.data.remote.push.WebDavPushXml
import org.vovchenko.webdavsync.data.remote.push.sameOrigin
import java.io.InputStream

/**
 * [WebDavClient] on dav4jvm and the account's OkHttp client.
 */
class Dav4jvmWebDavClient(
    private val baseUrl: String,
    /** Must be the holder installed on [accountClient]. */
    private val pushDontNotify: PushDontNotify = PushDontNotify(),
    private val accountClient: HttpClient,
) : WebDavClient {

    override fun close() {
        WebDavClientFactory.shutdown(accountClient)
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

    override suspend fun testConnection(): Result<Unit> =
        runCatchingWebDav { listCollection(resolve("", asCollection = true)) }.map { }

    override suspend fun list(remotePath: String): Result<List<WebDavResource>> = runCatchingWebDav {
        val targetUrl = resolve(remotePath, asCollection = true)
        try {
            listCollection(targetUrl)
        } catch (e: HttpException) {
            // Surface the URL that 404'd so diagnostic logs are actionable.
            if (e.statusCode == 404) throw WebDavException.NotFound(targetUrl)
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
            // Each readFrom opens the SAF stream again. A Digest 401 retry must not stage the
            // file in a cache; that staging hung multi-100MB uploads.
            val parsedType = runCatching { ContentType.parse(contentType) }.getOrNull()
            val body = object : OutgoingContent.ReadChannelContent() {
                override val contentType: ContentType? = parsedType
                override val contentLength: Long = contentLength
                override fun readFrom(): ByteReadChannel = openContent().toByteReadChannel()
            }
            val url = resolve(remotePath, asCollection = false)
            DavResource(accountHttp(), Url(url)).put(body) { response ->
                response.headers[HttpHeaders.ETag]
            }
        }

    override suspend fun download(remotePath: String): Result<InputStream> = runCatchingWebDav {
        val url = resolve(remotePath, asCollection = false)
        val client = accountHttp()
        val pipeIn = PipedInputStream(DOWNLOAD_PIPE_BYTES)
        val pipeOut = PipedOutputStream(pipeIn)
        val failure = AtomicReference<Throwable>()
        val parent = client.coroutineContext[Job]
        val job = CoroutineScope(Dispatchers.IO + SupervisorJob(parent)).launch {
            try {
                DavResource(client, Url(url)).get { response ->
                    response.bodyAsChannel().toInputStream().use { input -> input.copyTo(pipeOut) }
                }
            } catch (thrown: Throwable) {
                failure.set(thrown)
                throw thrown
            } finally {
                pipeOut.close()
            }
        }
        object : FilterInputStream(pipeIn) {
            override fun read(): Int = checkFailure(super.read())
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                checkFailure(super.read(buffer, offset, length))

            private fun checkFailure(count: Int): Int {
                failure.get()?.let { throw java.io.IOException(it) }
                return count
            }

            override fun close() {
                job.cancel()
                super.close()
            }
        }
    }

    override suspend fun delete(remotePath: String): Result<Unit> = runCatchingWebDav {
        // Prefer no trailing slash for deletes; works for both files and most collection DELETEs.
        val url = resolve(remotePath, asCollection = false)
        DavResource(accountHttp(), Url(url)).delete { }
    }

    override suspend fun createDirectory(remotePath: String): Result<Unit> = runCatchingWebDav {
        // resolve already ends the collection URL with one slash. mkCol appends a slash only
        // when the URL does not already end in one.
        val url = resolve(remotePath, asCollection = true)
        DavResource(accountHttp(), Url(url)).mkCol(null) { }
    }

    override suspend fun move(fromRemotePath: String, toRemotePath: String): Result<Unit> = runCatchingWebDav {
        val source = resolve(fromRemotePath, asCollection = false)
        val destination = resolve(toRemotePath, asCollection = false)
        // overwrite true omits the header, and the RFC default of an omitted header is T.
        DavResource(accountHttp(), Url(source)).move(Url(destination), overwrite = false) { }
    }

    override suspend fun exists(remotePath: String): Result<Boolean> = runCatchingWebDav {
        // Collections are the usual exists() target. HEAD, not PROPFIND. A 404 is absence.
        val url = resolve(remotePath, asCollection = true)
        try {
            DavResource(accountHttp(), Url(url)).head { }
            true
        } catch (e: HttpException) {
            if (e.statusCode == 404) false else throw e
        }
    }

    /**
     * Depth-1 PROPFIND of the four live properties the scanner stores. The collection's own
     * href is dropped. A name of `.` or `..`, or an href on another host, is not returned.
     * A 404 propstat for one property does not drop the child.
     */
    private suspend fun listCollection(targetUrl: String): List<WebDavResource> {
        val requested = Url(targetUrl)
        return DavResource(accountHttp(), requested)
            .propfind(
                1,
                WebDAV.ResourceType,
                WebDAV.GetContentLength,
                WebDAV.GetLastModified,
                WebDAV.GetETag,
            )
            .responses()
            .toList()
            .mapNotNull { response ->
                val href = response.href
                if (!response.isSuccess()) return@mapNotNull null
                if (href.protocol != requested.protocol || href.host != requested.host || href.port != requested.port) {
                    return@mapNotNull null
                }
                val hrefText = href.toString()
                if (DavHref.isSelf(hrefText, targetUrl)) return@mapNotNull null
                val parentPath = DavHref.normalizePath(targetUrl)
                val childPath = DavHref.normalizePath(hrefText)
                // A `..` segment is resolved by the URL parser before we see it, so the href
                // is the parent. Only a single path segment under this collection is a child.
                if (!childPath.startsWith("$parentPath/")) return@mapNotNull null
                val name = childPath.removePrefix("$parentPath/")
                if (name.isEmpty() || name.contains('/') || name == "." || name == "..") return@mapNotNull null
                val types = response[ResourceType::class.java]?.types.orEmpty()
                WebDavResource(
                    path = hrefText,
                    isDirectory = WebDAV.Collection in types,
                    sizeBytes = response[GetContentLength::class.java]?.contentLength ?: 0L,
                    lastModifiedEpochMillis = response[GetLastModified::class.java]?.lastModified?.toEpochMilli(),
                    etag = response[GetETag::class.java]?.rawETag,
                )
            }
    }

    private fun accountHttp(): HttpClient = accountClient

    override suspend fun getQuota(): Result<WebDavQuota> = runCatchingWebDav {
        val targetUrl = resolve("", asCollection = true)
        val response = DavResource(accountHttp(), Url(targetUrl))
            .propfind(0, WebDAV.QuotaAvailableBytes, WebDAV.QuotaUsedBytes)
            .responses()
            .firstOrNull { it.isSuccess() }
        WebDavQuota(
            availableBytes = response?.get(QuotaAvailableBytes::class.java)?.quotaAvailableBytes.knownQuota(),
            usedBytes = response?.get(QuotaUsedBytes::class.java)?.quotaUsedBytes.knownQuota(),
        )
    }

    override suspend fun discoverPush(remotePath: String): Result<PushCapability?> = runCatchingWebDav {
        val url = resolve(remotePath, asCollection = true)
        accountHttp().prepareRequest(url) {
            method = HttpMethod("PROPFIND")
            header("Depth", "0")
            contentType(ContentType.Text.Xml.withCharset(Charsets.UTF_8))
            setBody(WebDavPushXml.DISCOVERY_BODY)
        }.execute { response ->
            when (response.status.value) {
                207 -> readCapped(response, WebDavPushXml.MAX_MULTISTATUS_BYTES)?.let(WebDavPushXml::parseCapability)
                401, 403 -> throw WebDavException.AuthenticationFailed()
                404 -> throw WebDavException.NotFound(url)
                in 200..299 -> null
                else -> throw WebDavException.ServerError(response.status.value)
            }
        }
    }

    override suspend fun registerPush(
        remotePath: String,
        request: PushSubscriptionRequest,
    ): Result<PushRegistrationResult> = runCatchingWebDav {
        val url = resolve(remotePath, asCollection = true)
        accountHttp().prepareRequest(url) {
            method = HttpMethod.Post
            contentType(ContentType.Application.Xml.withCharset(Charsets.UTF_8))
            setBody(WebDavPushXml.registerBody(request))
        }.execute { response ->
            val now = System.currentTimeMillis()
            when (val code = response.status.value) {
                200, 201, 204 -> {
                    val location = response.headers[HttpHeaders.Location]?.takeIf(PushHeaders::isValidRegistrationUrl)
                    if (location == null) {
                        PushRegistrationResult.Failed(code, PushRegistrationResult.NO_LOCATION)
                    } else {
                        val expiresAt = PushHeaders.parseHttpDate(response.headers[HttpHeaders.Expires])
                            ?.takeIf { it > now }
                            ?: (now + MISSING_EXPIRES_FALLBACK_MS)
                        PushRegistrationResult.Registered(location, expiresAt)
                    }
                }
                401 -> throw WebDavException.AuthenticationFailed()
                403 -> {
                    val condition = readCapped(response, WebDavPushXml.MAX_ERROR_BYTES)
                        ?.let(WebDavPushXml::parsePrecondition)
                    when (condition) {
                        "push-not-available", "no-trigger-supported", "no-supported-trigger" ->
                            PushRegistrationResult.Unsupported(condition)
                        "invalid-subscription" -> PushRegistrationResult.InvalidSubscription
                        else -> PushRegistrationResult.Failed(403, "Forbidden")
                    }
                }
                404 -> throw WebDavException.NotFound(url)
                429 -> PushRegistrationResult.RateLimited(
                    PushHeaders.parseRetryAfterSeconds(response.headers[HttpHeaders.RetryAfter], now),
                )
                else -> PushRegistrationResult.Failed(code, "HTTP $code")
            }
        }
    }

    override suspend fun unregisterPush(registrationUrl: String): Result<Unit> {
        val target = registrationUrl.takeIf(PushHeaders::isHeaderSafeUrl)?.toHttpUrlOrNull()
        val base = baseUrl.toHttpUrlOrNull()
        if (target == null || base == null || !sameOrigin(target, base)) {
            return Result.failure(WebDavException.ForeignOrigin())
        }
        return runCatchingWebDav {
            accountHttp().prepareRequest(target.toString()) {
                method = HttpMethod.Delete
            }.execute { response ->
                when (response.status.value) {
                    200, 204, 404 -> Unit
                    401, 403 -> throw WebDavException.AuthenticationFailed()
                    else -> throw WebDavException.ServerError(response.status.value)
                }
            }
        }
    }

    override fun setPushDontNotify(registrationUrls: List<String>) {
        pushDontNotify.set(registrationUrls)
    }

    /** Reads at most [maxBytes] of the body; null when it is larger. */
    private suspend fun readCapped(response: HttpResponse, maxBytes: Int): String? {
        val bytes = response.bodyAsChannel().readBuffer(maxBytes.toLong() + 1).readByteArray()
        if (bytes.size > maxBytes) return null
        return bytes.decodeToString()
    }

    /** RFC 4331: −1 unknown, −2 not determined, −3 unlimited. An omitted property is also missing. */
    private fun Long?.knownQuota(): Long? = this?.takeIf { it >= 0L }

    private companion object {
        const val DOWNLOAD_PIPE_BYTES = 64 * 1024
        /** Renew soon when the server omits or garbles `Expires`. */
        const val MISSING_EXPIRES_FALLBACK_MS = 24L * 60 * 60 * 1000
    }
}

/** Same status-code scrape used by [toWebDavException]; kept package-visible for the list() 404 path. */
internal fun httpCodeFromMessage(message: String?): Int? =
    message?.let { Regex("""\b(4\d\d|5\d\d)\b""").find(it)?.groupValues?.get(1)?.toIntOrNull() }
