package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class BaseUrlNormalizerTest {

    @Test
    fun `normalizes host case and trailing slash`() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav",
            BaseUrlNormalizer.normalize("HTTPS://Cloud.Example.com/remote.php/dav/"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects http`() {
        BaseUrlNormalizer.normalize("http://cloud.example.com/dav")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects userinfo`() {
        BaseUrlNormalizer.normalize("https://user:secret@cloud.example.com/dav")
    }
}
