// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.features

import org.junit.Assert.assertEquals
import org.junit.Test

/** The provider-independent half of SensorCalendar: the event for a sensor and which old events go. */
class SensorCalendarTest {
    private val start = 1_760_000_000L
    private val end = start + 15 * 86_400L

    @Test fun eventIsAPointAtTheSensorsEnd() {
        val e = SensorCalendar.event("TEST0000001", end, "Sensor ends", "desc")
        assertEquals("sensor:TEST0000001", e.key)
        assertEquals(end * 1000, e.startMs)
        assertEquals(e.startMs, e.endMs)
        assertEquals("Sensor ends", e.title)
    }

    @Test fun sameSensorSameKey() {
        // the key is what makes a rewrite an update, not a second event (restarts, repeated SensorLife updates)
        assertEquals(SensorCalendar.event("A1", end, "t", "d").key, SensorCalendar.event("A1", end + 60, "t", "d").key)
    }

    @Test fun replacedSensorsFutureEventGoesPastOnesStay() {
        val now = (start + 3 * 86_400L) * 1000
        val existing = mapOf("sensor:OLD" to now - 86_400_000L, "sensor:EARLY" to now + 86_400_000L, "sensor:NEW" to now + 10 * 86_400_000L)
        assertEquals(setOf("sensor:EARLY"), SensorCalendar.stale(existing, "sensor:NEW", now))
    }

    @Test fun noCurrentSensorRemovesNothing() {
        assertEquals(emptySet<String>(), SensorCalendar.stale(mapOf("sensor:A" to Long.MAX_VALUE), null, 0))
    }

    @Test fun pastEventKeepsItsTimeFutureOneFollowsTheEnd() {
        val now = 1_000_000L
        assertEquals(true, SensorCalendar.keepTime(now - 1, now))    // ended: SensorLife pulled the end to now, keep the planned time
        assertEquals(false, SensorCalendar.keepTime(now + 1, now))   // failed early: the future event moves to the real end
    }
}
