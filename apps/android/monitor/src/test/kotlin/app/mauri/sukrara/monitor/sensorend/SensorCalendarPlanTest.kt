// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.sensorend

import app.mauri.sukrara.monitor.sensorend.SensorCalendar.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** *Sensor end in calendar*'s decisions: one event per sensor, which ones go, which ones stay put. */
class SensorCalendarPlanTest {
    private val now = 1_800_000_000_000L

    @Test fun `the event is 30 minutes from the sensor's end, keyed by its serial`() {
        val e = Plan.event("ABC", 1_800_100_000, "Sensor ends", "d")
        assertEquals("sensor:ABC", e.key)
        assertEquals(1_800_100_000_000L, e.startMs)
        assertEquals(1_800_100_000_000L + 30 * 60_000L, e.endMs)
    }

    @Test fun `a replaced sensor's future event goes, past events stay as a history`() {
        val existing = mapOf("sensor:OLD" to now + 3_600_000, "sensor:PAST" to now - 1, "sensor:NEW" to now + 99)
        assertEquals(setOf("sensor:OLD"), Plan.stale(existing, "sensor:NEW", now))
        // no current sensor: nothing is removed
        assertEquals(emptySet<String>(), Plan.stale(existing, null, now))
    }

    @Test fun `a past event keeps its time, a future one follows the end`() {
        assertTrue(Plan.keepTime(now - 1, now))
        assertTrue(Plan.keepTime(now, now))
        assertFalse(Plan.keepTime(now + 1, now))
    }
}
