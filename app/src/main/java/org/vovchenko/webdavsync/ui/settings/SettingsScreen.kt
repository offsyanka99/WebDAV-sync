package org.vovchenko.webdavsync.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

private data class SettingsMenuItem(val label: String, val description: String, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsMenuScreen(
    modifier: Modifier = Modifier,
    onSynchronizationClick: () -> Unit,
    onSystemClick: () -> Unit,
    onBackupRestoreClick: () -> Unit,
    onAboutClick: () -> Unit,
    onAccountsClick: () -> Unit,
) {
    val items = listOf(
        SettingsMenuItem("Synchronization", "Size limits, Wi-Fi only, auto-sync schedule, retries", onSynchronizationClick),
        SettingsMenuItem("Configuration", "Battery optimization, auto-start, diagnostic log", onSystemClick),
        SettingsMenuItem("Accounts", "Manage WebDAV accounts", onAccountsClick),
        SettingsMenuItem("Backup & Restore", "Export or import your configuration", onBackupRestoreClick),
        SettingsMenuItem("About", "Version, contact", onAboutClick),
    )

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Settings") }) },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.padding(innerPadding)) {
            items(items) { item ->
                ListItem(
                    headlineContent = { Text(item.label) },
                    supportingContent = { Text(item.description) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = item.onClick),
                )
                HorizontalDivider()
            }
        }
    }
}
