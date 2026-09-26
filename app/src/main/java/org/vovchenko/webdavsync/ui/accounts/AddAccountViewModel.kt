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
import org.vovchenko.webdavsync.data.local.security.CertificateDescriptions
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
    val trustedCertificateSubject: String? = null,
    val trustedCertificateFingerprint: String? = null,
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
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read < 0) break
                    total += read
                    if (total > CertificateDescriptions.MAX_BYTES) {
                        error("Certificate file is too large")
                    }
                    buffer.write(chunk, 0, read)
                }
                buffer.toByteArray()
            }
        }.getOrElse {
            _uiState.value = _uiState.value.copy(error = it.message ?: "Could not read certificate")
            return
        }
        if (bytes == null) {
            _uiState.value = _uiState.value.copy(error = "Could not read certificate")
            return
        }
        val described = CertificateDescriptions.parse(bytes)
        if (described == null) {
            _uiState.value = _uiState.value.copy(
                trustedCertificateBytes = null,
                trustedCertificateFileName = null,
                trustedCertificateSubject = null,
                trustedCertificateFingerprint = null,
                error = "That file is not an X.509 certificate",
            )
            return
        }
        _uiState.value = _uiState.value.copy(
            trustedCertificateBytes = bytes,
            trustedCertificateFileName = uri.lastPathSegment,
            trustedCertificateSubject = described.subject,
            trustedCertificateFingerprint = described.sha256Fingerprint,
            error = null,
        )
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
