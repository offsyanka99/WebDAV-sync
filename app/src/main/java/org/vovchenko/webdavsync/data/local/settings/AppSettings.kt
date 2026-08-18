package org.vovchenko.webdavsync.data.local.settings

/** Global settings (Settings > Synchronization / Settings screens, requirements.md). Defaults per spec. */
data class AppSettings(
    val uploadSizeLimitBytes: Long? = null, // null = no limit
    val downloadSizeLimitBytes: Long? = null, // null = no limit
    val warnOnMobileNetwork: Boolean = false,
    val wifiOnly: Boolean = false,
    val allowParallelTransfers: Boolean = true,
    val autoSyncEnabled: Boolean = true,
    val autoSyncIntervalMinutes: Int = 60,
    /**
     * Default **false** for new installs (key absent in DataStore). Existing installs that already
     * persisted this key keep their stored value.
     */
    val syncImmediatelyOnLocalChange: Boolean = false,
    val onlyWhileCharging: Boolean = false,
    /** When false (default), periodic WorkManager waits until the battery is not low. */
    val syncEvenWhenBatteryLow: Boolean = false,
    val retryAttempts: Int = 3,
    val retryWaitMinutes: Int = 1,
    val batteryOptimizationDisabled: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val diagnosticLogEnabled: Boolean = false,
) {
    fun applyBatterySaverProfile(): AppSettings = copy(
        wifiOnly = true,
        onlyWhileCharging = true,
        autoSyncEnabled = true,
        autoSyncIntervalMinutes = BATTERY_SAVER_INTERVAL_MINUTES,
        syncImmediatelyOnLocalChange = false,
        syncEvenWhenBatteryLow = false,
    )

    fun isBatterySaverProfile(): Boolean =
        wifiOnly &&
            onlyWhileCharging &&
            autoSyncEnabled &&
            autoSyncIntervalMinutes == BATTERY_SAVER_INTERVAL_MINUTES &&
            !syncImmediatelyOnLocalChange &&
            !syncEvenWhenBatteryLow

    companion object {
        /** 3 hours — inside the planned 2–6h battery-saver window. */
        const val BATTERY_SAVER_INTERVAL_MINUTES = 180
    }
}
