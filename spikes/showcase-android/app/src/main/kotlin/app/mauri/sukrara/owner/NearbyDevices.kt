// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.BleOwner

/**
 * Settings → Share with people → Nearby devices: the follower devices connected over Bluetooth now and the followers
 * paired nearby, like Settings' Connected devices (connected first, then the saved ones). A connection is listed only
 * once its `HELLO` identity proof named a current follower (spec/ogs-ble.md B§6): an unnamed link never shows, so the
 * list never makes a name up.
 */
object NearbyDevices {
    /** [kind] = `phone` / `mac` while connected (from the identity proof), null for a saved one. */
    data class Row(val follower: Follower, val connected: Boolean, val since: Long, val kind: String? = null)

    fun of(followers: List<Follower>, paired: Set<String>, links: List<BleOwner.Link>): List<Row> {
        val active = followers.filter { it.revoked == null }.associateBy { it.f }
        val connected = links.sortedBy { it.since }.mapNotNull { l -> l.f?.let { active[it] }?.let { Row(it, true, l.since, l.kind) } }
        val saved = paired.mapNotNull { active[it] }.filter { fl -> connected.none { it.follower.f == fl.f } }
            .sortedBy { it.name.lowercase() }.map { Row(it, false, it.since) }
        return connected + saved
    }
}
