package org.vovchenko.webdavsync.sync.control

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.vovchenko.webdavsync.data.remote.InFlightCallRegistry

class SyncControlTest {

    @Test
    fun `pause and resume toggle state`() {
        val control = SyncControl(InFlightCallRegistry())
        assertFalse(control.isPaused)
        control.pause()
        assertTrue(control.isPaused)
        control.resume()
        assertFalse(control.isPaused)
    }

    @Test
    fun `cancel clears pause and sets cancelled`() {
        val control = SyncControl(InFlightCallRegistry())
        control.pause()
        control.cancel()
        assertTrue(control.isCancelled)
        assertFalse(control.isPaused)
        assertTrue(control.shouldStop())
    }

    @Test
    fun `reset clears pause and cancel`() {
        val control = SyncControl(InFlightCallRegistry())
        control.pause()
        control.cancel()
        control.reset()
        assertFalse(control.isPaused)
        assertFalse(control.isCancelled)
        assertFalse(control.shouldStop())
    }

    @Test
    fun `session tracks active pass and follow-up request`() {
        val control = SyncControl(InFlightCallRegistry())
        assertFalse(control.isSessionActive)
        control.beginSession()
        assertTrue(control.isSessionActive)
        control.requestFollowUpSync(7L)
        assertEquals(setOf(7L), control.endSession().pairIds)
        assertFalse(control.isSessionActive)
        // Second end without request → no follow-up.
        control.beginSession()
        assertTrue(control.endSession().isEmpty)
    }

    @Test
    fun `tryBeginSession is single-flight across concurrent workers`() {
        val control = SyncControl(InFlightCallRegistry())
        assertTrue(control.tryBeginSession())
        assertTrue(control.isSessionActive)
        // Second worker (e.g. periodic while manual is running) must not claim the session.
        assertFalse(control.tryBeginSession())
        assertTrue(control.isSessionActive)
        assertTrue(control.endSession().isEmpty)
        assertFalse(control.isSessionActive)
        // After release, another worker can start.
        assertTrue(control.tryBeginSession())
        assertTrue(control.endSession().isEmpty)
    }

    @Test
    fun `follow-up requested before the session starts is dropped`() {
        val control = SyncControl(InFlightCallRegistry())
        control.requestFollowUpSync(3L)
        assertTrue(control.tryBeginSession())
        // The pass about to scan already includes that edit.
        assertTrue(control.endSession().isEmpty)
    }

    @Test
    fun `follow-up during a session keeps the pair id`() {
        val control = SyncControl(InFlightCallRegistry())
        assertTrue(control.tryBeginSession())
        control.requestFollowUpSync(4L)
        control.requestFollowUpSync(9L)
        val followUp = control.endSession()
        assertEquals(setOf(4L, 9L), followUp.pairIds)
        assertFalse(followUp.allPairs)
    }

    @Test
    fun `awaitWhilePaused returns after resume`() = runBlocking {
        val control = SyncControl(InFlightCallRegistry())
        control.pause()
        val waiter = async {
            withTimeout(2_000) { control.awaitWhilePaused() }
        }
        delay(50)
        control.resume()
        waiter.await()
        assertFalse(control.isPaused)
    }

    @Test
    fun `awaitWhilePaused returns after cancel`() = runBlocking {
        val control = SyncControl(InFlightCallRegistry())
        control.pause()
        val waiter = async {
            withTimeout(2_000) { control.awaitWhilePaused() }
        }
        delay(50)
        control.cancel()
        waiter.await()
        assertTrue(control.isCancelled)
    }
}
