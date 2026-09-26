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
import org.vovchenko.webdavsync.data.remote.WebDavPathSafety
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncFileStateRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.domain.sync.RemotePaths
import javax.inject.Inject

@HiltViewModel
class AccountsViewModel @Inject constructor(
    private val accountRepository: WebDavAccountRepository,
    private val folderPairRepository: FolderPairRepository,
    private val clientFactory: WebDavClientFactory,
    private val localFileIo: LocalFileIo,
    private val safFolderAccess: SafFolderAccess,
    private val syncFileStateRepository: SyncFileStateRepository,
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
     * [alsoDeleteData] is set, deletes only files this app has a baseline for. The SAF tree
     * root and an empty remote path (the account root) are never deleted.
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
                var tracked = 0
                try {
                pairs.forEach { pair ->
                    val localUri = Uri.parse(pair.localFolderUri)
                    val remoteRoot = runCatching { WebDavPathSafety.sanitize(pair.remoteFolderPath) }.getOrNull()
                    val rows = syncFileStateRepository.getForFolderPair(pair.id)
                        .sortedByDescending { row -> row.relativePath.count { it == '/' } }
                    if (rows.isEmpty()) {
                        failures++
                    }
                    for (row in rows) {
                        tracked++
                        val stillThere = localFileIo.statOrNull(localUri, row.relativePath) != null
                        if (stillThere && !localFileIo.delete(localUri, row.relativePath)) failures++
                        if (row.isDirectory) continue
                        if (remoteRoot.isNullOrEmpty() || client == null) {
                            failures++
                            continue
                        }
                        val remotePath = RemotePaths.join(remoteRoot, row.relativePath)
                        val remoteOk = runCatching { client.delete(remotePath).isSuccess }.getOrDefault(false)
                        if (!remoteOk) failures++
                    }

                    val stillReferenced = folderPairRepository.observeAll().first()
                        .any { it.id != pair.id && it.localFolderUri == pair.localFolderUri }
                    if (!stillReferenced) {
                        runCatching { safFolderAccess.releaseAccess(localUri) }
                    }
                }
                } finally {
                    client?.close()
                }
                _deleteWarning.value = when {
                    tracked == 0 ->
                        "No synced files were recorded for \"${account.displayName}\", so no files were deleted."
                    failures > 0 ->
                        "Some synced files for \"${account.displayName}\" could not be deleted ($failures item(s))."
                    else -> null
                }
            }
            accountRepository.deleteAccount(account)
        }
    }
}

