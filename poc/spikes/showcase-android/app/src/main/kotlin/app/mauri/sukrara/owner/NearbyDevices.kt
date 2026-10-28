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

    /**
     * *Forget this device* (round f33; Android Bluetooth settings' "Forget"): everything this phone keeps for follower
     * [f]'s device and nothing else. Its trust ends ([Follower.revoked], so it gets no new key and a rekey gets the
     * revoked notice: the record stays under *Removed*), its nearby pairing, its "can add entries" and any request it
     * left waiting go. Returns whether its trust ended now (the caller then sends the revoked notice and rotates the
     * key, as Remove does); false = nothing was kept for it.
     */
    fun forget(followers: MutableList<Follower>, nearbyPaired: MutableSet<String>, writers: MutableSet<String>,
               pending: MutableList<PendingRequest>, f: String, now: Long): Boolean {
        val idx = followers.indexOfLast { it.f == f && it.revoked == null }
        if (idx >= 0) followers[idx] = followers[idx].copy(revoked = now)
        val changed = nearbyPaired.remove(f) or writers.remove(f) or pending.removeAll { it.f == f }
        return idx >= 0 || changed
    }

    /**
     * An approved request (the engine's `approve`): the device is a follower again from this moment, a forgotten or
     * removed record replaced, and paired nearby when the request came over Bluetooth.
     */
    fun approved(followers: MutableList<Follower>, nearbyPaired: MutableSet<String>, p: PendingRequest, now: Long) {
        followers.removeIf { it.f == p.f }
        followers += Follower(p.f, p.name, now, avatar = p.avatar)
        if (p.nearby) nearbyPaired += p.f
    }
}
