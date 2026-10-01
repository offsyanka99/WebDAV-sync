package org.vovchenko.webdavsync.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.PushRepository
import org.vovchenko.webdavsync.push.PushDistributor
import org.vovchenko.webdavsync.push.PushServiceOption
import org.vovchenko.webdavsync.push.preselectPushService
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val diagnosticLogger: DiagnosticLogger,
    private val pushDistributor: PushDistributor,
    private val pushRepository: PushRepository,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _diagnosticLogExists = MutableStateFlow(diagnosticLogger.exists())
    val diagnosticLogExists: StateFlow<Boolean> = _diagnosticLogExists.asStateFlow()

    /** App name of the chosen push service; null when none is chosen or it was uninstalled. */
    private val _pushServiceLabel = MutableStateFlow<String?>(null)
    val pushServiceLabel: StateFlow<String?> = _pushServiceLabel.asStateFlow()

    init {
        refreshDiagnosticLogExists()
        refreshPushService()
    }

    /** Applies [transform] to the stored settings, then reschedules periodic work (scheduling-relevant fields may have changed). */
    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.update(transform)
            syncScheduler.reschedulePeriodicSync()
        }
    }

    /**
     * Turns diagnostic file logging on/off. When enabling, writes a banner line so "Share log"
     * becomes available immediately; when disabling, writes a final line first so the reason is
     * captured.
     */
    fun setDiagnosticLogEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                diagnosticLogger.setEnabled(true)
                settingsRepository.update { it.copy(diagnosticLogEnabled = true) }
                diagnosticLogger.i(
                    "Settings",
                    "Diagnostic logging enabled (app ${org.vovchenko.webdavsync.BuildConfig.VERSION_NAME})",
                )
            } else {
                diagnosticLogger.setEnabled(true)
                diagnosticLogger.i("Settings", "Diagnostic logging disabled by user")
                diagnosticLogger.setEnabled(false)
                settingsRepository.update { it.copy(diagnosticLogEnabled = false) }
            }
            refreshDiagnosticLogExists()
            syncScheduler.reschedulePeriodicSync()
        }
    }

    fun clearDiagnosticLog() {
        diagnosticLogger.clear()
        if (diagnosticLogger.isEnabled()) {
            diagnosticLogger.i("Settings", "Diagnostic log cleared")
        }
        refreshDiagnosticLogExists()
    }

    fun refreshDiagnosticLogExists() {
        _diagnosticLogExists.value = diagnosticLogger.exists()
    }

    /** Push services for the WebDAV-Push dialog and the one to preselect. Read fresh each time it opens. */
    fun pushServiceChoice(): PushServiceChoice {
        val options = pushDistributor.options()
        return PushServiceChoice(options, preselectPushService(options, pushDistributor.savedDistributor()))
    }

    /**
     * Saves the WebDAV-Push dialog. Turning on with a (new) push service drops the old endpoints so
     * every account asks the chosen service for a fresh one.
     */
    fun applyPushSettings(enabled: Boolean, service: PushServiceOption?) {
        viewModelScope.launch {
            if (!enabled || service == null) {
                settingsRepository.update { it.copy(instantDownloadEnabled = false) }
                diagnosticLogger.i("Settings", "WebDAV-Push off")
                refreshPushService()
                return@launch
            }
            val changed = service.packageName != pushDistributor.savedDistributor()
            if (changed) pushDistributor.select(service.packageName)
            val wasEnabled = settings.value.instantDownloadEnabled
            if (changed || !wasEnabled) pushRepository.clearEndpoints()
            pushRepository.clearBackoff()
            settingsRepository.update { it.copy(instantDownloadEnabled = true) }
            syncScheduler.enqueuePushReconcile()
            diagnosticLogger.i(
                "Settings",
                "WebDAV-Push on via ${if (service.isGooglePlay) "Google Play (FCM)" else "UnifiedPush app"}" +
                    if (changed) " (service changed)" else "",
            )
            refreshPushService()
        }
    }

    /** Re-reads the chosen push service; the user can uninstall it while the app is away. */
    fun refreshPushService() {
        val chosen = pushDistributor.ackDistributor() ?: pushDistributor.savedDistributor()
        _pushServiceLabel.value = pushDistributor.options().firstOrNull { it.packageName == chosen }?.label
    }
}

data class PushServiceChoice(val options: List<PushServiceOption>, val preselected: PushServiceOption?)
