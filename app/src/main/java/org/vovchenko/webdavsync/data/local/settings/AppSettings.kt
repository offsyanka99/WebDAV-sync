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
    val syncImmediatelyOnLocalChange: Boolean = true,
    val onlyWhileCharging: Boolean = false,
    val retryAttempts: Int = 3,
    val retryWaitMinutes: Int = 1,
    val batteryOptimizationDisabled: Boolean = false,
    val autoStartOnBoot: Boolean = false,
    val diagnosticLogEnabled: Boolean = false,
)
