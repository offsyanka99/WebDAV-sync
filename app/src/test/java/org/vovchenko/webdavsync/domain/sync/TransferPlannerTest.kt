package org.vovchenko.webdavsync.domain.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.domain.model.SyncAction

class TransferPlannerTest {

    @Test
    fun `unique size match becomes a move and drops the remote delete`() {
        val upload = SyncAction.UploadFile("new.txt")
        val delete = SyncAction.DeleteRemoteFile("old.txt", sizeBytes = 50)
        val plan = TransferPlanner.plan(
            fileActions = listOf(delete, upload),
            localSizes = mapOf("new.txt" to 50L),
            remoteSizes = mapOf("old.txt" to 50L),
        )
        assertEquals(1, plan.primary.size)
        val move = plan.primary.single() as SyncAction.MoveRemote
        assertEquals("new.txt", move.relativePath)
        assertEquals("old.txt", move.fromRelativePath)
        assertTrue(plan.guardedDeletes.isEmpty())
    }

    @Test
    fun `local delete waits until the matching download succeeds`() {
        val download = SyncAction.DownloadFile("new.txt", remoteSizeBytes = 80)
        val delete = SyncAction.DeleteLocalFile("old.txt", sizeBytes = 80)
        val plan = TransferPlanner.plan(
            fileActions = listOf(delete, download),
            localSizes = mapOf("old.txt" to 80L),
            remoteSizes = emptyMap(),
        )
        assertTrue(plan.primary.single() is SyncAction.DownloadFile)
        assertEquals("new.txt", plan.guardedDeletes.single().afterRelativePath)
        assertEquals("old.txt", plan.guardedDeletes.single().action.relativePath)
    }
}
