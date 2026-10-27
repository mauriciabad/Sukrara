// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.libre3

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the direct client gives up on a sensor (2026-10-18: the owner's expired Libre 3 Plus looped for ever). PoC test. */
class PatchStateTest {
    @Test
    fun running() {
        assertFalse(PatchState.ended(4, 10_000, 21_600))
        assertFalse(PatchState.ended(4, 21_599, 21_600))
        assertFalse(PatchState.stopNow(4))
    }

    @Test
    fun theOwnersExpiredSensor() {
        // state=5 currentLifeCount=21635 — ended, one glucose wait (grace) then stop
        assertTrue(PatchState.ended(5, 21_635, 21_600))
        assertFalse(PatchState.stopNow(5))
    }

    @Test
    fun pastWearLength() = assertTrue(PatchState.ended(4, 21_600, 21_600))

    @Test
    fun terminatedOrErrorStopsAtOnce() {
        for (s in 6..8) {
            assertTrue(PatchState.ended(s, 100, 21_600))
            assertTrue(PatchState.stopNow(s))
        }
    }

    @Test
    fun unknownWearLength() = assertFalse(PatchState.ended(4, 30_000, 0))
}
