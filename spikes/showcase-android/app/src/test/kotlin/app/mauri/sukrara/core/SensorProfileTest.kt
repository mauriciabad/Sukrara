// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

/**
 * Investigation 23, step 1: the Libre 3's profile carries exactly the values that were constants before it, so
 * nothing the app computes from them (sensor end, warm-up end, countdowns, reminders) moved.
 */
class SensorProfileTest {
    private val libre3 = SensorProfiles.LIBRE_3

    @Test fun libre3ProfileEqualsTheOldConstants() {
        assertEquals("libre3", libre3.id)
        assertEquals(21_600, libre3.wearMin)          // was SensorLife.DEFAULT_WEAR_MIN = 21_600
        assertEquals(60, libre3.warmupMin)            // was SensorLife.DEFAULT_WARMUP_MIN = 60
        assertEquals(15.days, libre3.lifetime)
        assertEquals(60.minutes, libre3.warmup)
        assertEquals(Pairing.NfcTap, libre3.pairing)
        assertEquals(Calibration.NONE, libre3.calibration)
        assertFalse(libre3.needsScan)
    }

    @Test fun sensorLifeDefaultsComeFromTheProfile() {
        assertEquals(21_600, SensorLife.DEFAULT_WEAR_MIN)
        assertEquals(60, SensorLife.DEFAULT_WARMUP_MIN)
        // the same SensorInfo as before the profile existed: end = start + 15 d, warm-up end = start + 60 min
        assertEquals(SensorInfo("S", 100, 100 + 15 * 86_400L, 100 + 3_600L), SensorLife.fromStart("S", 100, 0))
        assertEquals(100 + 3_600L, SensorInfo("S", 100, 200).warmupEndEpoch)
        // a sensor's own patch info still wins over the profile
        assertEquals(SensorInfo("S", 100, 100 + 14 * 86_400L, 100 + 1_800L), SensorLife.fromStart("S", 100, 20_160, 30))
    }

    @Test fun registryHasOnlyTheLibre3() {
        assertEquals(listOf(libre3), SensorProfiles.all)
        assertSame(libre3, SensorProfiles.byId("libre3"))
        assertNull(SensorProfiles.byId("dexcom-g7"))
    }
}
