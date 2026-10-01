package org.vovchenko.webdavsync.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.push.PushServiceOption
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
    val pushServiceLabel by viewModel.pushServiceLabel.collectAsState()
    var pushDialog by remember { mutableStateOf<PushDialogState?>(null) }

    LaunchedEffect(Unit) { viewModel.refreshPushService() }

    pushDialog?.let { state ->
        WebDavPushDialog(
            state = state,
            onSave = { enabled, service ->
                pushDialog = null
                viewModel.applyPushSettings(enabled, service)
            },
            onDismiss = { pushDialog = null },
        )
    }

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
                if (settings.instantDownloadEnabled) {
                    HelpText(INTERVAL_WITH_PUSH_INFO)
                }
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

            Text("Server changes", style = MaterialTheme.typography.titleSmall)
            SettingToggleRow(
                label = "Download server changes instantly (WebDAV-Push)",
                checked = settings.instantDownloadEnabled,
                onCheckedChange = { on ->
                    // The switch opens the dialog; nothing is saved until the user confirms there.
                    pushDialog = PushDialogState(viewModel.pushServiceChoice(), initialEnabled = on)
                },
                help = INSTANT_DOWNLOAD_INFO,
                helpStyle = SettingHelpStyle.InlineInfo,
            )
            HelpText(INSTANT_DOWNLOAD_SUMMARY)
            if (settings.instantDownloadEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Push service: ${pushServiceLabel ?: "app missing"}",
                        color = if (pushServiceLabel == null) MaterialTheme.colorScheme.error else Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            pushDialog = PushDialogState(viewModel.pushServiceChoice(), initialEnabled = true)
                        },
                    ) {
                        Text("Change")
                    }
                }
            }

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

private data class PushDialogState(val choice: PushServiceChoice, val initialEnabled: Boolean)

@Composable
private fun WebDavPushDialog(
    state: PushDialogState,
    onSave: (Boolean, PushServiceOption?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = state.choice.options
    var enabled by remember { mutableStateOf(state.initialEnabled && options.isNotEmpty()) }
    var selected by remember { mutableStateOf(state.choice.preselected) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("WebDAV-Push") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Download server changes instantly", modifier = Modifier.weight(1f).padding(end = 8.dp))
                    Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = options.isNotEmpty())
                }
                HorizontalDivider()
                Text("Push service", style = MaterialTheme.typography.titleSmall)
                if (options.isEmpty()) {
                    Text(NO_PUSH_SERVICE_INFO, color = MaterialTheme.colorScheme.error)
                }
                options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option == selected,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { selected = option },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null, enabled = enabled)
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(option.label)
                            Text(
                                text = if (option.isGooglePlay) "System default · built in" else "UnifiedPush app · no Google",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (options.none { !it.isGooglePlay }) {
                    HelpText(NO_UNIFIEDPUSH_APP_INFO)
                }
                if (enabled && selected?.isGooglePlay == true) {
                    HelpText(GOOGLE_PLAY_PRIVACY_INFO)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(enabled, selected) },
                enabled = !enabled || selected != null,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun HelpText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private const val INSTANT_DOWNLOAD_SUMMARY =
    "Starts a sync when files change on the server. Needs a server with WebDAV-Push for files " +
        "(AngaraDAV 2.5.3+). Regular auto-sync keeps running as a safety net."

private const val INSTANT_DOWNLOAD_INFO =
    "The server notifies this device when files change in a synced folder, and that folder pair syncs " +
        "about 10 seconds later. To cloud pairs are not subscribed.\n\n" +
        "Push service:\n" +
        "• Google Play (FCM) — built in, the system default. Needs Google Play services. " +
        "Notifications travel through Google.\n" +
        "• A UnifiedPush app such as ntfy — no Google. Install it from F-Droid or Google Play; it then " +
        "appears in the list.\n\n" +
        "Either way, notifications are end-to-end encrypted: the push service sees when they arrive and an " +
        "opaque folder ID, never file names or contents. Push-triggered syncs follow Wi‑Fi only, charging, " +
        "and battery settings. The server must have WebDAV-Push for files turned on, and must accept the push " +
        "service's address (AngaraDAV: fcm.googleapis.com or your ntfy host in push_allowed_hosts, if that list is set)."

private const val INTERVAL_WITH_PUSH_INFO =
    "Server changes arrive by push. This interval still controls how often local changes are uploaded " +
        "unless Instant upload is on."

private const val NO_PUSH_SERVICE_INFO =
    "No push service is available. Install a UnifiedPush app such as ntfy, or use a phone with Google Play services."

private const val NO_UNIFIEDPUSH_APP_INFO =
    "To avoid Google, install a UnifiedPush app such as ntfy; it will appear here."

private const val GOOGLE_PLAY_PRIVACY_INFO =
    "Notifications go through Google's FCM. Google sees when they arrive, never file names or contents."
