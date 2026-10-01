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
import org.vovchenko.webdavsync.domain.sync.SyncOverviewMetrics
import org.vovchenko.webdavsync.domain.sync.SyncStatusDisplay
import org.vovchenko.webdavsync.sync.control.ManualSyncDecision
import org.vovchenko.webdavsync.sync.control.ManualSyncStarter
import org.vovchenko.webdavsync.sync.control.SyncProgress
import org.vovchenko.webdavsync.sync.control.SyncUserMessage
import org.vovchenko.webdavsync.sync.control.UserSyncResult
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject

enum class OverviewSyncDialog {
    None,
    MobileData,
    UnmeteredOverride,
}

/** A one-shot notice. [id] changes so the same text can be shown again. */
data class SyncNotice(val id: Long, val text: String)

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
    /**
     * True when Recent changes numbers are live for this pass (in-memory counters),
     * not the last finished session summary from Room.
     */
    val liveProgress: Boolean = false,
) {
    /** Same labels as the home-screen widget ([SyncStatusDisplay]). */
    val statusDisplay: String
        get() = SyncStatusDisplay.resolve(lastSyncStatus, syncing).text

    companion object {
        fun from(
            metrics: SyncOverviewMetrics,
            accounts: List<WebDavAccountEntity>,
            live: SyncProgress.Counts? = null,
        ): OverviewUiState {
            val liveActive = live?.takeIf { it.active }
            return OverviewUiState(
                lastSyncAtMillis = metrics.lastSyncAtMillis,
                lastSyncDurationMs = metrics.lastSyncDurationMs,
                lastSyncStatus = metrics.lastSyncStatus,
                uploaded = liveActive?.uploaded ?: metrics.uploaded,
                downloaded = liveActive?.downloaded ?: metrics.downloaded,
                deletedDevice = liveActive?.deletedDevice ?: metrics.deletedDevice,
                deletedCloud = liveActive?.deletedCloud ?: metrics.deletedCloud,
                accounts = accounts,
                syncing = metrics.syncing,
                liveProgress = liveActive != null,
            )
        }
    }
}

@HiltViewModel
class OverviewViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val folderPairRepository: FolderPairRepository,
    private val syncLogRepository: SyncLogRepository,
    private val accountRepository: WebDavAccountRepository,
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val syncProgress: SyncProgress,
) : ViewModel() {

    private val _syncDialog = MutableStateFlow(OverviewSyncDialog.None)
    val syncDialog: StateFlow<OverviewSyncDialog> = _syncDialog.asStateFlow()

    private val _syncNotice = MutableStateFlow<SyncNotice?>(null)
    val syncNotice: StateFlow<SyncNotice?> = _syncNotice.asStateFlow()

    val uiState: StateFlow<OverviewUiState> = combine(
        folderPairRepository.observeAll(),
        syncLogRepository.observeRecent(100),
        accountRepository.observeAll(),
        syncScheduler.observeIsSyncActive(),
        syncProgress.counts,
    ) { folderPairs, logs, accounts, syncing, live ->
        OverviewUiState.from(
            metrics = SyncOverviewMetrics.from(folderPairs, logs, syncing = syncing),
            accounts = accounts,
            live = live,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OverviewUiState())

    /** Entry point for the Sync button and a widget tap that opened Overview. */
    fun requestSync() {
        viewModelScope.launch {
            if (syncScheduler.isSyncWorkerRunning()) {
                publish(syncScheduler.enqueueUserSync(bypassUnmetered = false))
                return@launch
            }
            val settings = settingsRepository.settings.first()
            when (ManualSyncStarter.prepareManualSync(context, settings)) {
                ManualSyncDecision.NeedsUnmeteredOverride -> {
                    _syncDialog.value = OverviewSyncDialog.UnmeteredOverride
                }
                ManualSyncDecision.NeedsMobileDataConfirm -> {
                    _syncDialog.value = OverviewSyncDialog.MobileData
                }
                ManualSyncDecision.Proceed -> publish(syncScheduler.enqueueUserSync(bypassUnmetered = false))
            }
        }
    }

    /** User accepted cellular use while Wi-Fi only is off. */
    fun confirmMobileDataSync() {
        _syncDialog.value = OverviewSyncDialog.None
        viewModelScope.launch {
            publish(syncScheduler.enqueueUserSync(bypassUnmetered = false))
        }
    }

    /** This sync may use mobile data. Charging is still required when that setting is on. */
    fun confirmSyncOnMobileData() {
        _syncDialog.value = OverviewSyncDialog.None
        viewModelScope.launch {
            publish(syncScheduler.enqueueUserSync(bypassUnmetered = true))
        }
    }

    /** Keep Wi-Fi only, and replace any waiting sync so it uses the current settings. */
    fun waitForWifi() {
        _syncDialog.value = OverviewSyncDialog.None
        viewModelScope.launch {
            publish(syncScheduler.enqueueUserSync(bypassUnmetered = false))
        }
    }

    fun dismissMobileDataWarning() {
        _syncDialog.value = OverviewSyncDialog.None
    }

    fun consumeSyncNotice() {
        _syncNotice.value = null
    }

    private fun publish(result: UserSyncResult) {
        val text = when (result) {
            UserSyncResult.Started -> null
            UserSyncResult.AlreadyRunning -> SyncUserMessage.ALREADY_RUNNING
            is UserSyncResult.Waiting -> SyncUserMessage.waiting(result.reasons)
        }
        if (text != null) {
            _syncNotice.value = SyncNotice(id = System.nanoTime(), text = text)
        }
    }
}
