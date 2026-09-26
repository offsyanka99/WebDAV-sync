package org.vovchenko.webdavsync.sync.control

import android.content.Intent
import org.vovchenko.webdavsync.MainActivity
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Proves a sync request was started inside this process.
 * The launcher activity is exported, so a bare extra is not enough.
 */
@Singleton
class SyncRequestToken @Inject constructor() {
    val value: String = UUID.randomUUID().toString()

    /** True only for an intent this process just built. Clears the extras either way. */
    fun accepts(intent: Intent): Boolean {
        val requested = intent.getBooleanExtra(MainActivity.EXTRA_REQUEST_SYNC, false)
        val token = intent.getStringExtra(EXTRA)
        if (intent.hasExtra(MainActivity.EXTRA_REQUEST_SYNC)) {
            intent.removeExtra(MainActivity.EXTRA_REQUEST_SYNC)
            intent.removeExtra(EXTRA)
        }
        return requested && token == value
    }

    companion object {
        const val EXTRA = "org.vovchenko.webdavsync.EXTRA_SYNC_TOKEN"
    }
}
