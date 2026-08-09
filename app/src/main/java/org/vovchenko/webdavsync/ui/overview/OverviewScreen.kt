package org.vovchenko.webdavsync.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.domain.sync.SyncStatusDisplay
import org.vovchenko.webdavsync.ui.components.Formatters
import org.vovchenko.webdavsync.ui.components.LabeledRow
import org.vovchenko.webdavsync.ui.components.SectionCard
import org.vovchenko.webdavsync.ui.components.WebDavSyncTitle
import org.vovchenko.webdavsync.ui.theme.StatusError
import org.vovchenko.webdavsync.ui.theme.StatusOk
import org.vovchenko.webdavsync.ui.theme.StatusWarn

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    modifier: Modifier = Modifier,
    viewModel: OverviewViewModel = hiltViewModel(),
    /** Set when the widget opens the app for a Sync that needs the mobile-data warning. */
    requestSyncOnStart: Boolean = false,
    onRequestSyncOnStartConsumed: () -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    val showMobileWarning by viewModel.showMobileDataWarning.collectAsState()

    LaunchedEffect(requestSyncOnStart) {
        if (requestSyncOnStart) {
            viewModel.requestSync()
            onRequestSyncOnStartConsumed()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { WebDavSyncTitle() },
            )
        },
        bottomBar = {
            Button(
                onClick = viewModel::requestSync,
                enabled = !uiState.syncing,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text(if (uiState.syncing) "Syncing…" else "Sync")
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                SectionCard(title = "Sync status") {
                    LabeledRow(label = "Last sync", value = Formatters.timestamp(uiState.lastSyncAtMillis))
                    LabeledRow(label = "Duration", value = Formatters.duration(uiState.lastSyncDurationMs))
                    LabeledRow(
                        label = "Status",
                        value = uiState.statusDisplay,
                        valueColor = statusColor(uiState),
                    )
                }
            }
            item {
                SectionCard(title = "Recent changes") {
                    LabeledRow(label = "Upload", value = uiState.uploaded.toString())
                    LabeledRow(label = "Download", value = uiState.downloaded.toString())
                    LabeledRow(label = "Deleted in device", value = uiState.deletedDevice.toString())
                    LabeledRow(label = "Deleted in cloud", value = uiState.deletedCloud.toString())
                }
            }
            item {
                SectionCard(title = "Cloud Storage") {
                    if (uiState.accounts.isEmpty()) {
                        Text("No accounts configured yet")
                    } else {
                        uiState.accounts.forEach { account ->
                            LabeledRow(label = "Account", value = account.displayName)
                            LabeledRow(label = "Server", value = account.baseUrl)
                            LabeledRow(
                                label = "Storage",
                                value = Formatters.storageSummary(
                                    available = account.storageAvailableBytes,
                                    used = null,
                                    total = account.storageQuotaBytes,
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showMobileWarning) {
        AlertDialog(
            onDismissRequest = viewModel::dismissMobileDataWarning,
            title = { Text("Sync on mobile data?") },
            text = {
                Text("You are on a mobile network. Syncing may use cellular data. Continue?")
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmMobileDataSync) { Text("Sync anyway") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissMobileDataWarning) { Text("Cancel") }
            },
        )
    }
}

/** Same color-role mapping as the home-screen widget ([SyncStatusDisplay.colorRole]). */
private fun statusColor(state: OverviewUiState): Color =
    when (SyncStatusDisplay.resolve(state.lastSyncStatus, state.syncing).colorRole) {
        SyncStatusDisplay.ColorRole.WARN -> StatusWarn
        SyncStatusDisplay.ColorRole.OK -> StatusOk
        SyncStatusDisplay.ColorRole.ERROR -> StatusError
        SyncStatusDisplay.ColorRole.NEUTRAL -> Color.Unspecified
    }
