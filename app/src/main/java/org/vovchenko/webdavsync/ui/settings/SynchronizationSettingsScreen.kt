package org.vovchenko.webdavsync.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.ui.components.ToggleRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SynchronizationSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Synchronization") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = settings.uploadSizeLimitBytes?.let { (it / (1024 * 1024)).toString() } ?: "",
                onValueChange = { text ->
                    val mb = text.toLongOrNull()
                    viewModel.update { it.copy(uploadSizeLimitBytes = mb?.let { v -> v * 1024 * 1024 }) }
                },
                label = { Text("Upload size limit (MB, blank = no limit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = settings.downloadSizeLimitBytes?.let { (it / (1024 * 1024)).toString() } ?: "",
                onValueChange = { text ->
                    val mb = text.toLongOrNull()
                    viewModel.update { it.copy(downloadSizeLimitBytes = mb?.let { v -> v * 1024 * 1024 }) }
                },
                label = { Text("Download size limit (MB, blank = no limit)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            ToggleRow(
                label = "Wi-Fi only",
                checked = settings.wifiOnly,
                onCheckedChange = { viewModel.update { s -> s.copy(wifiOnly = it) } },
            )
            ToggleRow(
                label = "Warn before syncing on mobile data",
                checked = settings.warnOnMobileNetwork,
                onCheckedChange = { viewModel.update { s -> s.copy(warnOnMobileNetwork = it) } },
            )
            ToggleRow(
                label = "Allow parallel transfers",
                checked = settings.allowParallelTransfers,
                onCheckedChange = { viewModel.update { s -> s.copy(allowParallelTransfers = it) } },
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
                    minValue = 15,
                    step = 15,
                    onValueChange = { viewModel.update { s -> s.copy(autoSyncIntervalMinutes = it) } },
                )
                ToggleRow(
                    label = "Sync immediately on local changes",
                    checked = settings.syncImmediatelyOnLocalChange,
                    onCheckedChange = { viewModel.update { s -> s.copy(syncImmediatelyOnLocalChange = it) } },
                )
                ToggleRow(
                    label = "Only while charging",
                    checked = settings.onlyWhileCharging,
                    onCheckedChange = { viewModel.update { s -> s.copy(onlyWhileCharging = it) } },
                )
            }

            HorizontalDivider()

            Stepper(
                label = "Retry attempts",
                value = settings.retryAttempts,
                minValue = 0,
                step = 1,
                onValueChange = { viewModel.update { s -> s.copy(retryAttempts = it) } },
            )
            Stepper(
                label = "Retry wait (minutes)",
                value = settings.retryWaitMinutes,
                minValue = 1,
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
    step: Int,
    onValueChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onValueChange((value - step).coerceAtLeast(minValue)) }) {
                Icon(imageVector = Icons.Filled.Remove, contentDescription = "Decrease")
            }
            Text(value.toString())
            IconButton(onClick = { onValueChange(value + step) }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Increase")
            }
        }
    }
}
