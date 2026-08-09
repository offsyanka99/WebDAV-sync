package org.vovchenko.webdavsync.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.ui.components.AppScaffold
import org.vovchenko.webdavsync.ui.components.LabeledRow
import org.vovchenko.webdavsync.ui.components.SectionCard

@Composable
fun AccountsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onAddAccountClick: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val accounts by viewModel.accounts.collectAsState()
    var pendingDelete by remember { mutableStateOf<WebDavAccountEntity?>(null) }
    val deleteWarning by viewModel.deleteWarning.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(deleteWarning) {
        deleteWarning?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearDeleteWarning()
        }
    }

    AppScaffold(
        title = "Accounts",
        onBack = onBack,
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddAccountClick) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Add account")
            }
        },
    ) { innerPadding ->
        if (accounts.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Text(
                    text = "No accounts configured yet",
                    modifier = Modifier.padding(16.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(accounts, key = { it.id }) { account ->
                    SectionCard(title = account.displayName) {
                        LabeledRow(label = "Server", value = account.baseUrl)
                        LabeledRow(label = "Auth", value = account.authScheme?.name ?: "\u2014")
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            IconButton(onClick = { pendingDelete = account }) {
                                Icon(imageVector = Icons.Filled.Delete, contentDescription = "Delete account")
                            }
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { account ->
        DeleteAccountDialog(
            account = account,
            viewModel = viewModel,
            onDismiss = { pendingDelete = null },
            onConfirm = { alsoDeleteData ->
                viewModel.deleteAccount(account, alsoDeleteData)
                pendingDelete = null
            },
        )
    }
}

@Composable
private fun DeleteAccountDialog(
    account: WebDavAccountEntity,
    viewModel: AccountsViewModel,
    onDismiss: () -> Unit,
    onConfirm: (alsoDeleteData: Boolean) -> Unit,
) {
    var folderPairCount by remember { mutableIntStateOf(-1) }
    var step by remember { mutableIntStateOf(1) }
    var alsoDeleteData by remember { mutableStateOf(false) }

    LaunchedEffect(account.id) {
        folderPairCount = viewModel.folderPairCount(account)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (step == 1) "Delete account?" else "Are you sure?") },
        text = {
            Column {
                if (step == 1) {
                    val pairsText = if (folderPairCount > 0) {
                        " This will also remove $folderPairCount folder pair(s) using this account."
                    } else {
                        ""
                    }
                    Text("Delete \"${account.displayName}\"?$pairsText")
                } else {
                    Text("This cannot be undone.")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Checkbox(checked = alsoDeleteData, onCheckedChange = { alsoDeleteData = it })
                        Text("Also delete the files on device and in the cloud")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (step == 1) {
                    step = 2
                } else {
                    onConfirm(alsoDeleteData)
                }
            }) {
                Text(if (step == 1) "Continue" else "Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
