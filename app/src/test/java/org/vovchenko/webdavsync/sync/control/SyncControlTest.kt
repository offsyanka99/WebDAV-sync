package org.vovchenko.webdavsync.sync.control

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncControlTest {

    @Test
    fun `pause and resume toggle state`() {
        val control = SyncControl()
        assertFalse(control.isPaused)
        control.pause()
        assertTrue(control.isPaused)
        control.resume()
        assertFalse(control.isPaused)
    }

    @Test
    fun `cancel clears pause and sets cancelled`() {
        val control = SyncControl()
        control.pause()
        control.cancel()
        assertTrue(control.isCancelled)
        assertFalse(control.isPaused)
        assertTrue(control.shouldStop())
    }

    @Test
    fun `reset clears pause and cancel`() {
        val control = SyncControl()
        control.pause()
        control.cancel()
        control.reset()
        assertFalse(control.isPaused)
        assertFalse(control.isCancelled)
        assertFalse(control.shouldStop())
    }

    @Test
    fun `session tracks active pass and follow-up request`() {
        val control = SyncControl()
        assertFalse(control.isSessionActive)
        control.beginSession()
        assertTrue(control.isSessionActive)
        control.requestFollowUpSync()
        assertTrue(control.endSession())
        assertFalse(control.isSessionActive)
        // Second end without request → no follow-up.
        control.beginSession()
        assertFalse(control.endSession())
    }

    @Test
    fun `tryBeginSession is single-flight across concurrent workers`() {
        val control = SyncControl()
        assertTrue(control.tryBeginSession())
        assertTrue(control.isSessionActive)
        // Second worker (e.g. periodic while manual is running) must not claim the session.
        assertFalse(control.tryBeginSession())
        assertTrue(control.isSessionActive)
        assertFalse(control.endSession())
        assertFalse(control.isSessionActive)
        // After release, another worker can start.
        assertTrue(control.tryBeginSession())
        assertFalse(control.endSession())
    }

    @Test
    fun `tryBeginSession preserves follow-up requested before session start`() {
        val control = SyncControl()
        control.requestFollowUpSync()
        assertTrue(control.tryBeginSession())
        // Flag set before begin must still be returned by endSession.
        assertTrue(control.endSession())
    }

    @Test
    fun `awaitWhilePaused returns after resume`() = runBlocking {
        val control = SyncControl()
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
        val control = SyncControl()
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
