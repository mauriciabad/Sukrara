// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucoseMetrics
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.graph.stats.GlucoseStats
import app.mauri.sukrara.graph.stats.Goal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlucoseStatsTest {
    private val b = Bands()

    private fun r(
        min: Int,
        v: Int,
    ) = GlucosePoint(min * 60L, v, null)

    @Test fun bands() {
        assertEquals(Band.VERY_LOW, b.of(53))
        assertEquals(Band.LOW, b.of(54))
        assertEquals(Band.LOW, b.of(69))
        assertEquals(Band.TIGHT, b.of(70))
        assertEquals(Band.TIGHT, b.of(140))
        assertEquals(Band.TARGET, b.of(141))
        assertEquals(Band.TARGET, b.of(180))
        assertEquals(Band.HIGH, b.of(181))
        assertEquals(Band.HIGH, b.of(250))
        assertEquals(Band.VERY_HIGH, b.of(251))
        assertEquals(Bands(54, 120, 130, 140, 150, 300), Bands(54, 200, 10, 90, 100, 300).sane())
    }

    /** 1-minute and 5-minute readings count by the time they stand for, not by how many there are. */
    @Test fun timeWeighted() {
        val oneMin = (0 until 60).map { r(it, 100) }
        val fiveMin = (60 until 120 step 5).map { r(it, 200) }
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

    @Test fun bandAreasMatchTimeInRange() {
        val rs = (0 until 600).map { r(it, (150 + 120 * Math.sin(it / 40.0)).toInt()) }
        val tir = GlucoseStats.timeInRange(rs, 0, 600 * 60L, b)
        assertTrue(tir.veryLow > 0 && tir.veryHigh > 0)
        val p = GlucoseStats.profile(rs, 0, 600 * 60L, b, tir)
        for (band in Band.entries) {
            val area = p.indices.filter { b.of(GlucoseStats.MIN_MG + it) == band }.sumOf { p[it] }
            assertEquals("$band", tir.share(band), area, 1e-9)
        }
        assertEquals(1.0, p.sum(), 1e-6)
    }

    /** The LibreView report's example: average 141 mg/dL → GMI 6.7 %, 49 mmol/mol. */
    @Test fun gmi() {
        val m = GlucoseMetrics.of((0 until 600).map { r(it, 141) }, 0, 600 * 60L, b)
        assertEquals(141.0, m.mean, 1e-9)
        assertEquals(6.68, m.gmi, 0.005)
        assertEquals(49.5, m.gmiMmolMol, 0.1)
        assertEquals(0.0, m.cv, 1e-9)
        assertTrue(Goal.GMI.met(m) && Goal.AVERAGE.met(m) && Goal.CV.met(m))
    }

    /** Half the time at 100 and half at 200 → mean 150, SD 50, CV 33 %. */
    @Test fun cvTimeWeighted() {
        val rs = (0 until 60).map { r(it, 100) } + (60 until 120 step 5).map { r(it, 200) }
        val m = GlucoseMetrics.of(rs, 0, 120 * 60L, b)
        assertEquals(150.0, m.mean, 0.5)
        assertEquals(50.0, m.sd, 0.5)
        assertEquals(33.3, m.cv, 0.5)
        assertTrue(!Goal.ABOVE.met(m))
    }

    @Test fun windowDeduplicatesAndCuts() {
        val rs = listOf(r(0, 100), GlucosePoint(10, 110, null), r(5, 120), r(10, 130))
        val w = GlucoseStats.window(rs, 0, 6 * 60L)
        assertEquals(listOf(110, 120), w.map { it.sgv })
    }
}
