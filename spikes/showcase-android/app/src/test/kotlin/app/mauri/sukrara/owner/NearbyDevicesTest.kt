// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.BleOwner
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings → Share with people → Nearby devices: identified connections first (oldest link first, with the device
 * type), then the saved ones; a link its HELLO didn't name is not listed (no made-up "Follower's phone").
 */
class NearbyDevicesTest {
    private val sam = Follower("s", "Sam", 10)
    private val robin = Follower("r", "Robin", 20)
    private val kim = Follower("k", "Kim", 30, revoked = 40)

    @Test fun connectedFirstThenPaired() {
        val rows = NearbyDevices.of(listOf(sam, robin, kim), setOf("s", "r", "k"),
            listOf(BleOwner.Link("A", 100, "r", "mac"), BleOwner.Link("B", 50, null)))
        assertEquals(listOf(NearbyDevices.Row(robin, true, 100, "mac"), NearbyDevices.Row(sam, false, 10)), rows)
    }

    @Test fun aConnectedFollowerPairedOverTheInternetShowsToo() {
        val rows = NearbyDevices.of(listOf(sam), emptySet(), listOf(BleOwner.Link("A", 100, "s", "phone")))
        assertEquals(listOf(NearbyDevices.Row(sam, true, 100, "phone")), rows)
    }

    @Test fun unnamedAndRemovedLinksAreNotListed() {
        val rows = NearbyDevices.of(listOf(kim), setOf("k"), listOf(BleOwner.Link("A", 100, "k", "mac"), BleOwner.Link("B", 100, null)))
        assertEquals(emptyList<NearbyDevices.Row>(), rows)
    }
}
