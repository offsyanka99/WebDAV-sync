package org.vovchenko.webdavsync.data.local.saf

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LocalTreeFingerprintTest {

    @Test
    fun `pack is stable and changes when children change`() {
        val a = LocalTreeFingerprint.pack(
            childCount = 3,
            totalSize = 1000L,
            rootMtime = 10L,
            maxChildMtime = 20L,
            idHash = 42L,
        )
        val same = LocalTreeFingerprint.pack(
            childCount = 3,
            totalSize = 1000L,
            rootMtime = 10L,
            maxChildMtime = 20L,
            idHash = 42L,
        )
        val added = LocalTreeFingerprint.pack(
            childCount = 4,
            totalSize = 1000L,
            rootMtime = 10L,
            maxChildMtime = 20L,
            idHash = 42L,
        )
        assertEquals(a, same)
        assertNotEquals(a, added)
    }

    @Test
    fun `invalid tree uri does not throw and returns 0`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val fingerprint = LocalTreeFingerprint(context)
        assertEquals(0L, fingerprint.of(Uri.parse("not-a-tree")))
        assertEquals(0L, fingerprint.of(Uri.parse("content://missing/tree/primary%3ANone")))
    }
}
