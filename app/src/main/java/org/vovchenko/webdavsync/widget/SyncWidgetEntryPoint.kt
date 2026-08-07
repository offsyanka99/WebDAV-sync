package org.vovchenko.webdavsync.widget

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.vovchenko.webdavsync.data.repository.FolderPairRepository
import org.vovchenko.webdavsync.data.repository.SyncLogRepository
import org.vovchenko.webdavsync.sync.worker.SyncScheduler

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SyncWidgetEntryPoint {
    fun folderPairRepository(): FolderPairRepository
    fun syncLogRepository(): SyncLogRepository
    fun syncScheduler(): SyncScheduler
}
