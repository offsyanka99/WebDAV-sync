package org.vovchenko.webdavsync.data.local.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/** DataStore-backed store for [AppSettings] (plan §3 "Global settings", Phase 1). */
@Singleton
class SettingsDataStore @Inject constructor(
    context: Context,
) {
    private val dataStore = context.settingsDataStore

    val settings: Flow<AppSettings> = dataStore.data.map { prefs -> prefs.toAppSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { prefs ->
            val updated = transform(prefs.toAppSettings()).clamped()
            prefs.applyAppSettings(updated)
        }
    }

    private object Keys {
        val uploadSizeLimitBytes = longPreferencesKey("upload_size_limit_bytes")
        val downloadSizeLimitBytes = longPreferencesKey("download_size_limit_bytes")
        val warnOnMobileNetwork = booleanPreferencesKey("warn_on_mobile_network")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val allowParallelTransfers = booleanPreferencesKey("allow_parallel_transfers")
        val autoSyncEnabled = booleanPreferencesKey("auto_sync_enabled")
        val autoSyncIntervalMinutes = intPreferencesKey("auto_sync_interval_minutes")
        val syncImmediatelyOnLocalChange = booleanPreferencesKey("sync_immediately_on_local_change")
        val onlyWhileCharging = booleanPreferencesKey("only_while_charging")
        val syncEvenWhenBatteryLow = booleanPreferencesKey("sync_even_when_battery_low")
        val retryAttempts = intPreferencesKey("retry_attempts")
        val retryWaitMinutes = intPreferencesKey("retry_wait_minutes")
        val batteryOptimizationDisabled = booleanPreferencesKey("battery_optimization_disabled")
        val autoStartOnBoot = booleanPreferencesKey("auto_start_on_boot")
        val diagnosticLogEnabled = booleanPreferencesKey("diagnostic_log_enabled")
    }

    private fun Preferences.toAppSettings(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            uploadSizeLimitBytes = this[Keys.uploadSizeLimitBytes],
            downloadSizeLimitBytes = this[Keys.downloadSizeLimitBytes],
            warnOnMobileNetwork = this[Keys.warnOnMobileNetwork] ?: defaults.warnOnMobileNetwork,
            wifiOnly = this[Keys.wifiOnly] ?: defaults.wifiOnly,
            allowParallelTransfers = this[Keys.allowParallelTransfers] ?: defaults.allowParallelTransfers,
            autoSyncEnabled = this[Keys.autoSyncEnabled] ?: defaults.autoSyncEnabled,
            autoSyncIntervalMinutes = this[Keys.autoSyncIntervalMinutes] ?: defaults.autoSyncIntervalMinutes,
            syncImmediatelyOnLocalChange = this[Keys.syncImmediatelyOnLocalChange]
                ?: defaults.syncImmediatelyOnLocalChange,
            onlyWhileCharging = this[Keys.onlyWhileCharging] ?: defaults.onlyWhileCharging,
            syncEvenWhenBatteryLow = this[Keys.syncEvenWhenBatteryLow] ?: defaults.syncEvenWhenBatteryLow,
            retryAttempts = this[Keys.retryAttempts] ?: defaults.retryAttempts,
            retryWaitMinutes = this[Keys.retryWaitMinutes] ?: defaults.retryWaitMinutes,
            batteryOptimizationDisabled = this[Keys.batteryOptimizationDisabled]
                ?: defaults.batteryOptimizationDisabled,
            autoStartOnBoot = this[Keys.autoStartOnBoot] ?: defaults.autoStartOnBoot,
            diagnosticLogEnabled = this[Keys.diagnosticLogEnabled] ?: defaults.diagnosticLogEnabled,
        ).clamped()
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.applyAppSettings(settings: AppSettings) {
        if (settings.uploadSizeLimitBytes != null) {
            this[Keys.uploadSizeLimitBytes] = settings.uploadSizeLimitBytes
        } else {
            this.remove(Keys.uploadSizeLimitBytes)
        }
        if (settings.downloadSizeLimitBytes != null) {
            this[Keys.downloadSizeLimitBytes] = settings.downloadSizeLimitBytes
        } else {
            this.remove(Keys.downloadSizeLimitBytes)
        }
        this[Keys.warnOnMobileNetwork] = settings.warnOnMobileNetwork
        this[Keys.wifiOnly] = settings.wifiOnly
        this[Keys.allowParallelTransfers] = settings.allowParallelTransfers
        this[Keys.autoSyncEnabled] = settings.autoSyncEnabled
        this[Keys.autoSyncIntervalMinutes] = settings.autoSyncIntervalMinutes
        this[Keys.syncImmediatelyOnLocalChange] = settings.syncImmediatelyOnLocalChange
        this[Keys.onlyWhileCharging] = settings.onlyWhileCharging
        this[Keys.syncEvenWhenBatteryLow] = settings.syncEvenWhenBatteryLow
        this[Keys.retryAttempts] = settings.retryAttempts
        this[Keys.retryWaitMinutes] = settings.retryWaitMinutes
        this[Keys.batteryOptimizationDisabled] = settings.batteryOptimizationDisabled
        this[Keys.autoStartOnBoot] = settings.autoStartOnBoot
        this[Keys.diagnosticLogEnabled] = settings.diagnosticLogEnabled
    }
}
