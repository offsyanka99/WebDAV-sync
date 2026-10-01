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

    @Test
    fun `redacts push registration tokens`() {
        val token = "Abc_def-123".repeat(4)
        val out = DiagnosticRedactor.redact("Registered https://dav.example.com/dav.php/push-subscriptions/$token until 7 Oct")
        assertFalse(out.contains(token))
        assertTrue(out.contains("https://dav.example.com/dav.php/push-subscriptions/*** until 7 Oct"))
    }

    @Test
    fun `redacts push subscription secrets in XML`() {
        val input = """<P:push-resource>https://ntfy.sh/upXyZ</P:push-resource>""" +
            """<P:subscription-public-key type="p256dh">BPubKeyValue</P:subscription-public-key>""" +
            """<auth-secret>s3cr3tAuth</auth-secret>"""
        val out = DiagnosticRedactor.redact(input)
        assertFalse(out.contains("upXyZ"))
        assertFalse(out.contains("BPubKeyValue"))
        assertFalse(out.contains("s3cr3tAuth"))
        assertTrue(out.contains("""<P:push-resource>***</P:push-resource>"""))
        assertTrue(out.contains("""<P:subscription-public-key type="p256dh">***</P:subscription-public-key>"""))
    }

    @Test
    fun `redacts Push-Dont-Notify header values`() {
        val out = DiagnosticRedactor.redact("PUT a.txt Push-Dont-Notify: \"https://dav.example/r/1\", \"https://dav.example/r/2\"")
        assertFalse(out.contains("dav.example/r/"))
        assertTrue(out.endsWith("Push-Dont-Notify: ***"))
    }
}
