// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.util.TimeZone

class GlucoseStateTest {
    @Test
    fun `the initial state shows no value and has not started monitoring`() {
        val s = GlucoseState.Initial
        assertNull(s.latest)
        assertNull(s.role)
        assertTrue(s.recent.isEmpty())
        assertEquals(SensorState.None, s.sensor)
        assertEquals(SourceStatus.NotStarted, s.source)
    }

    @Test
    fun `the system clock follows a time zone change while running`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Madrid"))
            assertEquals(ZoneId.of("Europe/Madrid"), SystemClock.zone)
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            assertEquals(ZoneId.of("America/New_York"), SystemClock.zone)
        } finally {
            TimeZone.setDefault(saved)
        }
    }
}
