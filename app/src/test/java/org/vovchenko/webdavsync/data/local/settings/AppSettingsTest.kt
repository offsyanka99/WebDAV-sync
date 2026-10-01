package org.vovchenko.webdavsync.data.local.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSettingsTest {

    @Test
    fun `new-install defaults are energy-conservative`() {
        val defaults = AppSettings()
        assertFalse(defaults.syncImmediatelyOnLocalChange)
        assertFalse(defaults.syncEvenWhenBatteryLow)
        assertFalse(defaults.onlyWhileCharging)
        assertFalse(defaults.wifiOnly)
        assertTrue(defaults.autoSyncEnabled)
        assertEquals(60, defaults.autoSyncIntervalMinutes)
    }

    @Test
    fun `battery saver profile sets wifi charging interval and turns instant off`() {
        val aggressive = AppSettings(
            wifiOnly = false,
            onlyWhileCharging = false,
            autoSyncIntervalMinutes = 15,
            syncImmediatelyOnLocalChange = true,
            syncEvenWhenBatteryLow = true,
        )
        val saver = aggressive.applyBatterySaverProfile()
        assertTrue(saver.wifiOnly)
        assertTrue(saver.onlyWhileCharging)
        assertTrue(saver.autoSyncEnabled)
        assertEquals(AppSettings.BATTERY_SAVER_INTERVAL_MINUTES, saver.autoSyncIntervalMinutes)
        assertFalse(saver.syncImmediatelyOnLocalChange)
        assertFalse(saver.syncEvenWhenBatteryLow)
        assertTrue(saver.isBatterySaverProfile())
        assertFalse(aggressive.isBatterySaverProfile())
    }

    @Test
    fun `instant download is off by default and untouched by battery saver and clamping`() {
        assertFalse(AppSettings().instantDownloadEnabled)
        val on = AppSettings(instantDownloadEnabled = true)
        assertTrue(on.applyBatterySaverProfile().instantDownloadEnabled)
        assertTrue(on.clamped().instantDownloadEnabled)
        assertTrue(on.applyBatterySaverProfile().isBatterySaverProfile())
    }
}
