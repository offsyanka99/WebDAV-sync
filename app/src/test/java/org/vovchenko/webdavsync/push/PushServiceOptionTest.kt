package org.vovchenko.webdavsync.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PushServiceOptionTest {

    private val fcm = PushServiceOption("org.vovchenko.webdavsync", "Google Play (FCM)", isGooglePlay = true)
    private val ntfy = PushServiceOption("io.heckel.ntfy", "ntfy", isGooglePlay = false)

    @Test
    fun `saved service wins while it is still available`() {
        assertEquals(ntfy, preselectPushService(listOf(fcm, ntfy), saved = "io.heckel.ntfy"))
    }

    @Test
    fun `Google Play is the default, also when an app is installed`() {
        assertEquals(fcm, preselectPushService(listOf(fcm, ntfy), saved = null))
        assertEquals(fcm, preselectPushService(listOf(fcm, ntfy), saved = "uninstalled.app"))
    }

    @Test
    fun `without Play services the first app is used, and nothing when the list is empty`() {
        assertEquals(ntfy, preselectPushService(listOf(ntfy), saved = null))
        assertNull(preselectPushService(emptyList(), saved = null))
    }
}
