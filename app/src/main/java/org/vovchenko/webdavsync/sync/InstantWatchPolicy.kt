package org.vovchenko.webdavsync.sync

import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.settings.AppSettings

/** Which folder pairs get instant local-change detection (observer + content-URI job + cheap poll). */
object InstantWatchPolicy {
    fun shouldWatch(pair: FolderPairEntity, settings: AppSettings): Boolean {
        if (!pair.enabled || settings.isBatterySaverProfile()) return false
        return pair.instantUpload ||
            (settings.autoSyncEnabled && settings.syncImmediatelyOnLocalChange)
    }
}
