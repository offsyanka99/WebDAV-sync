package org.vovchenko.webdavsync.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.model.SyncMethod

class PushStatusTest {

    private val on = AppSettings(instantDownloadEnabled = true)
    private val pair = FolderPairEntity(
        id = 1, accountId = 1, name = "P", remoteFolderPath = "/Sync/", localFolderUri = "content://p",
    )

    private fun status(
        state: PushRegistrationState? = null,
        settings: AppSettings = on,
        target: FolderPairEntity = pair,
        endpoint: PushEndpointEntity? = null,
        row: (PushRegistrationEntity) -> PushRegistrationEntity = { it },
    ): String? = PushStatus.forPair(
        target,
        settings,
        listOfNotNull(state?.let { row(PushRegistrationEntity(accountId = 1, remotePath = "Sync", state = it)) }),
        listOfNotNull(endpoint),
        formatDate = { "7 Oct" },
    )

    @Test
    fun `nothing is shown while the feature is off`() {
        assertNull(status(PushRegistrationState.ACTIVE, settings = AppSettings()))
    }

    @Test
    fun `each state has a short line`() {
        assertEquals("Server push: active until 7 Oct", status(PushRegistrationState.ACTIVE) { it.copy(expiresAt = 1) })
        assertEquals("Server push: not supported by this server", status(PushRegistrationState.UNSUPPORTED))
        assertEquals("Server push: waiting for first sync", status(PushRegistrationState.WAITING_FOR_ROOT))
        assertEquals("Server push: setting up…", status())
        assertEquals("Server push: not used for To cloud", status(target = pair.copy(syncMethod = SyncMethod.TO_CLOUD)))
        assertEquals(
            "Server push: failed: Server limit for push subscriptions reached",
            status(PushRegistrationState.FAILED) { it.copy(lastError = PushRegistrationManager.ERR_RATE_LIMITED) },
        )
        assertEquals(
            "Server push: push service problem: No push service app selected",
            status(
                PushRegistrationState.WAITING_FOR_ENDPOINT,
                endpoint = PushEndpointEntity(
                    1, "account-1", PushEndpointState.FAILED, null,
                    lastError = PushRegistrationManager.ERR_NO_DISTRIBUTOR, updatedAt = 0,
                ),
            ),
        )
    }

    @Test
    fun `status never shows URLs`() {
        val text = status(PushRegistrationState.ACTIVE) {
            it.copy(expiresAt = 1, registrationUrl = "https://dav/push-subscriptions/x", endpointUrl = "https://ntfy.sh/up")
        }!!
        assertFalse(text.contains("https://"))
    }
}
