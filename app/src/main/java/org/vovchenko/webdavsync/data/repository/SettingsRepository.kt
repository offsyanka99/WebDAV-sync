package org.vovchenko.webdavsync.data.repository

import kotlinx.coroutines.flow.Flow
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.local.settings.SettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: SettingsDataStore,
) {
    val settings: Flow<AppSettings> = dataStore.settings

    suspend fun update(transform: (AppSettings) -> AppSettings) = dataStore.update(transform)
}
