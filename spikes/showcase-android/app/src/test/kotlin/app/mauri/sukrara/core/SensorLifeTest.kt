// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The pure part of [SensorLife]; the shared label/ring cases are in spikes/graph-fixtures/sensor (GraphFixtureTest). */
class SensorLifeTest {
    private val w = Words()

    @Test fun defaultWear() {
        assertEquals(SensorInfo("S", 100, 100 + 15 * 86_400L), SensorLife.fromStart("S", 100, 0))
        assertEquals(SensorInfo("S", 100, 100 + 14 * 86_400L), SensorLife.fromStart("S", 100, 20_160))
    }

    @Test fun labels() {
        assertEquals("14d", SensorLife.label(14 * 86_400L + 3_599, 0, w))
        assertEquals("1h", SensorLife.label(3_600, 0, w))
        assertEquals("59m", SensorLife.label(3_599, 0, w))
        assertEquals("0m", SensorLife.label(-5, 0, w))
    }

    @Test fun ringWindow() {
        assertNull(SensorLife.countdownFraction(8 * 3_600L + 1, 0, 8))
        assertEquals(1.0, SensorLife.countdownFraction(8 * 3_600L, 0, 8)!!, 1e-9)
        assertNull(SensorLife.countdownFraction(0, 0, 8))
    }
}
