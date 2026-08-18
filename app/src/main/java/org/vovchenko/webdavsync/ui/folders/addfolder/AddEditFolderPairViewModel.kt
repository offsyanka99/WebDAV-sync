package org.vovchenko.webdavsync.ui.folders.addfolder

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.saf.SafFolderAccess
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import javax.inject.Inject

data class AddEditFolderPairFormState(
    val name: String = "",
    val accountId: Long? = null,
    /** Default remote root for new folder pairs (user can change before save). */
    val remoteFolderPath: String = DEFAULT_REMOTE_FOLDER_PATH,
    val localFolderUri: String? = null,
    val syncMethod: SyncMethod = SyncMethod.TWO_WAY,
    val excludeHiddenFiles: Boolean = true,
    val excludedSubfolders: List<String> = emptyList(),
    val deleteEmptyFolders: Boolean = false,
    val instantUpload: Boolean = false,
    val enabled: Boolean = true,
) {
    val canSave: Boolean get() = name.isNotBlank() && accountId != null && remoteFolderPath.isNotBlank() && localFolderUri != null
}

data class AddEditFolderPairUiState(
    val isEditing: Boolean = false,
    val accounts: List<WebDavAccountEntity> = emptyList(),
    val form: AddEditFolderPairFormState = AddEditFolderPairFormState(),
    val saved: Boolean = false,
    val deleted: Boolean = false,
)

private data class FormEvents(val saved: Boolean = false, val deleted: Boolean = false)

/** Default remote folder path for new folder pairs (user can change before save). */
const val DEFAULT_REMOTE_FOLDER_PATH = "/Webdavsync"

@HiltViewModel
class AddEditFolderPairViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderPairRepository: FolderPairRepository,
    private val accountRepository: WebDavAccountRepository,
    private val safFolderAccess: SafFolderAccess,
) : ViewModel() {

    private val folderPairId: Long? = savedStateHandle.get<Long>("folderPairId")?.takeIf { it > 0 }

    private val form = MutableStateFlow(AddEditFolderPairFormState())
    private val events = MutableStateFlow(FormEvents())

    val uiState: StateFlow<AddEditFolderPairUiState> = combine(
        accountRepository.observeAll(),
        form,
        events,
    ) { accounts, form, events ->
        AddEditFolderPairUiState(
            isEditing = folderPairId != null,
            accounts = accounts,
            form = form,
            saved = events.saved,
            deleted = events.deleted,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AddEditFolderPairUiState())

    init {
        val id = folderPairId
        if (id != null) {
            viewModelScope.launch {
                folderPairRepository.observeById(id).first()?.let { pair ->
                    form.value = AddEditFolderPairFormState(
                        name = pair.name,
                        accountId = pair.accountId,
                        remoteFolderPath = pair.remoteFolderPath,
                        localFolderUri = pair.localFolderUri,
                        syncMethod = pair.syncMethod,
                        excludeHiddenFiles = pair.excludeHiddenFiles,
                        excludedSubfolders = pair.excludedSubfolders,
                        deleteEmptyFolders = pair.deleteEmptyFolders,
                        instantUpload = pair.instantUpload,
                        enabled = pair.enabled,
                    )
                }
            }
        }
    }

    fun setName(value: String) { form.value = form.value.copy(name = value) }
    fun setAccountId(value: Long) { form.value = form.value.copy(accountId = value) }
    fun setRemoteFolderPath(value: String) { form.value = form.value.copy(remoteFolderPath = value) }
    fun setSyncMethod(value: SyncMethod) { form.value = form.value.copy(syncMethod = value) }
    fun setExcludeHiddenFiles(value: Boolean) { form.value = form.value.copy(excludeHiddenFiles = value) }
    fun setDeleteEmptyFolders(value: Boolean) { form.value = form.value.copy(deleteEmptyFolders = value) }
    fun setInstantUpload(value: Boolean) { form.value = form.value.copy(instantUpload = value) }
    fun setEnabled(value: Boolean) { form.value = form.value.copy(enabled = value) }

    fun onLocalFolderPicked(uri: Uri) {
        safFolderAccess.persistAccess(uri)
        form.value = form.value.copy(localFolderUri = uri.toString())
    }

    fun addExcludedSubfolder(path: String) {
        if (path.isBlank()) return
        form.value = form.value.copy(excludedSubfolders = form.value.excludedSubfolders + path.trim())
    }

    fun removeExcludedSubfolder(index: Int) {
        form.value = form.value.copy(excludedSubfolders = form.value.excludedSubfolders.filterIndexed { i, _ -> i != index })
    }

    fun save() {
        val state = form.value
        if (!state.canSave) return
        viewModelScope.launch {
            val entity = FolderPairEntity(
                id = folderPairId ?: 0,
                accountId = state.accountId!!,
                name = state.name.trim(),
                remoteFolderPath = state.remoteFolderPath.trim(),
                localFolderUri = state.localFolderUri!!,
                syncMethod = state.syncMethod,
                excludeHiddenFiles = state.excludeHiddenFiles,
                excludedSubfolders = state.excludedSubfolders,
                deleteEmptyFolders = state.deleteEmptyFolders,
                instantUpload = state.instantUpload,
                enabled = state.enabled,
            )
            if (folderPairId != null) {
                val existing = folderPairRepository.observeById(folderPairId).first()
                val sameLocal = existing?.localFolderUri == entity.localFolderUri
                val sameRemote = existing?.remoteFolderPath == entity.remoteFolderPath
                folderPairRepository.update(
                    entity.copy(
                        lastSyncAt = existing?.lastSyncAt,
                        lastSyncDurationMs = existing?.lastSyncDurationMs,
                        lastSyncStatus = if (sameRemote) existing?.lastSyncStatus else null,
                        lastLocalFingerprint = if (sameLocal) existing?.lastLocalFingerprint else null,
                    ),
                )
            } else {
                folderPairRepository.add(entity)
            }
            events.value = events.value.copy(saved = true)
        }
    }

    fun delete() {
        val id = folderPairId ?: return
        viewModelScope.launch {
            folderPairRepository.observeById(id).first()?.let { folderPairRepository.delete(it) }
            events.value = events.value.copy(deleted = true)
        }
    }
}
