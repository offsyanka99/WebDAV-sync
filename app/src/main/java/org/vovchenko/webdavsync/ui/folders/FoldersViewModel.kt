package org.vovchenko.webdavsync.ui.folders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.saf.SafFolderAccess
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.push.PushStatus
import javax.inject.Inject

data class FolderPairRow(
    val folderPair: FolderPairEntity,
    val accountName: String,
    /** WebDAV-Push subscription status; null while the feature is off. */
    val push: PushStatus.Line? = null,
)

@HiltViewModel
class FoldersViewModel @Inject constructor(
    private val folderPairRepository: FolderPairRepository,
    private val safFolderAccess: SafFolderAccess,
    accountRepository: WebDavAccountRepository,
    settingsRepository: SettingsRepository,
    pushRepository: PushRepository,
) : ViewModel() {

    val rows: StateFlow<List<FolderPairRow>> = combine(
        folderPairRepository.observeAll(),
        accountRepository.observeAll(),
        settingsRepository.settings,
        pushRepository.observeRegistrations(),
        pushRepository.observeEndpoints(),
    ) { pairs, accounts, settings, registrations, endpoints ->
        val namesById = accounts.associate { it.id to it.displayName }
        pairs.map { pair ->
            FolderPairRow(
                folderPair = pair,
                accountName = namesById[pair.accountId] ?: "Unknown account",
                push = PushStatus.lineFor(pair, settings, registrations, endpoints),
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEnabled(folderPair: FolderPairEntity, enabled: Boolean) {
        viewModelScope.launch { folderPairRepository.update(folderPair.copy(enabled = enabled)) }
    }

    /** Deletes the folder-pair config only (never touches local/remote files, see FoldersScreen's confirm dialog copy). */
    fun delete(folderPair: FolderPairEntity) {
        viewModelScope.launch {
            folderPairRepository.delete(folderPair)
            // Security audit finding #10: release the SAF grant once nothing references it anymore.
            val stillReferenced = folderPairRepository.observeAll().first()
                .any { it.localFolderUri == folderPair.localFolderUri }
            if (!stillReferenced) {
                runCatching { safFolderAccess.releaseAccess(android.net.Uri.parse(folderPair.localFolderUri)) }
            }
        }
    }
}

