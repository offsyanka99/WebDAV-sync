package org.vovchenko.webdavsync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.settings.AppSettings

class InstantWatchPolicyTest {

    @Test
    fun `per-pair instant upload watches even when auto-sync is off`() {
        val pair = pair(enabled = true, instantUpload = true)
        val settings = AppSettings(autoSyncEnabled = false, syncImmediatelyOnLocalChange = false)
        assertTrue(InstantWatchPolicy.shouldWatch(pair, settings))
    }

    @Test
    fun `global immediately-on-change watches enabled pairs`() {
        val pair = pair(enabled = true, instantUpload = false)
        val settings = AppSettings(autoSyncEnabled = true, syncImmediatelyOnLocalChange = true)
        assertTrue(InstantWatchPolicy.shouldWatch(pair, settings))
    }

    @Test
    fun `disabled pair is never watched`() {
        val pair = pair(enabled = false, instantUpload = true)
        val settings = AppSettings(autoSyncEnabled = true, syncImmediatelyOnLocalChange = true)
        assertFalse(InstantWatchPolicy.shouldWatch(pair, settings))
    }

    @Test
    fun `auto-sync off without instant upload does not watch`() {
        val pair = pair(enabled = true, instantUpload = false)
        val settings = AppSettings(autoSyncEnabled = false, syncImmediatelyOnLocalChange = true)
        assertFalse(InstantWatchPolicy.shouldWatch(pair, settings))
    }

    @Test
    fun `immediately-on-change off without instant upload does not watch`() {
        val pair = pair(enabled = true, instantUpload = false)
        val settings = AppSettings(autoSyncEnabled = true, syncImmediatelyOnLocalChange = false)
        assertFalse(InstantWatchPolicy.shouldWatch(pair, settings))
    }

    private fun pair(enabled: Boolean, instantUpload: Boolean) = FolderPairEntity(
        id = 1L,
        accountId = 1L,
        name = "Photos",
        remoteFolderPath = "/Photos",
        localFolderUri = TREE_URI,
        enabled = enabled,
        instantUpload = instantUpload,
    )

    private companion object {
        const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3APhotos"
    }
}
