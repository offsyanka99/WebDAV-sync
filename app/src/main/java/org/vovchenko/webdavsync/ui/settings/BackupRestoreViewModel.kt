package org.vovchenko.webdavsync.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.saf.SafFolderAccess
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.data.remote.WebDavPathSafety
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SettingsRepository
import org.vovchenko.webdavsync.data.repository.WebDavAccountRepository
import javax.inject.Inject

data class BackupRestoreUiState(
    val isWorking: Boolean = false,
    val message: String? = null,
)

private const val BACKUP_VERSION = 1

@HiltViewModel
class BackupRestoreViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val folderPairRepository: FolderPairRepository,
    private val accountRepository: WebDavAccountRepository,
    private val safFolderAccess: SafFolderAccess,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BackupRestoreUiState())
    val uiState: StateFlow<BackupRestoreUiState> = _uiState.asStateFlow()

    fun clearMessage() { _uiState.value = _uiState.value.copy(message = null) }

    /** Writes settings + folder pairs (no credentials/certificates) to [uri] as JSON (plan Phase 8). */
    fun exportBackup(uri: Uri) {
        _uiState.value = _uiState.value.copy(isWorking = true)
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val folderPairs = folderPairRepository.observeAll().first()
            val accounts = accountRepository.observeAll().first().associateBy { it.id }

            val json = JSONObject().apply {
                put("version", BACKUP_VERSION)
                put("settings", settings.toJson())
                put(
                    "folderPairs",
                    JSONArray(
                        folderPairs.map { pair ->
                            val account = accounts[pair.accountId]
                            pair.toJson(accountDisplayName = account?.displayName, accountBaseUrl = account?.baseUrl)
                        },
                    ),
                )
            }

            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(json.toString(2).toByteArray())
                }
            }.fold(
                onSuccess = { _uiState.value = BackupRestoreUiState(message = "Backup exported") },
                onFailure = { _uiState.value = BackupRestoreUiState(message = "Export failed: ${it.message}") },
            )
        }
    }

    /**
     * Restores settings and **replaces** all folder pairs from [uri] (does not append).
     * Pairs whose account can't be matched locally (by server URL) are skipped, since credentials
     * are never exported (§4.6) — the matching account must already exist on this device.
     */
    fun importBackup(uri: Uri) {
        _uiState.value = _uiState.value.copy(isWorking = true)
        viewModelScope.launch {
            val result = runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    // Security audit finding #14: cap the read size so a corrupted/malicious backup
                    // file can't exhaust memory before it's even parsed as JSON.
                    input.readBytes(maxSize = MAX_IMPORT_BYTES)
                } ?: error("Could not read the selected file")
                val json = JSONObject(String(text))

                val version = json.optInt("version", 0)
                require(version in 1..BACKUP_VERSION) { "Unsupported backup format version: $version" }

                json.optJSONObject("settings")?.let { settingsJson ->
                    settingsRepository.update { settingsJson.toAppSettings(it) }
                }

                val accounts = accountRepository.observeAll().first()
                val pairsJson = json.optJSONArray("folderPairs") ?: JSONArray()
                val toRestore = mutableListOf<FolderPairEntity>()
                var skipped = 0
                for (i in 0 until pairsJson.length()) {
                    val pairJson = pairsJson.getJSONObject(i)
                    val accountBaseUrl = pairJson.optString("accountBaseUrl", "")
                    val account = accounts.firstOrNull { it.baseUrl == accountBaseUrl }
                    val localFolderUri = pairJson.optString("localFolderUri", "")
                    val remoteFolderPath = pairJson.optString("remoteFolderPath", "")
                    val hasSafeRemotePath = runCatching { WebDavPathSafety.sanitize(remoteFolderPath) }.isSuccess
                    val hasLocalAccess = localFolderUri.isNotBlank() &&
                        runCatching { safFolderAccess.hasAccess(Uri.parse(localFolderUri)) }.getOrDefault(false)
                    if (account == null || !hasSafeRemotePath || !hasLocalAccess) {
                        skipped++
                        continue
                    }
                    toRestore += pairJson.toFolderPairEntity(account.id)
                }

                // Replace (not append): drop current pairs first so restore overrides Folders.
                val existing = folderPairRepository.observeAll().first()
                existing.forEach { pair ->
                    runCatching { safFolderAccess.releaseAccess(Uri.parse(pair.localFolderUri)) }
                }
                folderPairRepository.deleteAll()

                toRestore.forEach { folderPairRepository.add(it) }
                val restored = toRestore.size
                "Restored $restored folder pair(s)" +
                    if (skipped > 0) ", skipped $skipped (missing account/folder access, or unsafe path)" else ""
            }
            _uiState.value = BackupRestoreUiState(
                message = result.getOrElse { "Import failed: ${it.message}" },
            )
        }
    }
}

/** Reads at most [maxSize] bytes, throwing if the stream has more (defends against oversized/corrupted import files). */
private fun java.io.InputStream.readBytes(maxSize: Long): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(8192)
    var total = 0L
    while (true) {
        val read = read(chunk)
        if (read == -1) break
        total += read
        require(total <= maxSize) { "Backup file is too large (> $maxSize bytes)" }
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}

private const val MAX_IMPORT_BYTES = 5L * 1024 * 1024

private fun AppSettings.toJson(): JSONObject = JSONObject().apply {
    put("uploadSizeLimitBytes", uploadSizeLimitBytes ?: JSONObject.NULL)
    put("downloadSizeLimitBytes", downloadSizeLimitBytes ?: JSONObject.NULL)
    put("warnOnMobileNetwork", warnOnMobileNetwork)
    put("wifiOnly", wifiOnly)
    put("allowParallelTransfers", allowParallelTransfers)
    put("autoSyncEnabled", autoSyncEnabled)
    put("autoSyncIntervalMinutes", autoSyncIntervalMinutes)
    put("syncImmediatelyOnLocalChange", syncImmediatelyOnLocalChange)
    put("onlyWhileCharging", onlyWhileCharging)
    put("syncEvenWhenBatteryLow", syncEvenWhenBatteryLow)
    put("retryAttempts", retryAttempts)
    put("retryWaitMinutes", retryWaitMinutes)
    put("batteryOptimizationDisabled", batteryOptimizationDisabled)
    put("autoStartOnBoot", autoStartOnBoot)
    put("diagnosticLogEnabled", diagnosticLogEnabled)
}

private fun JSONObject.toAppSettings(current: AppSettings): AppSettings = current.copy(
    uploadSizeLimitBytes = if (isNull("uploadSizeLimitBytes")) null else optLong("uploadSizeLimitBytes"),
    downloadSizeLimitBytes = if (isNull("downloadSizeLimitBytes")) null else optLong("downloadSizeLimitBytes"),
    warnOnMobileNetwork = optBoolean("warnOnMobileNetwork", current.warnOnMobileNetwork),
    wifiOnly = optBoolean("wifiOnly", current.wifiOnly),
    allowParallelTransfers = optBoolean("allowParallelTransfers", current.allowParallelTransfers),
    autoSyncEnabled = optBoolean("autoSyncEnabled", current.autoSyncEnabled),
    autoSyncIntervalMinutes = optInt("autoSyncIntervalMinutes", current.autoSyncIntervalMinutes),
    syncImmediatelyOnLocalChange = optBoolean("syncImmediatelyOnLocalChange", current.syncImmediatelyOnLocalChange),
    onlyWhileCharging = optBoolean("onlyWhileCharging", current.onlyWhileCharging),
    syncEvenWhenBatteryLow = optBoolean("syncEvenWhenBatteryLow", current.syncEvenWhenBatteryLow),
    retryAttempts = optInt("retryAttempts", current.retryAttempts),
    retryWaitMinutes = optInt("retryWaitMinutes", current.retryWaitMinutes),
    batteryOptimizationDisabled = optBoolean("batteryOptimizationDisabled", current.batteryOptimizationDisabled),
    autoStartOnBoot = optBoolean("autoStartOnBoot", current.autoStartOnBoot),
    diagnosticLogEnabled = optBoolean("diagnosticLogEnabled", current.diagnosticLogEnabled),
)

private fun FolderPairEntity.toJson(accountDisplayName: String?, accountBaseUrl: String?): JSONObject = JSONObject().apply {
    put("name", name)
    put("accountDisplayName", accountDisplayName ?: JSONObject.NULL)
    put("accountBaseUrl", accountBaseUrl ?: JSONObject.NULL)
    put("remoteFolderPath", remoteFolderPath)
    put("localFolderUri", localFolderUri)
    put("syncMethod", syncMethod.name)
    put("excludeHiddenFiles", excludeHiddenFiles)
    put("excludedSubfolders", JSONArray(excludedSubfolders))
    put("deleteEmptyFolders", deleteEmptyFolders)
    put("instantUpload", instantUpload)
    put("enabled", enabled)
}

private fun JSONObject.toFolderPairEntity(accountId: Long): FolderPairEntity {
    val excluded = optJSONArray("excludedSubfolders")
    val excludedList = if (excluded != null) List(excluded.length()) { excluded.getString(it) } else emptyList()
    return FolderPairEntity(
        accountId = accountId,
        name = getString("name"),
        remoteFolderPath = getString("remoteFolderPath"),
        localFolderUri = getString("localFolderUri"),
        syncMethod = runCatching { SyncMethod.valueOf(getString("syncMethod")) }.getOrDefault(SyncMethod.TWO_WAY),
        excludeHiddenFiles = optBoolean("excludeHiddenFiles", true),
        excludedSubfolders = excludedList,
        deleteEmptyFolders = optBoolean("deleteEmptyFolders", false),
        instantUpload = optBoolean("instantUpload", false),
        enabled = optBoolean("enabled", true),
    )
}
