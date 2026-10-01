package org.vovchenko.webdavsync.sync.control

import org.junit.Assert.assertEquals
import org.junit.Test
import org.vovchenko.webdavsync.data.local.settings.AppSettings

class UserSyncTest {

    @Test
    fun `wifi only on cellular asks before using mobile data`() {
        val settings = AppSettings(wifiOnly = true, warnOnMobileNetwork = true)
        assertEquals(
            ManualSyncDecision.NeedsUnmeteredOverride,
            ManualSyncStarter.decide(settings, onCellularData = true),
        )
    }

    @Test
    fun `mobile warning applies when wifi only is off`() {
        val settings = AppSettings(wifiOnly = false, warnOnMobileNetwork = true)
        assertEquals(
            ManualSyncDecision.NeedsMobileDataConfirm,
            ManualSyncStarter.decide(settings, onCellularData = true),
        )
        assertEquals(
            ManualSyncDecision.Proceed,
            ManualSyncStarter.decide(settings, onCellularData = false),
        )
    }

    @Test
    fun `wait reasons are wifi and charging only`() {
        val blocked = AppSettings(wifiOnly = true, onlyWhileCharging = true)
        assertEquals(
            listOf(SyncWaitReason.WIFI, SyncWaitReason.CHARGING),
            manualSyncWaitReasons(blocked, onUnmeteredNetwork = false, charging = false, bypassUnmetered = false),
        )
        assertEquals(
            listOf(SyncWaitReason.CHARGING),
            manualSyncWaitReasons(blocked, onUnmeteredNetwork = false, charging = false, bypassUnmetered = true),
        )
        assertEquals(
            emptyList<SyncWaitReason>(),
            manualSyncWaitReasons(
                AppSettings(),
                onUnmeteredNetwork = false,
                charging = false,
                bypassUnmetered = false,
            ),
        )
    }

    @Test
    fun `waiting message names the constraint that still blocks`() {
        assertEquals(
            "Sync will start when you are on Wi-Fi and the device is charging.",
            SyncUserMessage.waiting(listOf(SyncWaitReason.WIFI, SyncWaitReason.CHARGING)),
        )
        assertEquals(
            "Sync will start when you are on Wi-Fi.",
            SyncUserMessage.waiting(listOf(SyncWaitReason.WIFI)),
        )
        assertEquals(
            "Sync will start when the device is charging.",
            SyncUserMessage.waiting(listOf(SyncWaitReason.CHARGING)),
        )
    }
}
