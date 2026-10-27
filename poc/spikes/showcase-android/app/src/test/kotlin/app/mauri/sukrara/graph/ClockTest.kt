// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/** Clock times follow the phone's 24-hour setting, not the locale's default (graph/Clock). */
class ClockTest {
    private val zone = TimeZone.getDefault()
    private val today = 1_800_000_000L - 1_800_000_000L % 86400 + 21 * 3600 + 8 * 60   // 21:08 UTC
    private fun norm(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')

    @After fun reset() { Clock.hour24Source = null; TimeZone.setDefault(zone) }

    @Test fun localeDefaults() {
        assertFalse(Clock.localeHour24(Locale.US))
        assertTrue(Clock.localeHour24(Locale.forLanguageTag("es-ES")))
    }

    @Test fun patternsSwitchTheHourCycle() {
        assertEquals("HH:mm", Clock.pattern(true, Locale.US))
        assertEquals("h:mm a", norm(Clock.pattern(false, Locale.US)))
        assertEquals("H:mm", Clock.pattern(true, Locale.forLanguageTag("es-ES")))
        assertEquals("h:mm a", Clock.pattern(false, Locale.forLanguageTag("es-ES")))
    }

    @Test fun bothModes() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("21:08", Clock.time(today, true, Locale.US))
        assertEquals("9:08 PM", norm(Clock.time(today, false, Locale.US)))
        assertEquals("9:08 p. m.", norm(Clock.time(today, false, Locale.forLanguageTag("es-ES"))))
        assertEquals("21:08", Clock.time(today, true, Locale.forLanguageTag("es-ES")))
    }

    @Test fun theSettingWinsOverTheLocale() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Clock.hour24Source = { true }
        assertEquals("21:08", Freshness.timeText(today, today + 60, locale = Locale.US))
        Clock.hour24Source = { false }
        assertEquals("9:08 p. m.", norm(Freshness.timeText(today, today + 60, locale = Locale.forLanguageTag("es-ES"))))
        assertEquals("9:00 PM", norm(Clock.minuteOfDay(21 * 60, locale = Locale.US)))
    }

    @Test fun readyAtHasNoWarmingUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        Clock.hour24Source = { false }
        val line = app.mauri.sukrara.core.Words().readyAt.format(Freshness.timeText(today, today - 600, locale = Locale.US))
        assertEquals("Ready at 9:08 PM", norm(line))
    }
}
