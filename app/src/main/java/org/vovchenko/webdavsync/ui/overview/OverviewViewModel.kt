package org.vovchenko.webdavsync.ui.overview

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.domain.sync.RecentChangesCalculator
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject

data class OverviewUiState(
    val lastSyncAtMillis: Long? = null,
    val lastSyncDurationMs: Long? = null,
    /** Last finished sync status from DB (OK / ERROR / …), or null if never synced. */
    val lastSyncStatus: String? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedDevice: Int = 0,
    val deletedCloud: Int = 0,
    val accounts: List<WebDavAccountEntity> = emptyList(),
    /** True while a sync worker is RUNNING (not merely ENQUEUED waiting on constraints). */
    val syncing: Boolean = false,
) {
    /** Value shown in the Status row — live "in process" only while transfers are active. */
    val statusDisplay: String
        get() = if (syncing) "Sync in process..." else (lastSyncStatus ?: "Ready")
}

@HiltViewModel
class OverviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderPairRepository: FolderPairRepository,
    private val syncLogRepository: SyncLogRepository,
    private val accountRepository: WebDavAccountRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    private val _showMobileDataWarning = MutableStateFlow(false)
    val showMobileDataWarning: StateFlow<Boolean> = _showMobileDataWarning.asStateFlow()

    val uiState: StateFlow<OverviewUiState> = combine(
        folderPairRepository.observeAll(),
        syncLogRepository.observeRecent(100),
        accountRepository.observeAll(),
        syncScheduler.observeIsSyncActive(),
    ) { folderPairs, logs, accounts, syncing ->
        val mostRecentPair = folderPairs.filter { it.lastSyncAt != null }.maxByOrNull { it.lastSyncAt!! }
        val recent = RecentChangesCalculator.fromLogs(logs)

        OverviewUiState(
            lastSyncAtMillis = mostRecentPair?.lastSyncAt,
            lastSyncDurationMs = mostRecentPair?.lastSyncDurationMs,
            lastSyncStatus = mostRecentPair?.lastSyncStatus,
            uploaded = recent.uploaded,
            downloaded = recent.downloaded,
            deletedDevice = recent.deletedDevice,
            deletedCloud = recent.deletedCloud,
            accounts = accounts,
            syncing = syncing,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewUiState())

    /** Entry point for the Sync button — may show a mobile-data warning first. */
    fun requestSync() {
        if (uiState.value.syncing) return
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            if (settings.warnOnMobileNetwork && isOnCellularData()) {
                _showMobileDataWarning.value = true
            } else {
                syncScheduler.enqueueImmediateSync()
            }
        }
    }

    fun confirmMobileDataSync() {
        _showMobileDataWarning.value = false
        viewModelScope.launch { syncScheduler.enqueueImmediateSync() }
    }

    fun dismissMobileDataWarning() {
        _showMobileDataWarning.value = false
    }

    private fun isOnCellularData(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return cellular && !wifi && !ethernet
    }
}
