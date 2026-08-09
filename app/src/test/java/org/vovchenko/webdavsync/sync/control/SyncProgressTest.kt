package org.vovchenko.webdavsync.sync.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressTest {

    @Test
    fun `beginPass zeros counters and marks active`() {
        val p = SyncProgress()
        p.beginPass()
        p.recordUploaded()
        p.recordDownloaded(2)
        assertEquals(1, p.counts.value.uploaded)
        assertEquals(2, p.counts.value.downloaded)
        assertTrue(p.counts.value.active)

        p.beginPass()
        assertEquals(0, p.counts.value.uploaded)
        assertEquals(0, p.counts.value.downloaded)
        assertTrue(p.counts.value.active)
    }

    @Test
    fun `endPass clears active but keeps last counts until next begin`() {
        val p = SyncProgress()
        p.beginPass()
        p.recordUploaded(3)
        p.recordDeletedDevice(1)
        p.endPass()
        assertFalse(p.counts.value.active)
        assertEquals(3, p.counts.value.uploaded)
        assertEquals(1, p.counts.value.deletedDevice)
    }

    @Test
    fun `record ignored when not active`() {
        val p = SyncProgress()
        p.recordUploaded(5)
        assertEquals(0, p.counts.value.uploaded)
        assertFalse(p.counts.value.active)
    }
}
