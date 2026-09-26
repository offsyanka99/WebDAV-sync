package org.vovchenko.webdavsync.data.remote

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
}
