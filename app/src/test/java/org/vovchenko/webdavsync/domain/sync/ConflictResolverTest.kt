package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import org.vovchenko.webdavsync.domain.model.SyncAction
import java.util.Calendar
import java.util.TimeZone

class ConflictResolverTest {

    private val resolver = ConflictResolver()

    private fun millisFor(year: Int, month: Int, day: Int): Long {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.clear()
        calendar.set(year, month - 1, day)
        return calendar.timeInMillis
    }

    @Test
    fun `appends conflicted copy suffix before the extension`() {
        val result = resolver.conflictedCopyPath(
            relativePath = "Photos/beach.jpg",
            losingSide = SyncAction.Side.LOCAL,
            nowMillis = millisFor(2026, 8, 6),
        )
        assertEquals("Photos/beach (conflicted copy, device, 2026-08-06).jpg", result)
    }

    @Test
    fun `handles files without an extension`() {
        val result = resolver.conflictedCopyPath(
            relativePath = "notes",
            losingSide = SyncAction.Side.REMOTE,
            nowMillis = millisFor(2026, 8, 6),
        )
        assertEquals("notes (conflicted copy, cloud, 2026-08-06)", result)
    }

    @Test
    fun `handles root-level files`() {
        val result = resolver.conflictedCopyPath(
            relativePath = "readme.txt",
            losingSide = SyncAction.Side.LOCAL,
            nowMillis = millisFor(2026, 1, 1),
        )
        assertEquals("readme (conflicted copy, device, 2026-01-01).txt", result)
    }
}
