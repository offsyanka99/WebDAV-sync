package org.vovchenko.webdavsync.ui.accounts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.security.WebDavCredentials
import org.vovchenko.webdavsync.data.repository.WebDavConnectionRepository
import javax.inject.Inject

data class AddAccountUiState(
    val displayName: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val trustedCertificateBytes: ByteArray? = null,
    val trustedCertificateFileName: String? = null,
    val isSaving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
) {
    val canSave: Boolean get() = displayName.isNotBlank() && baseUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val connectionRepository: WebDavConnectionRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddAccountUiState())
    val uiState: StateFlow<AddAccountUiState> = _uiState.asStateFlow()

    fun setDisplayName(value: String) { _uiState.value = _uiState.value.copy(displayName = value) }
    fun setBaseUrl(value: String) { _uiState.value = _uiState.value.copy(baseUrl = value) }
    fun setUsername(value: String) { _uiState.value = _uiState.value.copy(username = value) }
    fun setPassword(value: String) { _uiState.value = _uiState.value.copy(password = value) }

    fun setTrustedCertificate(uri: android.net.Uri) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        val fileName = uri.lastPathSegment
        _uiState.value = _uiState.value.copy(trustedCertificateBytes = bytes, trustedCertificateFileName = fileName)
    }

    fun save() {
        val state = _uiState.value
        if (!state.canSave || state.isSaving) return
        _uiState.value = state.copy(isSaving = true, error = null)
        viewModelScope.launch {
            val result = connectionRepository.addAccount(
                displayName = state.displayName.trim(),
                baseUrl = state.baseUrl.trim(),
                credentials = WebDavCredentials(state.username.trim(), state.password),
                trustedCertificateBytes = state.trustedCertificateBytes,
            )
            result.fold(
                onSuccess = {
                    // Security audit finding #13: don't keep the plaintext password in memory any
                    // longer than needed once it's safely persisted (encrypted) by the repository.
                    _uiState.value = _uiState.value.copy(isSaving = false, saved = true, password = "")
                },
                onFailure = { error ->
                    _uiState.value = _uiState.value.copy(isSaving = false, error = error.message ?: "Connection failed")
                },
            )
        }
    }
}
