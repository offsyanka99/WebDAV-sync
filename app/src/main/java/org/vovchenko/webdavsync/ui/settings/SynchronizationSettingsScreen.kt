package org.vovchenko.webdavsync.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.ui.components.AppScaffold
import org.vovchenko.webdavsync.ui.components.LabelWithInfoIcon
import org.vovchenko.webdavsync.ui.components.SettingHelpStyle
import org.vovchenko.webdavsync.ui.components.SettingToggleRow
import org.vovchenko.webdavsync.ui.components.SizeLimitMbField
import org.vovchenko.webdavsync.ui.components.ToggleRow

@Composable
fun SynchronizationSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()

    AppScaffold(
        title = "Synchronization",
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SizeLimitMbField(
                label = "Upload size limit (MB, blank = no limit)",
                valueBytes = settings.uploadSizeLimitBytes,
                onBytesChange = { bytes -> viewModel.update { it.copy(uploadSizeLimitBytes = bytes) } },
            )
            SizeLimitMbField(
                label = "Download size limit (MB, blank = no limit)",
                valueBytes = settings.downloadSizeLimitBytes,
                onBytesChange = { bytes -> viewModel.update { it.copy(downloadSizeLimitBytes = bytes) } },
            )

            SettingToggleRow(
                label = "Wi-Fi only",
                checked = settings.wifiOnly,
                onCheckedChange = { viewModel.update { s -> s.copy(wifiOnly = it) } },
                help = WIFI_ONLY_INFO,
                helpStyle = SettingHelpStyle.InlineInfo,
            )
            SettingToggleRow(
                label = "Warn before syncing on mobile data",
                checked = settings.warnOnMobileNetwork,
                onCheckedChange = { viewModel.update { s -> s.copy(warnOnMobileNetwork = it) } },
                help = WARN_MOBILE_INFO,
                helpStyle = SettingHelpStyle.InlineInfo,
            )
            SettingToggleRow(
                label = "Allow parallel transfers",
                checked = settings.allowParallelTransfers,
                onCheckedChange = { viewModel.update { s -> s.copy(allowParallelTransfers = it) } },
                help = PARALLEL_TRANSFERS_INFO,
                helpStyle = SettingHelpStyle.InlineInfo,
            )

            HorizontalDivider()

            ToggleRow(
                label = "Auto-sync",
                checked = settings.autoSyncEnabled,
                onCheckedChange = { viewModel.update { s -> s.copy(autoSyncEnabled = it) } },
            )
            if (settings.autoSyncEnabled) {
                Stepper(
                    label = "Interval (minutes)",
                    value = settings.autoSyncIntervalMinutes,
                    minValue = AppSettings.MIN_INTERVAL_MINUTES,
                    maxValue = AppSettings.MAX_INTERVAL_MINUTES,
                    step = 15,
                    onValueChange = { viewModel.update { s -> s.copy(autoSyncIntervalMinutes = it) } },
                )
                SettingToggleRow(
                    label = "Sync immediately on local changes",
                    checked = settings.syncImmediatelyOnLocalChange,
                    onCheckedChange = { viewModel.update { s -> s.copy(syncImmediatelyOnLocalChange = it) } },
                    help = IMMEDIATE_LOCAL_INFO,
                    helpStyle = SettingHelpStyle.InlineInfo,
                )
                ToggleRow(
                    label = "Only while charging",
                    checked = settings.onlyWhileCharging,
                    onCheckedChange = { viewModel.update { s -> s.copy(onlyWhileCharging = it) } },
                )
                SettingToggleRow(
                    label = "Sync even when battery is low",
                    checked = settings.syncEvenWhenBatteryLow,
                    onCheckedChange = { viewModel.update { s -> s.copy(syncEvenWhenBatteryLow = it) } },
                    help = BATTERY_LOW_INFO,
                    helpStyle = SettingHelpStyle.InlineInfo,
                )
            }

            FilledTonalButton(
                onClick = { viewModel.update { s -> s.applyBatterySaverProfile() } },
                enabled = !settings.isBatterySaverProfile(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use battery saver profile")
            }
            Text(
                text = BATTERY_SAVER_INFO,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()

            Stepper(
                label = "Retry attempts",
                value = settings.retryAttempts,
                minValue = 0,
                maxValue = AppSettings.MAX_RETRY_ATTEMPTS,
                step = 1,
                onValueChange = { viewModel.update { s -> s.copy(retryAttempts = it) } },
                infoDescription = RETRY_ATTEMPTS_INFO,
            )
            Stepper(
                label = "Retry wait (minutes)",
                value = settings.retryWaitMinutes,
                minValue = 1,
                maxValue = AppSettings.MAX_RETRY_WAIT_MINUTES,
                step = 1,
                onValueChange = { viewModel.update { s -> s.copy(retryWaitMinutes = it) } },
            )
        }
    }
}

@Composable
private fun Stepper(
    label: String,
    value: Int,
    minValue: Int,
    maxValue: Int,
    step: Int,
    onValueChange: (Int) -> Unit,
    infoDescription: String? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LabelWithInfoIcon(
            label = label,
            infoDescription = infoDescription,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onValueChange((value - step).coerceAtLeast(minValue)) }) {
                Icon(imageVector = Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(value.toString())
            IconButton(onClick = { onValueChange((value + step).coerceAtMost(maxValue)) }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}

private const val WIFI_ONLY_INFO =
    "When enabled, sync runs only on unmetered networks (typically Wi‑Fi). " +
        "Manual, scheduled, and follow-up syncs wait until Wi‑Fi is available. " +
        "This is the reliable way to avoid using mobile data for background transfers."

private const val WARN_MOBILE_INFO =
    "When enabled, starting sync manually (Overview or home-screen widget) while on " +
        "cellular data shows a confirmation dialog first. " +
        "Scheduled sync and “sync on local changes” do not show this warning — use " +
        "“Wi‑Fi only” if you want those to avoid mobile data entirely."

private const val PARALLEL_TRANSFERS_INFO =
    "When enabled, several files can upload or download at the same time (up to 4), " +
        "which is usually faster on a good connection. " +
        "Turn off for slower or unstable networks, or if the server limits concurrent connections."

private const val RETRY_ATTEMPTS_INFO =
    "How many times to retry a single file transfer after a temporary failure " +
        "(for example a connection timeout). " +
        "Each retry waits the “Retry wait” interval. " +
        "Set to 0 to try each file only once."

private const val IMMEDIATE_LOCAL_INFO =
    "When enabled, local file changes start a sync after a short delay instead of waiting " +
        "for the interval. That uses more battery (folder watch + extra transfers). " +
        "Leave this off to save energy; scheduled auto-sync still catches up. " +
        "A folder pair’s own Instant upload checkbox can still trigger this for that pair."

private const val BATTERY_LOW_INFO =
    "When off (recommended), scheduled background sync waits until the battery is not low. " +
        "Manual Sync from Overview or the widget still runs. " +
        "Turn on only if you need hourly catch-up on a nearly empty battery."

private const val BATTERY_SAVER_INFO =
    "Sets Wi‑Fi only, only while charging, 3-hour interval, and turns off sync-on-local-change. " +
        "A folder pair’s Instant upload checkbox still watches that folder."
