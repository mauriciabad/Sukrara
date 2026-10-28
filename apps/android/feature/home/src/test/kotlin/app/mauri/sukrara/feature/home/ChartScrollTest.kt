// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.graph.ChartView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's chart scroll and zoom: the window stays inside the history and 2 h…31 d. */
class ChartScrollTest {
    private val now = 1_800_000_000L
    private val day = 86_400L
    private val default = 6 * 3600L
    private val first = now - 31 * day

    @Test fun scrollsBackAndStopsAtTheEdges() {
        val s = ChartScroll()
        assertFalse(s.away)
        assertEquals(-3600.0, s.by(-3600.0, first + default, now), 0.0)
        assertEquals(now - 3600, s.endS)
        assertTrue(s.away)
        // forward past now: only the hour back is moved, then it follows now again
        assertEquals(3600.0, s.by(7200.0, first + default, now), 0.0)
        assertNull(s.endS)
        // back past the oldest reading: stops with the oldest reading at the left edge
        s.by(-40.0 * day, first + default, now)
        assertEquals(first + default, s.endS)
    }

    @Test fun zoomsWithinTwoHoursAndTheHistory() {
        val s = ChartScroll()
        s.zoom(100.0, 1.0, default, first, now)
        assertEquals(ChartView.MIN_SPAN_S, s.spanS)
        s.zoom(0.0001, 1.0, default, first, now)
        assertEquals(31 * day, s.spanS)
        // a short history: never wider than it (but never narrower than the place's own window)
        val short = ChartScroll()
        short.zoom(0.0001, 1.0, default, now - 3 * day, now)
        assertEquals(3 * day, short.spanS)
        val tiny = ChartScroll()
        tiny.zoom(0.0001, 1.0, default, now - 3600, now)
        assertNull(tiny.spanS)
    }

    @Test fun zoomKeepsTheMomentUnderTheFingers() {
        val s = ChartScroll(endS = now - day)
        val anchor = 0.25
        val at = (now - day) - default * (1 - anchor)
        s.zoom(2.0, anchor, default, first, now)
        val span = s.span(default)
        assertEquals(default / 2, span)
        assertEquals(at, (s.endS!! - span * (1 - anchor)), 1.0)
    }

    @Test fun talkBackStepsDoubleAndHalve() {
        val s = ChartScroll()
        s.step(out = true, default, first, now)
        assertEquals(2 * default, s.spanS)
        s.step(out = false, default, first, now)
        assertNull(s.spanS) // back at the place's own length: not zoomed
        assertFalse(s.away)
    }

    @Test fun setBackToNowEndsTheScroll() {
        val s = ChartScroll(endS = now - day, spanS = 7 * day)
        s.set(now.toDouble(), default.toDouble(), default, now)
        assertFalse(s.away)
        assertEquals(default, s.span(default))
    }

    @Test fun limits() {
        assertEquals(ChartView.MIN_SPAN_S.toDouble() to (31 * day).toDouble(), ChartScroll.spanLimits(default, first - day, now))
        // a place showing 1 h: zooming in never goes below it
        assertEquals(3600.0, ChartScroll.spanLimits(3600, first, now).first, 0.0)
    }
}
