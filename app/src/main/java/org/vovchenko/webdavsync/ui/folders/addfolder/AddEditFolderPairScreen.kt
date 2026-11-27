package org.vovchenko.webdavsync.ui.folders.addfolder

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.ui.components.AppScaffold
import org.vovchenko.webdavsync.ui.components.ToggleRow
import org.vovchenko.webdavsync.ui.components.UnsavedChangesDialog
import org.vovchenko.webdavsync.ui.navigation.BindTabLeave
import org.vovchenko.webdavsync.ui.navigation.TabLeaveInterceptor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddEditFolderPairScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onAddAccountClick: () -> Unit,
    tabLeaveInterceptor: TabLeaveInterceptor? = null,
    viewModel: AddEditFolderPairViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val formDirty by viewModel.hasUnsavedChanges.collectAsState()
    val pushStatus by viewModel.pushStatus.collectAsState()
    val form = uiState.form

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(viewModel::onLocalFolderPicked)
    }

    var accountMenuExpanded by remember { mutableStateOf(false) }
    var syncMethodMenuExpanded by remember { mutableStateOf(false) }
    var newExcludedPath by remember { mutableStateOf("") }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var savingLeave by remember { mutableStateOf(false) }
    var pendingProceed by remember { mutableStateOf<(() -> Unit)?>(null) }
    val dirty = formDirty || newExcludedPath.isNotBlank()

    val requestLeave: (after: (() -> Unit)?) -> Unit = requestLeave@{ after ->
        if (savingLeave) return@requestLeave
        if (!dirty) {
            onBack()
            after?.invoke()
        } else {
            pendingProceed = after
            showLeaveDialog = true
        }
    }

    LaunchedEffect(uiState.error) {
        if (uiState.error != null) savingLeave = false
    }

    LaunchedEffect(uiState.saved, uiState.deleted) {
        if (uiState.saved || uiState.deleted) {
            val after = pendingProceed
            pendingProceed = null
            showLeaveDialog = false
            onBack()
            after?.invoke()
        }
    }

    BackHandler(enabled = (dirty || savingLeave) && !showLeaveDialog) {
        requestLeave(null)
    }
    BindTabLeave(tabLeaveInterceptor, handler = { proceed -> requestLeave(proceed) })
    LaunchedEffect(tabLeaveInterceptor) {
        val proceed = tabLeaveInterceptor?.deferredProceed ?: return@LaunchedEffect
        tabLeaveInterceptor.deferredProceed = null
        requestLeave(proceed)
    }

    AppScaffold(
        title = if (uiState.isEditing) "Edit folder pair" else "Add folder pair",
        onBack = { requestLeave(null) },
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
            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::setName,
                label = { Text("Name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            ExposedDropdownMenuBox(
                expanded = accountMenuExpanded,
                onExpandedChange = { accountMenuExpanded = it },
            ) {
                val accountName = uiState.accounts.firstOrNull { it.id == form.accountId }?.displayName
                OutlinedTextField(
                    value = accountName ?: "Choose account",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Account") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountMenuExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = accountMenuExpanded,
                    onDismissRequest = { accountMenuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    uiState.accounts.forEach { account ->
                        DropdownMenuItem(
                            text = { Text(account.displayName) },
                            onClick = {
                                viewModel.setAccountId(account.id)
                                accountMenuExpanded = false
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add account\u2026") },
                        onClick = {
                            accountMenuExpanded = false
                            onAddAccountClick()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    )
                }
            }

            OutlinedTextField(
                value = form.remoteFolderPath,
                onValueChange = viewModel::setRemoteFolderPath,
                label = { Text("Remote folder path") },
                placeholder = { Text("/Photos") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            OutlinedButton(onClick = { folderPicker.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                Text(form.localFolderUri?.let { "Local folder selected" } ?: "Choose local folder")
            }

            ExposedDropdownMenuBox(
                expanded = syncMethodMenuExpanded,
                onExpandedChange = { syncMethodMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = form.syncMethod.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Sync method") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = syncMethodMenuExpanded) },
                    colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                    modifier = Modifier
                        .menuAnchor()
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = syncMethodMenuExpanded,
                    onDismissRequest = { syncMethodMenuExpanded = false },
                    modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                ) {
                    SyncMethod.entries.forEach { method ->
                        DropdownMenuItem(
                            text = { Text(method.label()) },
                            onClick = {
                                viewModel.setSyncMethod(method)
                                syncMethodMenuExpanded = false
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        )
                    }
                }
            }

            ToggleRow(label = "Exclude hidden files", checked = form.excludeHiddenFiles, onCheckedChange = viewModel::setExcludeHiddenFiles)
            ToggleRow(label = "Delete empty folders", checked = form.deleteEmptyFolders, onCheckedChange = viewModel::setDeleteEmptyFolders)
            ToggleRow(label = "Instant upload", checked = form.instantUpload, onCheckedChange = viewModel::setInstantUpload)

            Text("Excluded subfolders", style = MaterialTheme.typography.titleSmall)
            form.excludedSubfolders.forEachIndexed { index, path ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(path)
                    IconButton(onClick = { viewModel.removeExcludedSubfolder(index) }) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove")
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newExcludedPath,
                    onValueChange = { newExcludedPath = it },
                    label = { Text("e.g. .thumbnails or node_modules/**") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                TextButton(onClick = {
                    viewModel.addExcludedSubfolder(newExcludedPath)
                    newExcludedPath = ""
                }) {
                    Text("Add")
                }
            }

            ToggleRow(label = "Enabled", checked = form.enabled, onCheckedChange = viewModel::setEnabled)

            pushStatus?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (uiState.error != null) {
                Text(text = uiState.error!!, color = MaterialTheme.colorScheme.error)
            }

            Button(
                onClick = {
                    if (newExcludedPath.isNotBlank()) {
                        viewModel.addExcludedSubfolder(newExcludedPath)
                        newExcludedPath = ""
                    }
                    savingLeave = true
                    if (!viewModel.save()) savingLeave = false
                },
                enabled = form.canSave && !savingLeave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }

            if (uiState.isEditing) {
                OutlinedButton(
                    onClick = viewModel::delete,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Delete folder pair", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showLeaveDialog) {
        UnsavedChangesDialog(
            message = "This folder pair has unsaved changes.",
            canSave = form.canSave,
            saving = savingLeave,
            error = uiState.error,
            onSave = {
                if (newExcludedPath.isNotBlank()) {
                    viewModel.addExcludedSubfolder(newExcludedPath)
                    newExcludedPath = ""
                }
                savingLeave = true
                if (!viewModel.save()) savingLeave = false
            },
            onDiscard = {
                val after = pendingProceed
                pendingProceed = null
                showLeaveDialog = false
                onBack()
                after?.invoke()
            },
            onKeepEditing = {
                pendingProceed = null
                showLeaveDialog = false
            },
        )
    }
}

private fun SyncMethod.label(): String = when (this) {
    SyncMethod.TWO_WAY -> "Two-way"
    SyncMethod.TO_DEVICE -> "To device only"
    SyncMethod.TO_CLOUD -> "To cloud only"
}
