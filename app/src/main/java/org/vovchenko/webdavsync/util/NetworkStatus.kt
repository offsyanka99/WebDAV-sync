package org.vovchenko.webdavsync.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Network helpers shared by Overview (mobile-data warning) and the home-screen widget.
 *
 * Important: when a VPN is active, [ConnectivityManager.getActiveNetwork] often only reports
 * [NetworkCapabilities.TRANSPORT_VPN], not cellular/Wi‑Fi. Checking only the active network
 * made "Warn before syncing on mobile data" never fire on VPN-on-cellular (common with
 * WiFi-VPN style apps).
 */
object NetworkStatus {

    /**
     * True when the device would use cellular for general internet and has no Wi‑Fi/ethernet
     * path. VPN-on-cellular still counts as mobile data.
     */
    fun isOnCellularData(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false

        var hasWifiOrEthernet = false
        var hasCellular = false

        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            // Skip captive / non-internet networks.
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue

            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                hasWifiOrEthernet = true
            }
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                hasCellular = true
            }
        }

        // Fallback: active network only (covers devices where allNetworks is empty/restricted).
        if (!hasCellular && !hasWifiOrEthernet) {
            val active = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(active) ?: return false
            hasWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            hasCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            // Active VPN with no underlying transports reported — treat metered as cellular.
            if (!hasCellular && !hasWifiOrEthernet &&
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED).not()
            ) {
                return true
            }
        }

        return hasCellular && !hasWifiOrEthernet
    }
}
