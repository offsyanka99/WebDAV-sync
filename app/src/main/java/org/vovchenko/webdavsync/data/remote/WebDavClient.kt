package org.vovchenko.webdavsync.data.remote

import java.io.InputStream

/**
 * Thin abstraction over the WebDAV protocol operations the sync engine needs (plan Phase 2).
 * Implemented by [SardineWebDavClient]; kept generic (streams, not `java.io.File`) so Phase 3's
 * SAF-backed local files can be bridged without a WebDavClient rework.
 */
interface WebDavClient {
    /** Verifies the connection (and credentials/trust) work, without assuming any path exists yet. */
    suspend fun testConnection(): Result<Unit>

    suspend fun list(remotePath: String): Result<List<WebDavResource>>

    suspend fun upload(remotePath: String, contentType: String, content: InputStream): Result<Unit>

    suspend fun download(remotePath: String): Result<InputStream>

    suspend fun delete(remotePath: String): Result<Unit>

    suspend fun createDirectory(remotePath: String): Result<Unit>

    suspend fun exists(remotePath: String): Result<Boolean>

    suspend fun getQuota(): Result<WebDavQuota>
}
