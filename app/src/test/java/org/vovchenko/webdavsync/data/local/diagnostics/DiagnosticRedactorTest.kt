package org.vovchenko.webdavsync.data.local.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticRedactorTest {

    @Test
    fun `redacts Authorization Basic header`() {
        val input = "Request failed Authorization: Basic dXNlcjpwYXNz more"
        val out = DiagnosticRedactor.redact(input)
        assertFalse(out.contains("dXNlcjpwYXNz"))
        assertTrue(out.contains("Authorization: Basic ***") || out.contains("Authorization: Basic ***".lowercase()))
    }

    @Test
    fun `redacts password field`() {
        val input = "WebDavCredentials(username=alice, password=s3cret!)"
        val out = DiagnosticRedactor.redact(input)
        assertFalse(out.contains("s3cret"))
        assertTrue(out.contains("password="))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `redacts userinfo in url`() {
        val input = "Connecting to https://alice:hunter2@example.com/dav"
        val out = DiagnosticRedactor.redact(input)
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("https://alice:***@example.com/dav"))
    }

    @Test
    fun `leaves innocent messages unchanged`() {
        val input = "Sync start pair='Photos' id=3 method=TWO_WAY"
        assertEquals(input, DiagnosticRedactor.redact(input))
    }
}
