package org.vovchenko.webdavsync.push

import android.content.Context
import android.content.pm.PackageManager
import org.unifiedpush.android.connector.UnifiedPush
import javax.inject.Inject
import javax.inject.Singleton

/** A push service the user can pick: an installed UnifiedPush app, or the built-in Google Play (FCM) one. */
data class PushServiceOption(
    val packageName: String,
    val label: String,
    val isGooglePlay: Boolean,
)

/** Test seam over the static [UnifiedPush] API. Instances are `account-{id}`. */
interface PushDistributor {
    /** The saved distributor once it has answered at least once; null when none or uninstalled. */
    fun ackDistributor(): String?

    /** The distributor the user picked, which may not have answered yet. */
    fun savedDistributor(): String?

    fun distributors(): List<String>

    /** Requests an endpoint; the answer arrives in the push service. False when [vapid] is malformed. */
    fun register(instance: String, messageForDistributor: String, vapid: String?): Boolean

    fun unregister(instance: String)

    /** Google Play (FCM) first when Play services exist, then installed UnifiedPush apps. */
    fun options(): List<PushServiceOption>

    /** Makes [packageName] the push service; the previous one is told to drop this app's registrations. */
    fun select(packageName: String)
}

@Singleton
class UnifiedPushDistributor @Inject constructor(
    private val context: Context,
) : PushDistributor {
    override fun ackDistributor(): String? = UnifiedPush.getAckDistributor(context)

    override fun savedDistributor(): String? = UnifiedPush.getSavedDistributor(context)

    override fun distributors(): List<String> = UnifiedPush.getDistributors(context)

    override fun register(instance: String, messageForDistributor: String, vapid: String?): Boolean =
        try {
            UnifiedPush.register(context, instance, messageForDistributor, vapid)
            true
        } catch (e: UnifiedPush.VapidNotValidException) {
            false
        }

    override fun unregister(instance: String) {
        UnifiedPush.unregister(context, instance)
    }

    // The connector lists this app itself only when the embedded FCM distributor and Play services are present.
    override fun options(): List<PushServiceOption> =
        UnifiedPush.getDistributors(context)
            .map { pkg ->
                if (pkg == context.packageName) {
                    PushServiceOption(pkg, GOOGLE_PLAY_LABEL, isGooglePlay = true)
                } else {
                    PushServiceOption(pkg, appLabel(pkg), isGooglePlay = false)
                }
            }
            .sortedByDescending { it.isGooglePlay }

    override fun select(packageName: String) {
        UnifiedPush.saveDistributor(context, packageName)
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    companion object {
        const val GOOGLE_PLAY_LABEL = "Google Play (FCM)"
    }
}

/** The option to preselect: the saved one when still available, otherwise Google Play (FCM), otherwise the first app. */
fun preselectPushService(options: List<PushServiceOption>, saved: String?): PushServiceOption? =
    options.firstOrNull { it.packageName == saved }
        ?: options.firstOrNull { it.isGooglePlay }
        ?: options.firstOrNull()
