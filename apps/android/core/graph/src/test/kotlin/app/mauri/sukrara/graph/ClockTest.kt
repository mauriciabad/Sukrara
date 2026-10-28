// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.Fixtures.bool
import app.mauri.sukrara.graph.Fixtures.long
import app.mauri.sukrara.graph.Fixtures.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.Locale

/** contracts/graph/clock: the 12/24-hour setting wins over the locale's default (Mac: GraphTests). */
class ClockTest {
    private fun norm(s: String) = s.replace('\u00A0', ' ').replace('\u202F', ' ')

    @Test fun fixtures() {
        val cases = Fixtures.read("clock/clock.json")["cases"] as JsonArray
        assertTrue(cases.isNotEmpty())
        val fails =
            cases.mapNotNull { e ->
                val c = e.jsonObject
                val locale = Locale.forLanguageTag(c["locale"].str()!!)
                val m = c["minute"].long()!!.toInt()
                val h24 = c["hour24"].bool()!!
                val x = c["expect"]!!.jsonObject
                val time = norm(Clock.minuteOfDay(m, h24, locale))
                val axis = norm(Clock.hourLabel(m / 60, h24, locale))
                if (time == x["time"].str() && axis == x["axis"].str()) {
                    null
                } else {
                    "${c["name"].str()}: got $time / $axis"
                }
            }
        assertTrue(fails.joinToString("\n"), fails.isEmpty())
    }

    @Test fun `time text today, this week, before`() {
        val now = 1_800_000_000L // Friday 15 Jan 2027, 08:00 UTC
        val utc = ZoneOffset.UTC
        Clock.hour24Source = { true }
        try {
            check(now, utc)
        } finally {
            Clock.hour24Source = null
        }
    }

    private fun check(
        now: Long,
        utc: ZoneOffset,
    ) {
        assertEquals("07:30", norm(Freshness.timeText(now - 1800, now, Locale.UK, utc)))
        assertEquals("Wed 08:00", norm(Freshness.timeText(now - 2 * 86400, now, Locale.UK, utc)))
        assertEquals("5 Jan", norm(Freshness.timeText(now - 10 * 86400, now, Locale.UK, utc)))
    }
}
