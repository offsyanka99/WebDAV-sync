package org.vovchenko.webdavsync.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.WebDavAccountEntity
import org.vovchenko.webdavsync.data.model.SyncEventType
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject

data class OverviewUiState(
    val lastSyncAtMillis: Long? = null,
    val lastSyncDurationMs: Long? = null,
    val lastSyncStatus: String? = null,
    val uploaded: Int = 0,
    val downloaded: Int = 0,
    val deletedDevice: Int = 0,
    val deletedCloud: Int = 0,
    val accounts: List<WebDavAccountEntity> = emptyList(),
    val syncing: Boolean = false,
)

@HiltViewModel
class OverviewViewModel @Inject constructor(
    private val folderPairRepository: FolderPairRepository,
    private val syncLogRepository: SyncLogRepository,
    private val accountRepository: WebDavAccountRepository,
    private val syncScheduler: SyncScheduler,
) : ViewModel() {

    val uiState: StateFlow<OverviewUiState> = combine(
        folderPairRepository.observeAll(),
        syncLogRepository.observeRecent(100),
        accountRepository.observeAll(),
    ) { folderPairs, logs, accounts ->
        val mostRecentPair = folderPairs.filter { it.lastSyncAt != null }.maxByOrNull { it.lastSyncAt!! }

        // All the counter rows from one sync pass share the same timestamp (SyncEngine.logOutcome).
        val latestSyncEndTimestamp = logs.firstOrNull { it.eventType == SyncEventType.SYNC_END }?.timestamp
        val latestBatch = logs.filter { it.timestamp == latestSyncEndTimestamp }

        OverviewUiState(
            lastSyncAtMillis = mostRecentPair?.lastSyncAt,
            lastSyncDurationMs = mostRecentPair?.lastSyncDurationMs,
            lastSyncStatus = mostRecentPair?.lastSyncStatus,
            uploaded = latestBatch.firstOrNull { it.eventType == SyncEventType.UPLOAD }?.fileCount ?: 0,
            downloaded = latestBatch.firstOrNull { it.eventType == SyncEventType.DOWNLOAD }?.fileCount ?: 0,
            deletedDevice = latestBatch.firstOrNull { it.eventType == SyncEventType.DELETE_DEVICE }?.fileCount ?: 0,
            deletedCloud = latestBatch.firstOrNull { it.eventType == SyncEventType.DELETE_CLOUD }?.fileCount ?: 0,
            accounts = accounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewUiState())

    fun syncNow() {
        viewModelScope.launch { syncScheduler.enqueueImmediateSync() }
    }
}
