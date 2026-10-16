// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseStatsTest {
    private val b = Bands()
    private fun r(min: Int, v: Int) = Reading(min * 60L, v, null)

    @Test fun bands() {
        assertEquals(Band.LOW, b.of(69)); assertEquals(Band.TIGHT, b.of(70)); assertEquals(Band.TIGHT, b.of(140))
        assertEquals(Band.TARGET, b.of(141)); assertEquals(Band.TARGET, b.of(180)); assertEquals(Band.HIGH, b.of(181))
        assertEquals(Bands(120, 130, 140), Bands(200, 10, 90).sane().let { Bands(it.low, it.tight, it.high) })
    }

    /** 1-minute and 5-minute readings count by the time they stand for, not by how many there are. */
    @Test fun timeWeighted() {
        val oneMin = (0 until 60).map { r(it, 100) }                // first hour: 60 readings at 100
        val fiveMin = (60 until 120 step 5).map { r(it, 200) }      // second hour: 12 readings at 200
        val t = GlucoseStats.timeInRange(oneMin + fiveMin, 0, 120 * 60L, b)
        assertEquals(0.5, t.tight, 0.01)
        assertEquals(0.5, t.high, 0.01)
        assertEquals(1.0, t.coverage, 0.01)
    }

    /** A gap counts as nothing (only the 15 min cap of the reading before it). */
    @Test fun gaps() {
        val t = GlucoseStats.timeInRange(listOf(r(0, 100), r(1, 100), r(120, 250), r(121, 250)), 0, 122 * 60L, b)
        assertEquals((60.0 + 15 * 60) / (60 + 15 * 60 + 60 + 60), t.tight, 0.01)
        assertTrue(t.coverage < 0.2)
    }

    @Test fun bandExactAreasMatchTimeInRange() {
        val rs = (0 until 600).map { r(it, (120 + 80 * Math.sin(it / 40.0)).toInt()) }
        val tir = GlucoseStats.timeInRange(rs, 0, 600 * 60L, b)
        val p = GlucoseStats.profile(Profile.BAND_EXACT, rs, 0, 600 * 60L, b, tir)
        for (band in Band.entries) {
            val area = p.indices.filter { b.of(GlucoseStats.MIN_MG + it) == band }.sumOf { p[it] }
            assertEquals("$band", tir.share(band), area, 1e-9)
        }
        for (kind in Profile.entries.drop(1)) {
            val q = GlucoseStats.profile(kind, rs, 0, 600 * 60L, b, tir)
            assertEquals("$kind sums to 1", 1.0, q.sum(), 1e-6)
        }
    }

    @Test fun percentagesAddUp() {
        assertEquals(Triple(33, 34, 33), GlucoseGraph.pct3(1 / 3.0, 1 / 3.0 + 0.0001, 1 / 3.0 - 0.0001))
        assertEquals(Triple(10, 70, 20), GlucoseGraph.pct3(0.1, 0.7, 0.2))
    }

    @Test fun freshness() {
        assertEquals(Freshness.FRESH, Freshness.of(90)); assertEquals(Freshness.LATE, Freshness.of(91))
        assertEquals(Freshness.STALE, Freshness.of(301)); assertEquals(Freshness.OLD, Freshness.of(1201))
        assertEquals(Freshness.GONE, Freshness.of(3 * 3600 + 1)); assertEquals(Freshness.GONE, Freshness.of(null))
    }
}
