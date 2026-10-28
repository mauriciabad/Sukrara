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

    /** Investigation 23 steps 2–5: the Libre 3 first and the only verified family; the rest are untested. */
    @Test fun registryLibre3FirstAndOnlyItVerified() {
        assertSame(libre3, SensorProfiles.all.first())
        assertSame(libre3, SensorProfiles.byId("libre3"))
        assertEquals(listOf("libre3"), SensorProfiles.all.filter { it.verified }.map { it.id })
        assertEquals(listOf("dexcom-g7", "dexcom-g7-15", "accu-chek-smartguide", "aidex-x", "dexcom-g6"),
            SensorProfiles.all.filter { !it.verified }.map { it.id })
        assertEquals(SensorProfiles.all.size, SensorProfiles.all.map { it.id }.toSet().size)
        // only the Libre 3 is found without scanning (its NFC tap gives the address)
        assertEquals(listOf(libre3), SensorProfiles.all.filter { !it.needsScan })
        assertNull(SensorProfiles.byId("lingo"))
    }

    @Test fun brandWarmupAndLifetime() {
        assertEquals(30, SensorProfiles.DEXCOM_G7.warmupMin); assertEquals(10 * 1440, SensorProfiles.DEXCOM_G7.wearMin)
        assertEquals(15 * 1440, SensorProfiles.DEXCOM_G7_15.wearMin)
        assertEquals(120, SensorProfiles.DEXCOM_G6.warmupMin); assertEquals(10 * 1440, SensorProfiles.DEXCOM_G6.wearMin)
        assertEquals(60, SensorProfiles.SMARTGUIDE.warmupMin); assertEquals(14 * 1440, SensorProfiles.SMARTGUIDE.wearMin)
        assertEquals(60, SensorProfiles.AIDEX_X.warmupMin); assertEquals(15 * 1440, SensorProfiles.AIDEX_X.wearMin)
        assertEquals(Calibration.NONE, SensorProfiles.AIDEX_X.calibration)
        assertEquals(Calibration.OPTIONAL_ON_SENSOR, SensorProfiles.SMARTGUIDE.calibration)
    }

    @Test fun typedIds() {
        val g6 = SensorProfiles.DEXCOM_G6.pairing as Pairing.TypedId
        val aidex = SensorProfiles.AIDEX_X.pairing as Pairing.TypedId
        assertEquals("8GABCD", PairingInput.normalize(" 8g-ab cd"))
        assertEquals(true, PairingInput.valid(g6, "8GABCD"))
        assertEquals(false, PairingInput.valid(g6, "8GABC"))
        assertEquals(false, PairingInput.valid(g6, "8gabcd"))   // normalize first
        assertEquals(true, PairingInput.valid(aidex, PairingInput.normalize("22222a2wc8")))
    }
}
