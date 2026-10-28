// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.core.model.AlertNote
import app.mauri.sukrara.core.model.AlertNoteType
import app.mauri.sukrara.core.testing.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AlertsLatelyTest {
    private val now = Instant.parse("2026-10-27T14:00:00Z")
    private val clock = FakeClock(now)

    @Test fun showsTheNewestOfTheLastDay() {
        val notes =
            List(8) { AlertNote(now - Duration.ofMinutes(10L * it), AlertNoteType.AllUnmuted) } +
                AlertNote(now - Duration.ofHours(30), AlertNoteType.AllMuted)
        val shown = latelyOf(notes, clock)
        assertEquals(LATELY_LINES, shown.size)
        assertEquals(now, shown.first().at)
        assertEquals(emptyList<AlertNote>(), latelyOf(listOf(AlertNote(now - Duration.ofHours(25), AlertNoteType.AllMuted)), clock))
    }
}
