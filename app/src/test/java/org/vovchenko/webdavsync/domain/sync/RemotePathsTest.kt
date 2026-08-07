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
}
