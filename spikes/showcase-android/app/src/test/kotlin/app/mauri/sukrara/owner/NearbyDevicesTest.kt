// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.BleOwner
import org.junit.Assert.assertEquals
import org.junit.Test

/** Settings → Share with people → Nearby devices: connected phones first (oldest link first), then the saved ones. */
class NearbyDevicesTest {
    private val sam = Follower("s", "Sam", 10)
    private val robin = Follower("r", "Robin", 20)
    private val kim = Follower("k", "Kim", 30, revoked = 40)

    @Test fun connectedFirstThenPaired() {
        val rows = NearbyDevices.of(listOf(sam, robin, kim), setOf("s", "r", "k"),
            listOf(BleOwner.Link("A", 100, "r"), BleOwner.Link("B", 50, null)))
        assertEquals(listOf(null to true, robin to true, sam to false), rows.map { it.follower to it.connected })
    }

    @Test fun aConnectedFollowerPairedOverTheInternetShowsToo() {
        val rows = NearbyDevices.of(listOf(sam), emptySet(), listOf(BleOwner.Link("A", 100, "s")))
        assertEquals(listOf(NearbyDevices.Row(sam, true, 100)), rows)
    }

    @Test fun removedFollowersAreUnnamedPhones() {
        val rows = NearbyDevices.of(listOf(kim), setOf("k"), listOf(BleOwner.Link("A", 100, "k")))
        assertEquals(listOf(NearbyDevices.Row(null, true, 100)), rows)
    }

    @Test fun nothing() = assertEquals(emptyList<NearbyDevices.Row>(), NearbyDevices.of(listOf(sam), emptySet(), emptyList()))
}
