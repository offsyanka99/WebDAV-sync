package org.vovchenko.webdavsync.ui.overview

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.ui.components.Formatters
import org.vovchenko.webdavsync.ui.components.LabeledRow
import org.vovchenko.webdavsync.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverviewScreen(
    modifier: Modifier = Modifier,
    onAddFolderClick: () -> Unit = {},
    viewModel: OverviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("WebDAV-Sync") },
                actions = {
                    IconButton(onClick = onAddFolderClick) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = "Add folder pair")
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = viewModel::syncNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                Text("Sync")
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
                    LabeledRow(label = "Status", value = uiState.lastSyncStatus ?: "Ready")
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
                                value = "${Formatters.bytes(account.storageAvailableBytes)} free of " +
                                    Formatters.bytes(account.storageQuotaBytes),
                            )
                        }
                    }
                }
            }
        }
    }
}
