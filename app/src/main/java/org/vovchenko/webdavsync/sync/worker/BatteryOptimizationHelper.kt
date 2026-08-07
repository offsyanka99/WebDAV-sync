package org.vovchenko.webdavsync.sync.worker

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import javax.inject.Inject
import javax.inject.Singleton

/** Battery-optimization exemption check + request intent (plan Phase 5; UI trigger is Phase 8's Settings screen). */
@Singleton
class BatteryOptimizationHelper @Inject constructor(
    private val context: Context,
) {
    fun isIgnoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java)
        return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
    }

    /**
     * System dialog / flow to **allow unrestricted background** (ignore battery optimizations).
     * On stock Android this is a yes/no dialog; some OEMs open the app battery screen instead —
     * the user must set Unrestricted/No restrictions there.
     */
    @SuppressLint("BatteryLife")
    fun createRequestExemptionIntent(): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * Opens the app's battery settings so the user can turn the exemption **off** again
     * (there is no public API to re-enable battery optimization programmatically).
     */
    fun createAppBatterySettingsIntent(): Intent {
        // Prefer the dedicated REQUEST intent's package-scoped UI when available; fall back to App info.
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    }
}
