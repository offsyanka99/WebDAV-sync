package org.vovchenko.webdavsync.ui.accounts

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.local.saf.LocalFileIo
import org.vovchenko.webdavsync.data.local.saf.SafFolderAccess
import org.vovchenko.webdavsync.data.remote.WebDavClientFactory
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: WebDavAccountRepository,
    private val folderPairRepository: FolderPairRepository,
    private val clientFactory: WebDavClientFactory,
    private val localFileIo: LocalFileIo,
    private val safFolderAccess: SafFolderAccess,
) : ViewModel() {

    val accounts: StateFlow<List<WebDavAccountEntity>> =
        accountRepository.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _deleteWarning = MutableStateFlow<String?>(null)
    /** Set after [deleteAccount] if any local/remote data deletion partially failed (finding #2 — never swallow silently). */
    val deleteWarning: StateFlow<String?> = _deleteWarning.asStateFlow()

    fun clearDeleteWarning() { _deleteWarning.value = null }

    /** Number of folder pairs attached to [account] — used to size the delete-confirmation warning. */
    suspend fun folderPairCount(account: WebDavAccountEntity): Int = accountRepository.folderPairCount(account.id)

    /**
     * Deletes [account] (cascades its folder pairs + sync state at the DB level). When
     * [alsoDeleteData] is set, also best-effort deletes each of its folder pairs' local folder
     * *contents* (never the granted tree-root document itself, see [LocalFileIo.deleteContents])
     * and remote root folder before removing the account row (plan §4.5).
     */
    fun deleteAccount(account: WebDavAccountEntity, alsoDeleteData: Boolean) {
        viewModelScope.launch {
            if (alsoDeleteData) {
                val pairs = folderPairRepository.observeByAccount(account.id).first()
                val credentials = accountRepository.getCredentials(account.id)
                val authScheme = account.authScheme
                val trustedCert = accountRepository.getTrustedCertificate(account.id)
                val client = if (credentials != null && authScheme != null) {
                    clientFactory.create(account.baseUrl, authScheme, credentials, trustedCert)
                } else {
                    null
                }
                var failures = 0
                pairs.forEach { pair ->
                    val localUri = Uri.parse(pair.localFolderUri)
                    val localOk = runCatching { localFileIo.deleteContents(localUri, "") }.getOrDefault(false)
                    if (!localOk) failures++

                    if (client != null) {
                        val remoteOk = runCatching { client.delete(pair.remoteFolderPath).isSuccess }.getOrDefault(false)
                        if (!remoteOk) failures++
                    }

                    // Only release the SAF grant if no other (surviving) folder pair still uses it.
                    val stillReferenced = folderPairRepository.observeAll().first()
                        .any { it.id != pair.id && it.localFolderUri == pair.localFolderUri }
                    if (!stillReferenced) {
                        runCatching { safFolderAccess.releaseAccess(localUri) }
                    }
                }
                if (failures > 0) {
                    _deleteWarning.value = "Some local/cloud data for \"${account.displayName}\" could not be deleted ($failures item(s))."
                }
            }
            accountRepository.deleteAccount(account)
        }
    }
}

