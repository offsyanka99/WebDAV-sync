package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class RemotePathsTest {

    @Test
    fun `join under account root`() {
        assertEquals("Test 1", RemotePaths.join("/", "Test 1"))
        assertEquals("Test 1", RemotePaths.join("", "Test 1"))
        assertEquals("Test 1", RemotePaths.join("///", "Test 1"))
    }

    @Test
    fun `join under nested remote root`() {
        assertEquals("Webdavsync/Test 1", RemotePaths.join("/Webdavsync", "Test 1"))
        assertEquals("Webdavsync/Test 1", RemotePaths.join("Webdavsync/", "Test 1"))
    }

    @Test
    fun `joinRelative for folder-pair local and remote trees`() {
        assertEquals("Photos", org.vovchenko.webdavsync.util.RelativePaths.joinRelative("", "Photos"))
        assertEquals("Photos/a.jpg", org.vovchenko.webdavsync.util.RelativePaths.joinRelative("Photos", "a.jpg"))
        // RemotePaths keeps a thin alias for domain call sites.
        assertEquals("Photos/a.jpg", RemotePaths.joinRelative("Photos", "a.jpg"))
    }
}
