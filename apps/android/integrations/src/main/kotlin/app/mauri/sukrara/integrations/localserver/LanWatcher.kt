// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address

/**
 * The phone's Wi-Fi network for the Nightscout API on the home network: one ConnectivityManager callback, registered
 * only while [wifi] is collected (the home-network option is on) and unregistered when it stops. The request is the
 * default one for Wi-Fi, which excludes VPNs; the network it reports is the one the phone joined (the station side),
 * never the phone's own hotspot. Every callback body is guarded: an exception on ConnectivityThread would end the
 * process the alerts run in.
 */
class LanWatcher(
    context: Context,
) {
    private val context = context.applicationContext

    val wifi: Flow<WifiNet?> =
        callbackFlow {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm == null) {
                trySend(null)
                awaitClose {}
                return@callbackFlow
            }
            var caps: NetworkCapabilities? = null
            var link: LinkProperties? = null
            var current: Network? = null

            fun publish() {
                trySend(
                    runCatching {
                        val c = caps
                        val l = link
                        if (c == null || l == null) null else read(c, l)
                    }.getOrNull(),
                )
            }
            val callback =
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities,
                    ) {
                        runCatching {
                            current = network
                            caps = networkCapabilities
                            publish()
                        }
                    }

                    override fun onLinkPropertiesChanged(
                        network: Network,
                        linkProperties: LinkProperties,
                    ) {
                        runCatching {
                            current = network
                            link = linkProperties
                            publish()
                        }
                    }

                    override fun onLost(network: Network) {
                        runCatching {
                            if (network == current) {
                                caps = null
                                link = null
                                current = null
                                trySend(null)
                            }
                        }
                    }
                }
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build()
            val registered = runCatching { cm.registerNetworkCallback(request, callback) }.isSuccess
            if (!registered) trySend(null)
            awaitClose { runCatching { cm.unregisterNetworkCallback(callback) } }
        }.distinctUntilChanged()

    /** Android 17's local network access (part of Nearby devices); granted by default on older Android. */
    fun localNetworkAllowed(): Boolean =
        Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun read(
        caps: NetworkCapabilities,
        link: LinkProperties,
    ): WifiNet? {
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null
        val security = (caps.transportInfo as? WifiInfo)?.currentSecurityType ?: WifiInfo.SECURITY_TYPE_UNKNOWN
        return WifiNet(
            addresses = link.linkAddresses.filter { it.address is Inet4Address }.map { it.address to it.prefixLength },
            gateway =
                link.routes
                    .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                    ?.gateway
                    ?.hostAddress,
            dhcpServer = link.dhcpServerAddress?.hostAddress,
            domains = link.domains,
            secured = security !in OPEN,
        )
    }

    companion object {
        /** Android 17, where local network access became a permission. */
        const val LOCAL_NETWORK_SDK = 37

        /** Networks anyone nearby can join or read: open, WEP, OWE (encrypted but open to all), and unknown. */
        private val OPEN =
            setOf(WifiInfo.SECURITY_TYPE_OPEN, WifiInfo.SECURITY_TYPE_WEP, WifiInfo.SECURITY_TYPE_OWE, WifiInfo.SECURITY_TYPE_UNKNOWN)
    }
}
