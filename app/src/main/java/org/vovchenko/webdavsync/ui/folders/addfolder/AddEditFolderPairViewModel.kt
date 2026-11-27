package org.vovchenko.webdavsync.ui.folders.addfolder

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.saf.SafFolderAccess
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.data.remote.WebDavPathSafety
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.domain.sync.FolderPairEditor
import org.vovchenko.webdavsync.push.PushStatus
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
    val error: String? = null,
)

private data class FormEvents(val saved: Boolean = false, val deleted: Boolean = false, val error: String? = null)

/** Default remote folder path for new folder pairs (user can change before save). */
const val DEFAULT_REMOTE_FOLDER_PATH = "/Webdavsync"

internal fun folderEditsAreUnsaved(
    baseline: AddEditFolderPairFormState?,
    current: AddEditFolderPairFormState,
): Boolean = baseline != null && current != baseline

private fun FolderPairEntity.toEditorForm(): AddEditFolderPairFormState = AddEditFolderPairFormState(
    name = name,
    accountId = accountId,
    remoteFolderPath = remoteFolderPath,
    localFolderUri = localFolderUri,
    syncMethod = syncMethod,
    excludeHiddenFiles = excludeHiddenFiles,
    excludedSubfolders = excludedSubfolders,
    deleteEmptyFolders = deleteEmptyFolders,
    instantUpload = instantUpload,
    enabled = enabled,
)

@HiltViewModel
class AddEditFolderPairViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val folderPairRepository: FolderPairRepository,
    private val accountRepository: WebDavAccountRepository,
    private val safFolderAccess: SafFolderAccess,
    private val folderPairEditor: FolderPairEditor,
    settingsRepository: SettingsRepository,
    pushRepository: PushRepository,
) : ViewModel() {

    private val folderPairId: Long? = savedStateHandle.get<Long>("folderPairId")?.takeIf { it > 0 }

    /** WebDAV-Push status of the saved pair; null for a new pair or while the feature is off. */
    val pushStatus: StateFlow<String?> = (
        folderPairId?.let { id ->
            combine(
                folderPairRepository.observeById(id),
                settingsRepository.settings,
                pushRepository.observeRegistrations(),
                pushRepository.observeEndpoints(),
            ) { pair, settings, registrations, endpoints ->
                pair?.let { PushStatus.forPair(it, settings, registrations, endpoints, PushStatus::formatDate) }
            }
        } ?: flowOf(null)
        ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val form = MutableStateFlow(AddEditFolderPairFormState())

    /**
     * Null until an existing pair has loaded. The default form must not count as an edit
     * during that load, or opening a folder and leaving at once would ask to save.
     */
    private val loadedBaseline = MutableStateFlow<AddEditFolderPairFormState?>(
        if (folderPairId == null) form.value else null,
    )

    val hasUnsavedChanges: StateFlow<Boolean> = combine(form, loadedBaseline) { current, baseline ->
        folderEditsAreUnsaved(baseline, current)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

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
            error = events.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AddEditFolderPairUiState())

    init {
        val id = folderPairId
        if (id != null) {
            viewModelScope.launch {
                val pair = folderPairRepository.observeById(id).first()
                if (pair != null) {
                    val loaded = pair.toEditorForm()
                    // Keep keystrokes that landed before the row arrived.
                    if (form.value == AddEditFolderPairFormState()) {
                        form.value = loaded
                    }
                    loadedBaseline.value = loaded
                } else {
                    loadedBaseline.value = loadedBaseline.value ?: AddEditFolderPairFormState()
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

    /** @return false when the form was rejected before a write started. */
    fun save(): Boolean {
        val state = form.value
        if (!state.canSave) return false
        val remoteFolderPath = try {
            WebDavPathSafety.sanitize(state.remoteFolderPath.trim())
        } catch (error: IllegalArgumentException) {
            events.value = events.value.copy(error = error.message ?: "Remote path is not safe")
            return false
        }
        viewModelScope.launch {
            try {
                val entity = FolderPairEntity(
                    id = folderPairId ?: 0,
                    accountId = state.accountId!!,
                    name = state.name.trim(),
                    remoteFolderPath = remoteFolderPath,
                    localFolderUri = state.localFolderUri!!,
                    syncMethod = state.syncMethod,
                    excludeHiddenFiles = state.excludeHiddenFiles,
                    excludedSubfolders = state.excludedSubfolders,
                    deleteEmptyFolders = state.deleteEmptyFolders,
                    instantUpload = state.instantUpload,
                    enabled = state.enabled,
                )
                val existing = folderPairId?.let { folderPairRepository.observeById(it).first() }
                folderPairEditor.save(existing, entity)
                val savedForm = state.copy(name = state.name.trim(), remoteFolderPath = remoteFolderPath)
                form.value = savedForm
                loadedBaseline.value = savedForm
                events.value = events.value.copy(saved = true, error = null)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                events.value = events.value.copy(error = error.message ?: "Could not save the folder pair")
            }
        }
        return true
    }

    fun delete() {
        val id = folderPairId ?: return
        viewModelScope.launch {
            folderPairRepository.observeById(id).first()?.let { folderPairRepository.delete(it) }
            events.value = events.value.copy(deleted = true)
        }
    }
}
