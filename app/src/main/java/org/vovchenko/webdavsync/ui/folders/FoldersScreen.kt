package org.vovchenko.webdavsync.ui.folders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.ui.components.LabeledRow
import org.vovchenko.webdavsync.ui.components.SectionCard
import org.vovchenko.webdavsync.ui.components.ToggleRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    modifier: Modifier = Modifier,
    onAddFolderClick: () -> Unit = {},
    onEditFolderClick: (Long) -> Unit = {},
    onManageAccountsClick: () -> Unit = {},
    viewModel: FoldersViewModel = hiltViewModel(),
) {
    val rows by viewModel.rows.collectAsState()
    var pendingDelete by remember { mutableStateOf<FolderPairEntity?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Folders") },
                actions = {
                    IconButton(onClick = onManageAccountsClick) {
                        Icon(imageVector = Icons.Filled.ManageAccounts, contentDescription = "Manage accounts")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddFolderClick) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add folder pair")
            }
        },
    ) { innerPadding ->
        if (rows.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "No folder pairs configured yet")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(rows, key = { it.folderPair.id }) { row ->
                    val pair = row.folderPair
                    SectionCard(title = pair.name) {
                        LabeledRow(label = "Account", value = row.accountName)
                        LabeledRow(label = "Remote", value = pair.remoteFolderPath)
                        LabeledRow(label = "Sync method", value = pair.syncMethod.name)
                        ToggleRow(
                            label = "Enabled",
                            checked = pair.enabled,
                            onCheckedChange = { viewModel.setEnabled(pair, it) },
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(onClick = { onEditFolderClick(pair.id) }) {
                                Icon(imageVector = Icons.Filled.Edit, contentDescription = "Edit folder pair")
                            }
                            IconButton(onClick = { pendingDelete = pair }) {
                                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete folder pair")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { pair ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete folder pair?") },
            text = { Text("Delete \"${pair.name}\"? This only removes the sync configuration — no files on device or in the cloud are deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(pair)
                    pendingDelete = null
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }
}
