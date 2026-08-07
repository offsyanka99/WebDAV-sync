package org.vovchenko.webdavsync.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WebDavPathSafetyTest {

    @Test
    fun `root slash sanitizes to empty relative path`() {
        assertEquals("", WebDavPathSafety.sanitize("/"))
        assertEquals("", WebDavPathSafety.sanitize(""))
        assertEquals("", WebDavPathSafety.sanitize("///"))
    }

    @Test
    fun `normal path keeps segments`() {
        assertEquals("Photos/2024", WebDavPathSafety.sanitize("/Photos/2024/"))
        assertEquals("Test 1", WebDavPathSafety.sanitize("Test 1"))
    }

    @Test
    fun `rejects parent segments`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebDavPathSafety.sanitize("../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebDavPathSafety.sanitize("a/./b")
        }
    }
}
