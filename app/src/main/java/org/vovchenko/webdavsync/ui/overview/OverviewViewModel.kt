package org.vovchenko.webdavsync.ui.overview

import android.content.Context
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
import org.vovchenko.webdavsync.domain.sync.SyncStatusDisplay
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import org.vovchenko.webdavsync.util.NetworkStatus
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
    /** Same labels as the home-screen widget ([SyncStatusDisplay]). */
    val statusDisplay: String
        get() = SyncStatusDisplay.resolve(lastSyncStatus, syncing).text
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
        // Still allow the mobile-data dialog even if a pass is already running (user may have
        // been surprised by auto/folder-watch sync). Only block starting another pass when busy
        // without needing a warning.
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val onCellular = NetworkStatus.isOnCellularData(context)
            if (settings.warnOnMobileNetwork && onCellular) {
                _showMobileDataWarning.value = true
                return@launch
            }
            if (uiState.value.syncing) return@launch
            syncScheduler.enqueueImmediateSync()
        }
    }

    fun confirmMobileDataSync() {
        _showMobileDataWarning.value = false
        viewModelScope.launch {
            if (uiState.value.syncing) return@launch
            syncScheduler.enqueueImmediateSync()
        }
    }

    fun dismissMobileDataWarning() {
        _showMobileDataWarning.value = false
    }
}
