package org.vovchenko.webdavsync.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLog
import org.vovchenko.webdavsync.sync.worker.BatteryOptimizationHelper

/**
 * Settings → Configuration (WiFi-VPN style): card rows with switch + help text for battery,
 * unused-app management, auto-start, and diagnostic logging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val logFileExists by viewModel.diagnosticLogExists.collectAsState()
    val context = LocalContext.current
    val batteryHelper = remember(context) { BatteryOptimizationHelper(context) }

    var batteryExempt by remember { mutableStateOf(batteryHelper.isIgnoringBatteryOptimizations()) }
    var manageUnusedOn by remember { mutableStateOf(isManageUnusedEnabled(context)) }

    val batterySettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        batteryExempt = batteryHelper.isIgnoringBatteryOptimizations()
    }
    val unusedSettingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        manageUnusedOn = isManageUnusedEnabled(context)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = batteryHelper.isIgnoringBatteryOptimizations()
                manageUnusedOn = isManageUnusedEnabled(context)
                viewModel.refreshDiagnosticLogExists()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Configuration") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ConfigurationSwitchCard(
                title = "Battery optimization",
                help = "When on, the app is exempt from battery optimization (Allow background usage / Unrestricted) so sync is less likely to be stopped. Android may open a system dialog or the App battery screen — confirm there, then return.",
                checked = batteryExempt,
                onCheckedChange = { wantExempt ->
                    if (wantExempt == batteryExempt) return@ConfigurationSwitchCard
                    if (wantExempt) {
                        // Optimistic UI: stay on until ON_RESUME re-reads the real system state.
                        batteryExempt = true
                        try {
                            batterySettingsLauncher.launch(batteryHelper.createRequestExemptionIntent())
                            Toast.makeText(
                                context,
                                "Allow unrestricted / no battery restrictions for WebDAV-sync, then return here",
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (_: Exception) {
                            try {
                                batterySettingsLauncher.launch(batteryHelper.createAppBatterySettingsIntent())
                            } catch (_: Exception) {
                                batteryExempt = batteryHelper.isIgnoringBatteryOptimizations()
                                Toast.makeText(context, "Could not open battery optimization settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } else {
                        // Cannot clear the exemption via API — open App info; snap switch back on resume.
                        batteryExempt = false
                        try {
                            batterySettingsLauncher.launch(batteryHelper.createAppBatterySettingsIntent())
                            Toast.makeText(
                                context,
                                "In App battery usage, set Optimized (or Restricted) if you want battery optimization back on",
                                Toast.LENGTH_LONG,
                            ).show()
                        } catch (_: Exception) {
                            batteryExempt = batteryHelper.isIgnoringBatteryOptimizations()
                            Toast.makeText(context, "Could not open battery optimization settings", Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )

            ConfigurationSwitchCard(
                title = "Manage app if unused",
                help = "Matches the system setting. Leave this off so Android does not pause the app or remove permissions when it looks unused — needed for reliable background sync.",
                checked = manageUnusedOn,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                onCheckedChange = {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        Toast.makeText(context, "This setting is not available on this device", Toast.LENGTH_SHORT).show()
                        return@ConfigurationSwitchCard
                    }
                    manageUnusedOn = isManageUnusedEnabled(context)
                    openUnusedAppSettings(context, unusedSettingsLauncher)
                    Toast.makeText(
                        context,
                        "Change “Manage app if unused” in the system screen, then return here. Leave it off for background sync.",
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )

            ConfigurationSwitchCard(
                title = "Auto-start after reboot",
                help = "When enabled, periodic sync is rescheduled after the phone reboots (requires auto-sync enabled under Synchronization).",
                checked = settings.autoStartOnBoot,
                onCheckedChange = { viewModel.update { s -> s.copy(autoStartOnBoot = it) } },
            )

            Text(
                text = "Diagnostic log",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Enable logging",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(end = 12.dp),
                        )
                        Switch(
                            checked = settings.diagnosticLogEnabled,
                            onCheckedChange = viewModel::setDiagnosticLogEnabled,
                        )
                    }
                    Text(
                        text = "Records sync start/end, transfers, account connection, and errors (secrets redacted). Share the log when troubleshooting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    FilledTonalButton(
                        onClick = {
                            viewModel.refreshDiagnosticLogExists()
                            DiagnosticLog.shareIntent(context)?.let {
                                context.startActivity(Intent.createChooser(it, "Send diagnostic log"))
                            } ?: Toast.makeText(context, "No log entries yet", Toast.LENGTH_SHORT).show()
                        },
                        enabled = logFileExists,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Text("Send log via…")
                    }
                    TextButton(
                        onClick = {
                            viewModel.clearDiagnosticLog()
                            Toast.makeText(context, "Diagnostic log cleared", Toast.LENGTH_SHORT).show()
                        },
                        enabled = logFileExists,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(),
                    ) {
                        Text("Clear log")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigurationSwitchCard(
    title: String,
    help: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                )
                Switch(
                    checked = checked,
                    onCheckedChange = onCheckedChange,
                    enabled = enabled,
                )
            }
            Text(
                text = help,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun isManageUnusedEnabled(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return try {
        // Whitelisted ⇒ auto-revoke off ⇒ "manage if unused" effectively off.
        !context.packageManager.isAutoRevokeWhitelisted
    } catch (_: Exception) {
        true
    }
}

private fun openUnusedAppSettings(
    context: android.content.Context,
    launcher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    val candidates = listOf(
        Intent("android.intent.action.AUTO_REVOKE_PERMISSIONS").apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )
    for (intent in candidates) {
        try {
            if (intent.resolveActivity(context.packageManager) != null) {
                launcher.launch(intent)
                return
            }
        } catch (_: Exception) {
            // try next
        }
    }
    Toast.makeText(context, "Could not open unused-app settings", Toast.LENGTH_SHORT).show()
}
