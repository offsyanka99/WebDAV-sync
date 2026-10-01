package org.vovchenko.webdavsync.push

import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage
import org.vovchenko.webdavsync.data.local.diagnostics.DiagnosticLogger
import javax.inject.Inject

/**
 * Receives UnifiedPush events. Callbacks can run on the main thread, so each one hops to the app
 * scope; [PushEventHandler] only touches the database and WorkManager, never the network.
 */
@AndroidEntryPoint
class WebDavPushService : PushService() {

    @Inject lateinit var handler: PushEventHandler
    @Inject lateinit var scope: CoroutineScope
    @Inject lateinit var diagnosticLogger: DiagnosticLogger

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val keys = endpoint.pubKeySet
        launchSafely("onNewEndpoint") {
            handler.onNewEndpoint(
                instance = instance,
                url = endpoint.url,
                pubKey = keys?.pubKey,
                authSecret = keys?.auth,
                temporary = endpoint.temporary,
            )
        }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val content = message.content
        val decrypted = message.decrypted
        launchSafely("onMessage") { handler.onMessage(instance, content, decrypted) }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        launchSafely("onRegistrationFailed") { handler.onRegistrationFailed(instance, reason.name) }
    }

    override fun onUnregistered(instance: String) {
        launchSafely("onUnregistered") { handler.onUnregistered(instance) }
    }

    override fun onTempUnavailable(instance: String) {
        handler.onTempUnavailable(instance)
    }

    /** An uncaught exception in the app scope would kill the process. */
    private fun launchSafely(what: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnosticLogger.e(TAG, "Push $what failed", e)
            }
        }
    }

    private companion object {
        const val TAG = "Push"
    }
}
