// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.BleOwner

/**
 * Settings → Share with people → Nearby devices: the follower phones paired over Bluetooth and the ones connected now,
 * like Settings' Connected devices (connected first, then the saved ones). A connection names its follower only once
 * that phone sent a signed request or treatment over it ([BleOwner.identify]); until then it's an unnamed phone.
 */
object NearbyDevices {
    /** [follower] null = a connected phone that hasn't said who it is yet. */
    data class Row(val follower: Follower?, val connected: Boolean, val since: Long)

    fun of(followers: List<Follower>, paired: Set<String>, links: List<BleOwner.Link>): List<Row> {
        val active = followers.filter { it.revoked == null }.associateBy { it.f }
        val named = links.mapNotNull { l -> l.f?.let { f -> active[f]?.let { it to l.since } } }.distinctBy { it.first.f }
        val connected = named.map { (fl, since) -> Row(fl, true, since) }
        val unnamed = links.filter { it.f == null || it.f !in active }.map { Row(null, true, it.since) }
        val saved = paired.mapNotNull { active[it] }.filter { fl -> named.none { it.first.f == fl.f } }
            .sortedBy { it.name.lowercase() }.map { Row(it, false, it.since) }
        return (connected + unnamed).sortedBy { it.since } + saved
    }
}
