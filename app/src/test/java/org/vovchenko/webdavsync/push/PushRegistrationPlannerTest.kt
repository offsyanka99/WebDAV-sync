package org.vovchenko.webdavsync.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.local.FolderPairEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointEntity
import org.vovchenko.webdavsync.data.local.push.PushEndpointState
import org.vovchenko.webdavsync.data.local.push.PushRegistrationEntity
import org.vovchenko.webdavsync.data.local.push.PushRegistrationState
import org.vovchenko.webdavsync.data.local.settings.AppSettings
import org.vovchenko.webdavsync.data.model.SyncMethod
import org.vovchenko.webdavsync.data.repository.PushRegistrationKey

class PushRegistrationPlannerTest {

    private val on = AppSettings(instantDownloadEnabled = true)
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `eligibility needs the switch, an enabled pair, and a download direction`() {
        val pairs = listOf(
            pair(1, "A", SyncMethod.TWO_WAY),
            pair(2, "B", SyncMethod.TO_DEVICE),
            pair(3, "C", SyncMethod.TO_CLOUD),
            pair(4, "D", SyncMethod.TWO_WAY, enabled = false),
        )
        assertEquals(
            mapOf(PushRegistrationKey(1, "A") to setOf(1L), PushRegistrationKey(1, "B") to setOf(2L)),
            PushRegistrationPlanner.desired(pairs, on),
        )
        assertTrue(PushRegistrationPlanner.desired(pairs, AppSettings()).isEmpty())
    }

    @Test
    fun `shared root gives one key, nested roots give two, paths are sanitized`() {
        val desired = PushRegistrationPlanner.desired(
            listOf(
                pair(1, "/Sync/", SyncMethod.TWO_WAY),
                pair(2, "Sync", SyncMethod.TO_DEVICE),
                pair(3, "Sync/Photos", SyncMethod.TWO_WAY),
                pair(4, "../escape", SyncMethod.TWO_WAY),
            ),
            on,
        )
        assertEquals(setOf(1L, 2L), desired[PushRegistrationKey(1, "Sync")])
        assertEquals(setOf(3L), desired[PushRegistrationKey(1, "Sync/Photos")])
        assertEquals(2, desired.size)
    }

    @Test
    fun `renewal window is min of 3 days and half the granted lifetime`() {
        val now = 100 * day
        fun active(registeredAt: Long, expiresAt: Long) =
            row(PushRegistrationState.ACTIVE).copy(registeredAt = registeredAt, expiresAt = expiresAt)

        // Granted 7 d: renew once less than 3 d remain.
        assertFalse(PushRegistrationPlanner.isRenewDue(active(now - 3 * day, now + 4 * day), now))
        assertTrue(PushRegistrationPlanner.isRenewDue(active(now - 5 * day, now + 2 * day), now))
        // Granted 1 h (server floor): renew in the last 30 min.
        val hour = 60L * 60 * 1000
        assertFalse(PushRegistrationPlanner.isRenewDue(active(now - 20 * 60_000, now + 40 * 60_000), now))
        assertTrue(PushRegistrationPlanner.isRenewDue(active(now - 40 * 60_000, now + 20 * 60_000), now))
        assertTrue(PushRegistrationPlanner.isRenewDue(active(now - hour, now - 1), now))
        assertFalse(PushRegistrationPlanner.isRenewDue(row(PushRegistrationState.FAILED), now))
    }

    @Test
    fun `active rows register again when due, on a new endpoint, or after backoff only`() {
        val now = 100 * day
        val endpoint = PushEndpointEntity(1, "account-1", PushEndpointState.READY, "V", "https://push/a", "k", "s", updatedAt = now - day)
        val fresh = row(PushRegistrationState.ACTIVE).copy(
            endpointUrl = "https://push/a", registeredAt = now - day / 2, expiresAt = now + 6 * day,
        )
        assertFalse(PushRegistrationPlanner.needsRegister(fresh, endpoint, now))
        assertTrue(PushRegistrationPlanner.needsRegister(fresh.copy(endpointUrl = "https://push/old"), endpoint, now))
        assertTrue(PushRegistrationPlanner.needsRegister(fresh.copy(registeredAt = now - 2 * day), endpoint, now))
        assertFalse(
            PushRegistrationPlanner.needsRegister(
                fresh.copy(endpointUrl = "https://push/old", nextAttemptAt = now + 1),
                endpoint,
                now,
            ),
        )
        assertTrue(PushRegistrationPlanner.needsRegister(row(PushRegistrationState.WAITING_FOR_ENDPOINT), endpoint, now))
        assertFalse(PushRegistrationPlanner.needsRegister(row(PushRegistrationState.UNSUPPORTED), endpoint, now))
    }

    @Test
    fun `suppression URLs exclude registrations shared with another pair`() {
        val rows = listOf(
            row(PushRegistrationState.ACTIVE).copy(id = 1, remotePath = "Solo", registrationUrl = "https://r/solo"),
            row(PushRegistrationState.ACTIVE).copy(id = 2, remotePath = "Shared", registrationUrl = "https://r/shared"),
            row(PushRegistrationState.FAILED).copy(id = 3, remotePath = "Other", registrationUrl = "https://r/failed"),
        )
        val desired = mapOf(
            PushRegistrationKey(1, "Solo") to setOf(10L),
            PushRegistrationKey(1, "Shared") to setOf(10L, 11L),
            PushRegistrationKey(1, "Other") to setOf(10L),
        )
        assertEquals(listOf("https://r/solo"), PushRegistrationPlanner.suppressionUrls(10L, rows, desired))
        assertTrue(PushRegistrationPlanner.suppressionUrls(11L, rows, desired).isEmpty())
    }

    @Test
    fun `discovery and endpoint requests follow state and backoff`() {
        val now = 100 * day
        val hints = setOf(PushRegistrationKey(1, "Root"))
        assertTrue(PushRegistrationPlanner.needsDiscovery(row(PushRegistrationState.DISCOVER), now, emptySet()))
        assertFalse(
            PushRegistrationPlanner.needsDiscovery(row(PushRegistrationState.UNSUPPORTED).copy(nextAttemptAt = now + 1), now, emptySet()),
        )
        val waiting = row(PushRegistrationState.WAITING_FOR_ROOT).copy(remotePath = "Root", nextAttemptAt = now + day)
        assertFalse(PushRegistrationPlanner.needsDiscovery(waiting, now, emptySet()))
        assertTrue(PushRegistrationPlanner.needsDiscovery(waiting, now, hints))
        assertFalse(PushRegistrationPlanner.needsDiscovery(row(PushRegistrationState.ACTIVE), now, hints))

        val ready = PushEndpointEntity(1, "account-1", PushEndpointState.READY, "V1", "https://p", "k", "s", updatedAt = now)
        assertTrue(PushRegistrationPlanner.shouldRequestEndpoint(null, "V1", now))
        assertFalse(PushRegistrationPlanner.shouldRequestEndpoint(ready, "V1", now))
        assertTrue(PushRegistrationPlanner.shouldRequestEndpoint(ready, "V2", now))
        val failed = ready.copy(state = PushEndpointState.FAILED)
        assertFalse(PushRegistrationPlanner.shouldRequestEndpoint(failed, "V1", now + 60_000))
        assertTrue(PushRegistrationPlanner.shouldRequestEndpoint(failed, "V1", now + PushRegistrationPlanner.REPEATED_BACKOFF_MS))
        assertFalse(PushRegistrationPlanner.shouldRequestEndpoint(ready.copy(state = PushEndpointState.REQUESTED), "V1", now + 60_000))

        assertEquals(now + PushRegistrationPlanner.FIRST_BACKOFF_MS, PushRegistrationPlanner.backoffAfter(row(PushRegistrationState.DISCOVER), now))
        assertEquals(now + PushRegistrationPlanner.REPEATED_BACKOFF_MS, PushRegistrationPlanner.backoffAfter(row(PushRegistrationState.FAILED), now))
    }

    private fun pair(id: Long, remote: String, method: SyncMethod, enabled: Boolean = true) = FolderPairEntity(
        id = id,
        accountId = 1,
        name = "P$id",
        remoteFolderPath = remote,
        localFolderUri = "content://p$id",
        syncMethod = method,
        enabled = enabled,
    )

    private fun row(state: PushRegistrationState) =
        PushRegistrationEntity(id = 1, accountId = 1, remotePath = "A", state = state)
}
