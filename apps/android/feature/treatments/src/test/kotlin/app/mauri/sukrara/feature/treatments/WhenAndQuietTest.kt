// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The dialog's *Minutes ago* and its quiet line. */
class WhenAndQuietTest {
    private val now = Instant.parse("2026-06-15T12:30:20Z")
    private val rules = QuietRules(afterInsulinMin = 60, afterCarbsMin = 15)

    @Test fun everyMinuteUnderADay() {
        for (m in 0..TreatmentForm.MAX_AGO_MIN) assertEquals(m, TreatmentForm.agoMinutes("$m"))
        assertNull(TreatmentForm.agoMinutes("1440"))
        assertNull(TreatmentForm.agoMinutes(""))
        assertEquals("1440", TreatmentForm.filterMinutes("1h440x9"))
    }

    @Test fun minutesAgoResolveWhenSaving() {
        val form = TreatmentFormState(ago = 16)
        assertEquals(now - Duration.ofMinutes(16), form.whenAt(now))
        // saved a minute later: still 16 minutes before the save
        assertEquals(now, form.whenAt(now + Duration.ofMinutes(16)))
        form.ago = null
        form.at = now - Duration.ofHours(2)
        assertEquals(now - Duration.ofHours(2), form.whenAt(now))
        form.at = null
        assertNull(form.whenAt(now))
    }

    @Test fun quietLine() {
        assertEquals(QuietNote(60, null), QuietNote.of(rules, rapid = true, carbs = false, at = now, now = now))
        assertEquals(QuietNote(null, 15), QuietNote.of(rules, rapid = false, carbs = true, at = now, now = now))
        assertEquals(QuietNote(60, 15), QuietNote.of(rules, rapid = true, carbs = true, at = now, now = now))
        // entered 16 minutes ago: what is left
        val earlier = now - Duration.ofMinutes(16)
        assertEquals(QuietNote(44, null), QuietNote.of(rules, rapid = true, carbs = true, at = earlier, now = now))
        // nothing quiets: no line
        assertNull(QuietNote.of(rules, rapid = false, carbs = false, at = now, now = now))
        assertNull(QuietNote.of(QuietRules.NONE, rapid = true, carbs = true, at = now, now = now))
        assertNull(QuietNote.of(rules, rapid = true, carbs = false, at = now - Duration.ofHours(2), now = now))
    }
}
