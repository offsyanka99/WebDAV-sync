package org.vovchenko.webdavsync.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DavHrefTest {

    @Test
    fun `normalizePath decodes spaces and strips trailing slash`() {
        assertEquals(
            "/dav.php/files/yurik/test 2",
            DavHref.normalizePath("https://example.com/dav.php/files/yurik/test%202/"),
        )
        assertEquals(
            "/dav.php/files/yurik/test 2",
            DavHref.normalizePath("/dav.php/files/yurik/test 2"),
        )
    }

    @Test
    fun `isSelf matches encoded request to decoded href`() {
        val listed = "https://my-cloud.example/dav.php/files/yurik/test%202/"
        assertTrue(DavHref.isSelf("/dav.php/files/yurik/test 2/", listed))
        assertTrue(DavHref.isSelf("/dav.php/files/yurik/test%202", listed))
        assertFalse(DavHref.isSelf("/dav.php/files/yurik/test 2/photo.jpg", listed))
        assertFalse(DavHref.isSelf("/dav.php/files/yurik/other", listed))
    }

    @Test
    fun `childName returns decoded last segment`() {
        assertEquals("test 1", DavHref.childName("/dav.php/files/yurik/test%201/"))
        assertEquals("photo.jpg", DavHref.childName("photo.jpg"))
    }
}
