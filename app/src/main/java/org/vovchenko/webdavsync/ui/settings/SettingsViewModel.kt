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
import org.vovchenko.webdavsync.sync.worker.SyncScheduler
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncScheduler: SyncScheduler,
    private val diagnosticLogger: DiagnosticLogger,
) : ViewModel() {

    val settings: StateFlow<AppSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppSettings())

    private val _diagnosticLogExists = MutableStateFlow(diagnosticLogger.exists())
    val diagnosticLogExists: StateFlow<Boolean> = _diagnosticLogExists.asStateFlow()

    init {
        refreshDiagnosticLogExists()
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
}
