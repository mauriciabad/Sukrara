// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.graph.stats.MetricsCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The multi-day slots count the stored days, not only the live window (30 days of 5-minute readings). */
class MetricsCacheTest {
    private val now = 1_800_000_000L
    private val day = 86_400L
    private val b = Bands()

    /** Readings every 5 min for [days] days up to now: in range (100) the last day, high (250) before. */
    private fun history(days: Long) =
        (0 until days * 288)
            .map { i ->
                val t = now - i * 300
                GlucosePoint(t, if (now - t < day) 100 else 250, null)
            }.reversed()

    @Test fun ringsCountTheirWholeWindow() {
        val cache = MetricsCache(history(30), now)
        val oneDay = cache(RingSpec(day), b)
        val week = cache(RingSpec(7 * day), b)
        val twoWeeks = cache(RingSpec(14 * day), b)
        val month = cache(RingSpec(30 * day), b)
        assertEquals(1.0, oneDay.tir.inRange, 0.01)
        assertEquals(1.0 / 7, week.tir.inRange, 0.01)
        assertEquals(1.0 / 14, twoWeeks.tir.inRange, 0.01)
        assertEquals(1.0 / 30, month.tir.inRange, 0.01)
        assertEquals(1.0, month.tir.coverage, 0.01)
    }

    /** Ten stored days cover a third of the 30-day window: still drawn; five days (a sixth) are not. */
    @Test fun coverageDecidesWhetherASlotShows() {
        assertTrue(MetricsCache(history(10), now)(RingSpec(30 * day), b).tir.coverage >= GlucoseCard.MIN_COVERAGE)
        assertTrue(MetricsCache(history(5), now)(RingSpec(30 * day), b).tir.coverage < GlucoseCard.MIN_COVERAGE)
    }

    @Test fun eachWindowIsCountedOnce() {
        val cache = MetricsCache(history(8), now)
        assertSame(cache(RingSpec(7 * day), b), cache(RingSpec(7 * day, kind = SlotKind.GMI), b))
    }
}
