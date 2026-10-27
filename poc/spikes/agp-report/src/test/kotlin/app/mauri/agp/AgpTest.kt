// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

class AgpTest {
    private val madrid = ZoneId.of("Europe/Madrid")
    private val utc = ZoneId.of("UTC")

    /** A steady series: one reading every [step] s from [from] to [to] at [sgv] (or sgv(t)). */
    private fun series(from: Long, to: Long, step: Long, sgv: (Long) -> Int) = (from until to step step).map { Reading(it, sgv(it)) }

    @Test fun quantileIsType7() {
        val s = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertEquals(1.75, Agp.quantile(s, 0.25), 1e-12)   // numpy.percentile([1,2,3,4], 25)
        assertEquals(2.5, Agp.quantile(s, 0.5), 1e-12)
        assertEquals(3.85, Agp.quantile(s, 0.95), 1e-12)
        assertEquals(7.0, Agp.quantile(doubleArrayOf(7.0), 0.05), 1e-12)
    }

    @Test fun liveMinutesDontOutweighHistory() {
        // 12 h at 100 in 5-minute history, then 12 h at 200 in 1-minute live data: half the time each
        val p = Period(utc, LocalDate.of(2026, 1, 1), 1)
        val rs = series(p.from, p.from + 43200, 300) { 100 } + series(p.from + 43200, p.to, 60) { 200 }
        val r = Agp.report(rs, p)
        assertEquals(150.0, r.metrics.mean, 0.01)
        assertEquals(0.5, r.metrics.tight, 1e-9)
        assertEquals(0.5, r.metrics.high, 1e-9)
        // and the modal day: night bins all 100, afternoon bins all 200, whatever the reading rate
        assertEquals(100.0, r.rawProfile[3].p50!!, 1e-9)
        assertEquals(200.0, r.rawProfile[18].p50!!, 1e-9)
        assertEquals(12, r.rawProfile[3].n)
        assertEquals(12, r.rawProfile[18].n)
    }

    @Test fun gapsCountAsNoData() {
        // one reading, then nothing for an hour: it stands for 15 minutes only
        val p = Period(utc, LocalDate.of(2026, 1, 1), 1)
        val rs = listOf(Reading(p.from + 3600, 120), Reading(p.from + 7200, 130))
        val w = Agp.weights(Agp.window(rs, p.from, p.to), p.to, 900)
        assertEquals(900L, w[0])
        val g = Agp.grid(rs, w, p.from, p.to, AgpOptions())
        assertEquals(3, g.count { it.second == 120 }) // 01:00, 01:05, 01:10
        assertNull(g.first { it.first == p.from + 3600 + 900 }.second)
    }

    @Test fun dstDaysKeepTheirTrueLength() {
        val spring = Period(madrid, LocalDate.of(2026, 3, 29), 1)
        val autumn = Period(madrid, LocalDate.of(2026, 10, 25), 1)
        assertEquals(23 * 3600L, spring.to - spring.from)
        assertEquals(25 * 3600L, autumn.to - autumn.from)
        val a = Agp.report(series(autumn.from, autumn.to, 300) { 120 }, autumn)
        assertEquals(25.0, a.days[0].hours, 1e-9)
        assertEquals(13.0 / 25.0, a.days[0].noon, 1e-9) // noon comes 13 elapsed hours after midnight
        // the repeated 02:00–03:00 hour lands twice in the same bin of the modal day
        assertEquals(24, a.rawProfile[2].n)
        assertEquals(12, a.rawProfile[3].n)
        val s = Agp.report(series(spring.from, spring.to, 300) { 120 }, spring)
        assertEquals(0, s.rawProfile[2].n) // 02:00–03:00 doesn't exist that night
        assertEquals(11.0 / 23.0, s.days[0].noon, 1e-9)
        assertEquals(1.0, s.metrics.active, 1e-3)
    }

    @Test fun smoothingIs141AroundTheDay() {
        fun bin(i: Int, v: Double?) = AgpBin(i * 60 + 30, if (v == null) 0 else 1, v, v, v, v, v)
        val bins = (0 until 24).map { bin(it, if (it == 0) 240.0 else 120.0) }
        val sm = Agp.smooth(bins)
        assertEquals((120 + 4 * 240 + 120) / 6.0, sm[0].p50!!, 1e-9)
        assertEquals((120 + 4 * 120 + 240) / 6.0, sm[23].p50!!, 1e-9) // wraps: 23:00 sees 00:00
        assertEquals((120 + 4 * 120 + 240) / 6.0, sm[1].p50!!, 1e-9)
        val holes = Agp.smooth((0 until 24).map { bin(it, if (it == 5) null else 100.0 + it) })
        assertNull(holes[5].p50)
        assertEquals((4 * 104.0 + 103) / 5, holes[4].p50!!, 1e-9) // empty neighbour drops out: 1-4-0 ÷ 5
    }

    @Test fun sufficiency() {
        val p = Period(utc, LocalDate.of(2026, 1, 1), 14)
        fun daysOfData(n: Int) = series(p.from, p.from + n * 86400L, 300) { 120 }
        assertFalse(Agp.report(series(p.from, p.from + 20 * 3600, 300) { 120 }, p).sufficiency.report)
        Agp.report(daysOfData(6), p).sufficiency.let { assertTrue(it.report); assertFalse(it.profile); assertFalse(it.consensus) }
        Agp.report(daysOfData(7), p).sufficiency.let { assertTrue(it.profile); assertFalse(it.consensus) }
        Agp.report(daysOfData(14), p).sufficiency.let { assertTrue(it.profile); assertTrue(it.consensus) }
    }

    @Test fun fixturesMatchTheEngine() {
        val files = File("fixtures").listFiles { f -> f.name.endsWith(".json") }!!.sorted()
        assertTrue(files.size >= 3)
        for (f in files) {
            val fx = Fixtures.read(f)
            val d = Fixtures.diff(fx.expect, Fixtures.expect(Agp.report(fx.readings, fx.period, fx.bands, fx.options)))
            assertTrue("${fx.name}: ${d.take(5)}", d.isEmpty())
        }
    }

    @Test fun fixturesAreTheSyntheticScenarios() {
        // the committed readings are exactly what the generator makes (nobody pasted real data in)
        for (s in Synthetic.SCENARIOS) {
            val fx = Fixtures.read(File("fixtures/${s.name}.json"))
            assertEquals(s.name, Synthetic.readings(s), fx.readings)
        }
    }

    @Test fun sharesNeverReadAsTheWrongSideOfTheGoal() {
        assertEquals("5.2 %", PageText.share(0.052, 0.05))       // not "5 %" against "< 5 %"
        assertEquals("70.2 %", PageText.share(0.702, 0.70, true)) // not "70 %" against "> 70 %"
        assertEquals("70 %", PageText.share(0.698, 0.70, true))   // 70 is not > 70: reads as missed, and is
        assertEquals("64 %", PageText.share(0.64, 0.70, true))
        assertEquals("0.6 %", PageText.share(0.006, 0.01))
    }

    @Test fun pageLaysOutEveryFixture() {
        for (f in File("fixtures").listFiles { x -> x.name.endsWith(".json") }!!) {
            val fx = Fixtures.read(f)
            val page = AgpPage.layout(Agp.report(fx.readings, fx.period, fx.bands, fx.options), fx.name)
            assertEquals("clips balance", page.ops.count { it is Op.Clip }, page.ops.count { it == Op.Unclip })
            assertTrue(page.ops.filterIsInstance<Op.Text>().all { it.x in 0.0..AgpPage.W && it.y in 0.0..AgpPage.H })
        }
    }
}
