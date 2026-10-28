// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCardTest {
    private val now = 1_800_000_000L
    private val bands = Bands()

    /** Every 5 min for [days] days ending [endAgoS] before now, at [mg]. */
    private fun steady(
        days: Int,
        mg: Int,
        endAgoS: Long = 0,
    ) = (0 until days * 288).map { GlucosePoint(now - endAgoS - (days * 288 - 1 - it) * 300L, mg, null) }

    @Test fun countsOnlyThePeriod() {
        // a week at 250 then the last day at 120: 1 day is all in range, 14 days mostly above
        val history = steady(7, 250, endAgoS = 86_400) + steady(1, 120)
        val day = reportMetrics(history, now, 1, bands)
        assertTrue(day.tir.inRange > 0.99)
        assertEquals(120.0, day.mean, 1.0)
        val twoWeeks = reportMetrics(history, now, 14, bands)
        assertTrue(twoWeeks.tir.above > 0.8)
        // 8 of 14 days have data: under the 70 % the report needs
        assertTrue(twoWeeks.tir.coverage < 0.6)
    }

    @Test fun noReadingsInThePeriodIsEmpty() {
        val old = steady(2, 120, endAgoS = 3 * 86_400L)
        assertTrue(reportMetrics(old, now, 1, bands).empty)
        assertFalse(reportMetrics(old, now, 7, bands).empty)
    }

    @Test fun periodsAndDefault() {
        assertEquals(listOf(1, 7, 14, 30), REPORT_DAYS)
        assertTrue(REPORT_DEFAULT_DAYS in REPORT_DAYS)
    }
}
