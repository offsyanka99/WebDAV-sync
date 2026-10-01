package org.vovchenko.webdavsync.ui.settings

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.vovchenko.webdavsync.data.local.settings.AppSettings

/** Robolectric supplies a real org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BackupSettingsJsonTest {

    @Test
    fun `instant download round-trips through backup JSON`() {
        val json = AppSettings(instantDownloadEnabled = true).toJson()
        assertTrue(json.getBoolean("instantDownloadEnabled"))
        assertTrue(JSONObject(json.toString()).toAppSettings(AppSettings()).instantDownloadEnabled)
    }

    @Test
    fun `missing key keeps the current value`() {
        val json = AppSettings().toJson().apply { remove("instantDownloadEnabled") }
        assertTrue(json.toAppSettings(AppSettings(instantDownloadEnabled = true)).instantDownloadEnabled)
        assertFalse(json.toAppSettings(AppSettings(instantDownloadEnabled = false)).instantDownloadEnabled)
    }

    @Test
    fun `backup JSON holds no push secrets`() {
        val text = AppSettings(instantDownloadEnabled = true).toJson().toString()
        listOf("endpoint", "auth", "secret", "registration", "topic").forEach { word ->
            assertFalse(word, text.contains(word, ignoreCase = true))
        }
    }
}
