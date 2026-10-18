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

    @Test fun warmUp() {
        val s = SensorLife.fromStart("S", 1_000, 0)
        assertEquals(1_000 + 3_600L, s.warmupEndEpoch)
        assertEquals(true, SensorLife.warming(s, null, 1_000 + 18 * 60))
        assertEquals("42m", SensorLife.warmupLabel(s, 1_000 + 18 * 60, w))
        assertEquals("1m", SensorLife.warmupLabel(s, 1_000 + 3_599, w))
        assertEquals(0.3, SensorLife.warmupFraction(s, 1_000 + 18 * 60), 1e-9)
        // the first reading ends it; an older reading (the previous sensor's) doesn't
        assertEquals(false, SensorLife.warming(s, 1_000 + 3_660, 1_000 + 3_700))
        assertEquals(true, SensorLife.warming(s, 900, 1_000 + 60))
        // at most 15 min past the warm-up's end without a reading
        assertEquals(false, SensorLife.warming(s, null, 1_000 + 3_600 + 15 * 60))
        assertEquals(1_000 + 30 * 60L, SensorLife.fromStart("S", 1_000, 0, 30).warmupEndEpoch)
    }

    @Test fun endedText() {
        val zone = java.time.ZoneId.of("Europe/Madrid")
        val now = java.time.ZonedDateTime.of(2026, 10, 18, 9, 0, 0, 0, zone).toEpochSecond()
        fun at(d: Int, h: Int, m: Int) = java.time.ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toEpochSecond()
        val uk = java.util.Locale.UK
        fun text(end: Long) = w.sensorEndedWhen.format(w.sensorEnded, SensorLife.endedWhen(end, now, w, uk, zone))
        assertEquals("Sensor ended at 08:03", text(at(18, 8, 3)))
        assertEquals("Sensor ended yesterday at 18:03", text(at(17, 18, 3)))
        assertEquals("Sensor ended Wed 18:03", text(at(14, 18, 3)))
        assertEquals("Sensor ended 9 Oct", text(at(9, 18, 3)))
    }
}
