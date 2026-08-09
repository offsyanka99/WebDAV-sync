package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncStatusDisplayTest {

    @Test
    fun `running worker always shows syncing even if last status is ERROR`() {
        val r = SyncStatusDisplay.resolve(lastSyncStatus = "ERROR", syncing = true)
        assertEquals(SyncStatusDisplay.Kind.SYNCING, r.kind)
        assertEquals(SyncStatusDisplay.TEXT_SYNCING, r.text)
    }

    @Test
    fun `idle after failed pass shows ERROR not syncing`() {
        val r = SyncStatusDisplay.resolve(lastSyncStatus = "ERROR", syncing = false)
        assertEquals(SyncStatusDisplay.Kind.ERROR, r.kind)
        assertEquals("ERROR", r.text)
    }

    @Test
    fun `idle after success shows OK`() {
        val r = SyncStatusDisplay.resolve(lastSyncStatus = "OK", syncing = false)
        assertEquals(SyncStatusDisplay.Kind.OK, r.kind)
        assertEquals("OK", r.text)
    }

    @Test
    fun `never synced shows Ready`() {
        val r = SyncStatusDisplay.resolve(lastSyncStatus = null, syncing = false)
        assertEquals(SyncStatusDisplay.Kind.READY, r.kind)
        assertEquals(SyncStatusDisplay.TEXT_READY, r.text)
    }
}
