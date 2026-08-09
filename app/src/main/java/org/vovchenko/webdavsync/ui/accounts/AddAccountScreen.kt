package org.vovchenko.webdavsync.ui.accounts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import org.vovchenko.webdavsync.ui.components.AppScaffold

@Composable
fun AddAccountScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var passwordVisible by remember { mutableStateOf(false) }
    val certPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::setTrustedCertificate)
    }

    LaunchedEffect(uiState.saved) {
        if (uiState.saved) onSaved()
    }

    AppScaffold(
        title = "Add account",
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
            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = viewModel::setDisplayName,
                label = { Text("Display name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.baseUrl,
                onValueChange = viewModel::setBaseUrl,
                label = { Text("Server URL") },
                placeholder = { Text("https://cloud.example.com/remote.php/dav/files/user/") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = uiState.username,
                onValueChange = viewModel::setUsername,
                label = { Text("Username") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.password,
                onValueChange = viewModel::setPassword,
                label = { Text("Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Filled.VisibilityOff
                            } else {
                                Icons.Filled.Visibility
                            },
                            contentDescription = if (passwordVisible) "Hide password" else "Show password",
                        )
                    }
                },
            )
            OutlinedButton(onClick = { certPicker.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(uiState.trustedCertificateFileName ?: "Import custom CA certificate (optional)")
            }
            if (uiState.error != null) {
                Text(text = uiState.error!!, color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = viewModel::save,
                enabled = uiState.canSave && !uiState.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Test connection & save")
            }
        }
    }
}
