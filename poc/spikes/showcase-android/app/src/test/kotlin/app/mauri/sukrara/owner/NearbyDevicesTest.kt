// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.owner

import app.mauri.sukrara.core.BleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private val samReq = PendingRequest("s", "Sam", "t2", "1234", 50, nearby = true)

    @Test fun forgettingClearsEverythingForThatDeviceAndNothingElse() {
        val followers = mutableListOf(sam, robin, kim)
        val paired = mutableSetOf("s", "r")
        val writers = mutableSetOf("s", "r")
        val pending = mutableListOf(samReq, PendingRequest("x", "Alex", "t3", "9999", 60))
        assertTrue(NearbyDevices.forget(followers, paired, writers, pending, "s", 99))
        // Sam: no longer trusted (kept under Removed so a rekey gets the revoked notice), unpaired, no writer, no request
        assertEquals(Follower("s", "Sam", 10, revoked = 99), followers[0])
        assertEquals(setOf("r"), paired)
        assertEquals(setOf("r"), writers)
        assertEquals(listOf("x"), pending.map { it.f })
        // the others untouched
        assertEquals(listOf(robin, kim), followers.drop(1))
        // the list no longer shows Sam, even while its link is still up
        assertEquals(listOf(NearbyDevices.Row(robin, false, 20)),
            NearbyDevices.of(followers, paired, listOf(BleOwner.Link("A", 100, "s", "mac"))))
        // a second forget has nothing left to clear
        assertFalse(NearbyDevices.forget(followers, paired, writers, pending, "s", 120))
    }

    @Test fun aForgottenDevicePairsAgainFromCleanState() {
        val followers = mutableListOf(sam, robin)
        val paired = mutableSetOf("s", "r")
        NearbyDevices.forget(followers, paired, mutableSetOf(), mutableListOf(), "s", 99)
        // Pair nearby again: the new request is approved like a first one
        NearbyDevices.approved(followers, paired, samReq, 200)
        assertEquals(listOf(robin, Follower("s", "Sam", 200)), followers)
        assertEquals(setOf("s", "r"), paired)
        val rows = NearbyDevices.of(followers, paired, listOf(BleOwner.Link("A", 210, "s", "phone")))
        assertEquals(listOf(NearbyDevices.Row(Follower("s", "Sam", 200), true, 210, "phone"), NearbyDevices.Row(robin, false, 20)), rows)
    }

    @Test fun unnamedAndRemovedLinksAreNotListed() {
        val rows = NearbyDevices.of(listOf(kim), setOf("k"), listOf(BleOwner.Link("A", 100, "k", "mac"), BleOwner.Link("B", 100, null)))
        assertEquals(emptyList<NearbyDevices.Row>(), rows)
    }
}
