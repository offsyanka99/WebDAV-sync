package org.vovchenko.webdavsync.data.remote

import org.vovchenko.webdavsync.data.remote.push.PushCapability
import org.vovchenko.webdavsync.data.remote.push.PushRegistrationResult
import org.vovchenko.webdavsync.data.remote.push.PushSubscriptionRequest
import java.io.Closeable
import java.io.InputStream

/**
 * Thin abstraction over the WebDAV protocol operations the sync engine needs (plan Phase 2).
 * Implemented by [SardineWebDavClient]; kept generic (streams, not `java.io.File`) so Phase 3's
 * SAF-backed local files can be bridged without a WebDavClient rework.
 */
interface WebDavClient : Closeable {
    /** Verifies the connection (and credentials/trust) work, without assuming any path exists yet. */
    suspend fun testConnection(): Result<Unit>

    suspend fun list(remotePath: String): Result<List<WebDavResource>>

    /**
     * Uploads a file. [openContent] is invoked for each body write (OkHttp/Digest may call
     * `writeTo` more than once) — each call must return a **fresh** stream from the start.
     * [contentLength] is required so PUT can set Content-Length without staging the whole file.
     */
    /**
     * Uploads a file. The success value is the response ETag when the server sends one.
     * [contentLength] may be 0 for an empty file.
     */
    suspend fun upload(
        remotePath: String,
        contentType: String,
        contentLength: Long,
        openContent: () -> InputStream,
    ): Result<String?>

    suspend fun download(remotePath: String): Result<InputStream>

    suspend fun delete(remotePath: String): Result<Unit>

    suspend fun createDirectory(remotePath: String): Result<Unit>

    /** WebDAV MOVE. [fromRemotePath] and [toRemotePath] are account-relative. */
    suspend fun move(fromRemotePath: String, toRemotePath: String): Result<Unit>

    suspend fun exists(remotePath: String): Result<Boolean>

    suspend fun getQuota(): Result<WebDavQuota>

    /**
     * Depth-0 PROPFIND of the WebDAV-Push properties on a collection. Success with null means the
     * collection does not offer push; a missing collection fails with [WebDavException.NotFound].
     */
    suspend fun discoverPush(remotePath: String): Result<PushCapability?>

    /** POSTs a `<push-register>` to the collection. Protocol outcomes are in the result value. */
    suspend fun registerPush(remotePath: String, request: PushSubscriptionRequest): Result<PushRegistrationResult>

    /**
     * DELETEs a registration URL. Fails without sending anything when [registrationUrl] is not on
     * the account's origin. `404` counts as success.
     */
    suspend fun unregisterPush(registrationUrl: String): Result<Unit>

    /** Registration URLs sent as `Push-Dont-Notify` on mutating requests; empty clears it. */
    fun setPushDontNotify(registrationUrls: List<String>)
}
