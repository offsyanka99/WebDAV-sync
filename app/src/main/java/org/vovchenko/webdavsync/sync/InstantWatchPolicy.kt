package org.vovchenko.webdavsync.sync

import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.settings.AppSettings

/** Which folder pairs get instant local-change detection (observer + content-URI job + cheap poll). */
object InstantWatchPolicy {
    fun shouldWatch(pair: FolderPairEntity, settings: AppSettings): Boolean {
        if (!pair.enabled) return false
        // The folder checkbox is explicit. Battery saver only suppresses the global switch.
        if (pair.instantUpload) return true
        if (settings.isBatterySaverProfile()) return false
        return settings.autoSyncEnabled && settings.syncImmediatelyOnLocalChange
    }
}
