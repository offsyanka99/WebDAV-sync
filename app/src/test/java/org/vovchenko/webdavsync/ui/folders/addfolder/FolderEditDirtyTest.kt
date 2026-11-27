package org.vovchenko.webdavsync.ui.folders.addfolder

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.model.SyncMethod

class FolderEditDirtyTest {

    @Test
    fun `unsaved edits wait until the saved pair has loaded`() {
        val typed = AddEditFolderPairFormState(name = "Photos")
        assertFalse(folderEditsAreUnsaved(baseline = null, current = typed))
    }

    @Test
    fun `a loaded form with no edits is clean`() {
        val loaded = AddEditFolderPairFormState(
            name = "Photos",
            accountId = 3,
            remoteFolderPath = "/Photos",
            localFolderUri = "content://tree",
            syncMethod = SyncMethod.TO_CLOUD,
        )
        assertFalse(folderEditsAreUnsaved(baseline = loaded, current = loaded))
    }

    @Test
    fun `any field change is unsaved`() {
        val loaded = AddEditFolderPairFormState(name = "Photos", accountId = 3)
        assertTrue(folderEditsAreUnsaved(baseline = loaded, current = loaded.copy(name = "Camera")))
        assertTrue(folderEditsAreUnsaved(baseline = loaded, current = loaded.copy(instantUpload = true)))
    }
}
