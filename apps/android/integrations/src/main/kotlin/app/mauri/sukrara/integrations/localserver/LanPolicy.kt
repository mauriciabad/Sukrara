// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.integrations.localserver

import kotlinx.serialization.Serializable
import java.net.Inet4Address
import java.net.InetAddress

/**
 * The Wi-Fi network the phone is joined to, as [LanWatcher] reads it from ConnectivityManager (the station network:
 * never the phone's own hotspot, never a VPN). [secured] is false for open, WEP and OWE networks and when Android
 * doesn't say (fail closed).
 */
data class WifiNet(
    /** IPv4 addresses with their prefix length. */
    val addresses: List<Pair<InetAddress, Int>>,
    val gateway: String?,
    val dhcpServer: String?,
    val domains: String?,
    val secured: Boolean,
)

/** Where the API on the home network is. */
sealed interface LanState {
    data object Off : LanState

    /** Not joined to a Wi-Fi network. */
    data object NoWifi : LanState

    /** This Wi-Fi gives the phone no private (home-network) address. */
    data object NotPrivate : LanState

    /** An open (or unknown) Wi-Fi: anyone nearby could join it and read along. */
    data object OpenWifi : LanState

    /** Another Wi-Fi than the one it was turned on in: paused until *Allow on this Wi-Fi*. */
    data object OtherWifi : LanState

    /** Android 17's local network access isn't allowed. */
    data object NoPermission : LanState

    /** Another app holds port 17581 on this address. */
    data object PortBusy : LanState

    /** Listening on http://[address]:17581 for devices on this subnet. */
    data class Listening(val address: String) : LanState
}

/** The network the person turned the home-network option on in (its look-alike fingerprint). */
@Serializable
data class LanPin(
    val fingerprint: String? = null,
) {
    companion object {
        /** A pin that matches no network (the stored one was lost). */
        const val UNKNOWN = "unknown"
    }
}

/** The address to bind, its prefix, and the network's fingerprint. */
data class LanTarget(
    val address: Inet4Address,
    val prefix: Int,
    val fingerprint: String,
)

/**
 * The rules for serving the Nightscout API on the home network, pure: which address, which callers, which networks.
 * Bound only to the phone's private IPv4 address on a secured Wi-Fi (never 0.0.0.0, never a public, hotspot or VPN
 * address); callers from outside that subnet are dropped; the Host header must be the bound address (a web page that
 * reaches the port through a rebound DNS name sends its own name). Without location permission (never asked) Android
 * hides the Wi-Fi's name, so "the same network" is a fingerprint of subnet, gateway, DHCP server and DNS domain: it
 * tells most networks apart, not two look-alike routers.
 */
object LanPolicy {
    sealed interface Pick {
        data class Ok(val target: LanTarget) : Pick

        data class Blocked(val state: LanState) : Pick
    }

    fun pick(
        net: WifiNet?,
        pin: String?,
    ): Pick {
        if (net == null) return Pick.Blocked(LanState.NoWifi)
        val (addr, prefix) =
            net.addresses
                .firstNotNullOfOrNull { (a, p) -> (a as? Inet4Address)?.takeIf { isPrivate(it) && p in 8..30 }?.let { it to p } }
                ?: return Pick.Blocked(LanState.NotPrivate)
        if (!net.secured) return Pick.Blocked(LanState.OpenWifi)
        val fp = fingerprint(addr, prefix, net)
        if (pin != null && pin != fp) return Pick.Blocked(LanState.OtherWifi)
        return Pick.Ok(LanTarget(addr, prefix, fp))
    }

    /** RFC 1918 only: 10/8, 172.16/12, 192.168/16 (no link-local, CGNAT or public addresses). */
    fun isPrivate(a: Inet4Address): Boolean {
        val b = a.address.map { it.toInt() and 0xff }
        return b[0] == 10 || (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168)
    }

    /** [caller] is on the same IPv4 subnet as [bound] (an IPv4-mapped IPv6 address counts as its IPv4 one). */
    fun sameSubnet(
        caller: InetAddress,
        bound: Inet4Address,
        prefix: Int,
    ): Boolean {
        val c = ipv4(caller) ?: return false
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        return (toInt(c) and mask) == (toInt(bound) and mask)
    }

    /** The Host header names exactly the bound address (with or without the port); a missing Host is refused. */
    fun hostOk(
        host: String?,
        bound: Inet4Address,
        port: Int,
    ): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        val ip = bound.hostAddress
        return h == ip || h == "$ip:$port"
    }

    fun fingerprint(
        addr: Inet4Address,
        prefix: Int,
        net: WifiNet,
    ): String {
        val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
        val subnet = toInt(addr) and mask
        val s = listOf(subnet.toUInt().toString(16), prefix, net.gateway.orEmpty(), net.dhcpServer.orEmpty(), net.domains.orEmpty())
        return s.joinToString("|")
    }

    private fun ipv4(a: InetAddress): Inet4Address? =
        when (a) {
            is Inet4Address -> {
                a
            }

            else -> {
                val b = a.address
                // ::ffff:a.b.c.d
                val mapped = b.size == 16 && (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
                if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) as Inet4Address else null
            }
        }

    private fun toInt(a: Inet4Address): Int = a.address.fold(0) { acc, x -> (acc shl 8) or (x.toInt() and 0xff) }
}
