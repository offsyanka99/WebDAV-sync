package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.local.saf.LocalFileEntry
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.domain.model.SyncAction

class SyncDiffCalculatorTest {

    private val calculator = SyncDiffCalculator()

    @Test
    fun `creates local directory for a remote-only folder in two-way sync`() {
        val local = emptyList<LocalFileEntry>()
        val remote = listOf(RemoteFileEntry("Photos", isDirectory = true, sizeBytes = 0, lastModifiedEpochMillis = null, etag = null))

        val actions = calculator.computeActions(SyncMethod.TWO_WAY, local, remote, baseline = emptyList())

        assertTrue(actions.contains(SyncAction.CreateLocalDirectory("Photos")))
    }

    @Test
    fun `creates remote directory for a local-only folder in two-way sync`() {
        val local = listOf(LocalFileEntry("Docs", isDirectory = true, sizeBytes = 0, lastModifiedEpochMillis = 0))
        val remote = emptyList<RemoteFileEntry>()

        val actions = calculator.computeActions(SyncMethod.TWO_WAY, local, remote, baseline = emptyList())

        assertTrue(actions.contains(SyncAction.CreateRemoteDirectory("Docs")))
    }

    @Test
    fun `to-device sync method never creates remote directories`() {
        val local = listOf(LocalFileEntry("Docs", isDirectory = true, sizeBytes = 0, lastModifiedEpochMillis = 0))
        val remote = emptyList<RemoteFileEntry>()

        val actions = calculator.computeActions(SyncMethod.TO_DEVICE, local, remote, baseline = emptyList())

        assertTrue(actions.none { it is SyncAction.CreateRemoteDirectory })
    }

    @Test
    fun `combines a new upload with a new local directory`() {
        val local = listOf(
            LocalFileEntry("Docs", isDirectory = true, sizeBytes = 0, lastModifiedEpochMillis = 0),
            LocalFileEntry("Docs/report.txt", isDirectory = false, sizeBytes = 42, lastModifiedEpochMillis = 1000),
        )
        val remote = listOf(RemoteFileEntry("Incoming", isDirectory = true, sizeBytes = 0, lastModifiedEpochMillis = null, etag = null))

        val actions = calculator.computeActions(SyncMethod.TWO_WAY, local, remote, baseline = emptyList())

        assertTrue(actions.contains(SyncAction.CreateRemoteDirectory("Docs")))
        assertTrue(actions.contains(SyncAction.CreateLocalDirectory("Incoming")))
        assertTrue(actions.contains(SyncAction.UploadFile("Docs/report.txt")))
    }
}
