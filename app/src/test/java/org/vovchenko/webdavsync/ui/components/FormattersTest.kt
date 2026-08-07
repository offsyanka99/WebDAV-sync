package org.vovchenko.webdavsync.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `duration rounds sub-second work to at least 0s and formats minutes`() {
        assertEquals("0s", Formatters.duration(0L))
        assertEquals("1s", Formatters.duration(1_000L))
        assertEquals("1m 0s", Formatters.duration(60_000L))
        assertEquals("1m 5s", Formatters.duration(65_000L))
    }

    @Test
    fun `bytes keeps two decimals for GB so 9_97 is not rounded to 10_0`() {
        val almostTenGb = (10L * 1024 * 1024 * 1024) - (26L * 1024 * 1024) // ~9.97 GiB free
        val text = Formatters.bytes(almostTenGb)
        assertTrue(text.contains("GB"))
        assertFalse("should not round 9.97 GiB up to 10.0 GB: $text", text.startsWith("10.0"))
    }

    @Test
    fun `storageSummary is used of total only`() {
        val total = 10L * 1024 * 1024 * 1024
        val used = (4.2 * 1024 * 1024).toLong()
        val free = total - used
        val text = Formatters.storageSummary(available = free, used = null, total = total)
        assertEquals("4.2 MB of 10.00 GB", text)
        assertFalse(text.contains("free"))
        assertFalse(text.contains("used ·"))
    }
}
