// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertEngineTest {
    private val s = AlertSettings()
    private val t0 = 1_760_000_000L

    private fun fires(a: List<AlertEngine.Action>) = a.filterIsInstance<AlertEngine.Fire>().map { it.kind }
    private fun clears(a: List<AlertEngine.Action>) = a.filterIsInstance<AlertEngine.Clear>().map { it.kind }

    /** Feeds one reading per minute; returns all actions with the minute they happened. */
    private fun run(e: AlertEngine, values: List<Pair<Int, Double?>>, rapid: List<Long> = emptyList(), set: AlertSettings = s,
                    start: Long = t0): List<Pair<Int, AlertEngine.Action>> =
        values.flatMapIndexed { i, (sgv, rate) ->
            val t = start + i * 60L
            e.reading(Reading(t, sgv, rate), set, rapid, t).map { i to it }
        }

    @Test fun highFiresOnceThenRepeatsAfterRepeatMin() {
        val e = AlertEngine()
        val out = run(e, List(40) { 200 to 0.0 })
        assertEquals(listOf(0 to "high", 30 to "high"), out.filter { it.second is AlertEngine.Fire }.map { it.first to it.second.kind })
        assertEquals(2, (out.last { it.second is AlertEngine.Fire }.second as AlertEngine.Fire).n)
    }

    @Test fun escalatesToVeryHighAndClears() {
        val e = AlertEngine()
        val a = run(e, listOf(190 to 1.0, 260 to 1.0, 150 to -1.0))
        assertEquals(listOf("high", "very_high"), fires(a.map { it.second }))
        assertEquals(listOf("high", "very_high"), clears(a.map { it.second }))
    }

    @Test fun lowToUrgentLow() {
        val e = AlertEngine()
        val a = run(e, listOf(64 to -1.0, 54 to -1.0)).map { it.second }
        assertEquals(listOf("low", "urgent_low"), fires(a))
    }

    @Test fun noFlappingAroundThreshold() {
        val e = AlertEngine()
        val a = run(e, listOf(186 to 0.0, 184 to 0.0, 186 to 0.0, 184 to 0.0, 186 to 0.0)).map { it.second }
        assertEquals(listOf("high"), fires(a))
    }

    @Test fun insulinMutesHighThenFiresWhenWindowEnds() {
        val e = AlertEngine()
        val rapid = listOf(t0 - 5 * 60)   // 5 min before the first reading, window 20 min → ends at minute 15
        val a = run(e, List(20) { 200 to 0.0 }, rapid)
        val f = a.filter { it.second is AlertEngine.Fire }
        assertEquals(listOf(15 to "high"), f.map { it.first to it.second.kind })
        assertEquals("high", e.states()["level"]?.kind)
    }

    @Test fun insulinAfterAlertClearsIt() {
        val e = AlertEngine()
        e.reading(Reading(t0, 200, 0.0), s, emptyList(), t0)
        val a = e.reading(Reading(t0 + 60, 201, 0.0), s, listOf(t0 + 30), t0 + 60)
        assertEquals(listOf("high"), clears(a))
        assertTrue(e.states()["level"]!!.muted!!.startsWith("insulin"))
    }

    @Test fun insulinDoesNotMuteLows() {
        val e = AlertEngine()
        assertEquals(listOf("low"), fires(e.reading(Reading(t0, 60, 0.0), s, listOf(t0 - 60), t0)))
    }

    @Test fun fallingHighIsMutedOnlyIfBackInRangeWithinWindow() {
        val e = AlertEngine()
        // 200 falling 1/min → 180 in 20 min (< 185): muted
        assertEquals(emptyList<String>(), fires(e.reading(Reading(t0, 200, -1.0), s, emptyList(), t0)))
        assertTrue(e.states()["level"]!!.muted!!.contains("back in range"))
        // flattens: fires at once
        assertEquals(listOf("high"), fires(e.reading(Reading(t0 + 60, 199, 0.0), s, emptyList(), t0 + 60)))
        // 240 falling 1/min → 220 after 20 min: not muted (other engine)
        val e2 = AlertEngine()
        assertEquals(listOf("high"), fires(e2.reading(Reading(t0, 240, -1.0), s, emptyList(), t0)))
    }

    @Test fun mutesCanBeSwitchedOff() {
        val off = s.copy(muteInsulin = false, muteFalling = false)
        val e = AlertEngine()
        assertEquals(listOf("high"), fires(e.reading(Reading(t0, 200, -2.0), off, listOf(t0), t0)))
    }

    @Test fun rateAlertsAreIndependentAndOffByDefault() {
        val on = s.copy(kinds = s.kinds + (Kind.FALLING_FAST to KindCfg(true, 2.0, 30)))
        assertEquals(emptyList<String>(), fires(AlertEngine().reading(Reading(t0, 120, -3.0), s, emptyList(), t0)))
        assertEquals(listOf("falling_fast"), fires(AlertEngine().reading(Reading(t0, 120, -3.0), on, emptyList(), t0)))
        assertEquals(listOf("low", "falling_fast"), fires(AlertEngine().reading(Reading(t0, 60, -3.0), on, emptyList(), t0)))
    }

    @Test fun noDataByTimerAndClearedByNextReading() {
        val e = AlertEngine()
        e.reading(Reading(t0, 120, 0.0), s, emptyList(), t0)
        assertEquals(t0 + 15 * 60 + 1, e.nextCheckAt(s, t0))
        assertEquals(emptyList<String>(), fires(e.evaluate(s, emptyList(), t0 + 19 * 60)))
        assertEquals(listOf("no_data"), fires(e.evaluate(s, emptyList(), t0 + 20 * 60)))
        assertEquals(listOf("no_data"), clears(e.reading(Reading(t0 + 21 * 60, 121, 0.0), s, emptyList(), t0 + 21 * 60)))
    }

    @Test fun seededAfterRestartNoDataStillFires() {
        val e = AlertEngine()
        e.seed(Reading(t0 - 30 * 60, 50, 0.0))   // last stored reading: 30 min ago, low
        val a = e.evaluate(s, emptyList(), t0)
        assertEquals(listOf("no_data"), fires(a))   // no late low alert, but no data counts from it
    }

    @Test fun staleReadingsNeverAlert() {
        val e = AlertEngine()
        assertEquals(emptyList<String>(), fires(e.reading(Reading(t0 - 16 * 60, 40, 0.0), s.copy(), emptyList(), t0)))
    }

    @Test fun masterSwitchClearsEverything() {
        val e = AlertEngine()
        e.reading(Reading(t0, 50, 0.0), s, emptyList(), t0)
        assertEquals(listOf("urgent_low"), clears(e.evaluate(s.copy(on = false), emptyList(), t0 + 1)))
        assertNull(e.states()["level"])
    }

    @Test fun settingsJsonRoundTrip() {
        val x = s.copy(pack = "kart", packFor = mapOf("high" to "quest"), source = "own",
            kinds = s.kinds + (Kind.RISING_FAST to KindCfg(true, 2.5, 10)))
        val back = AlertSettings.fromJson(JSONObject(x.toJson().toString()))
        assertEquals(x, back)
        assertEquals(55, x.toJson().getJSONObject("alerts").getJSONObject("urgent_low").getInt("mgdl"))
        assertTrue(x.toJson().getJSONObject("alerts").getJSONObject("low").getBoolean("alarm"))
        val flipped = AlertSettings.fromJson(JSONObject("{\"alerts\":{\"low\":{\"alarm\":false},\"high\":{\"alarm\":true}}}"))
        assertEquals(false, flipped.cfg(Kind.LOW).alarm)
        assertEquals(true, flipped.cfg(Kind.HIGH).alarm)
        assertEquals(65.0, flipped.cfg(Kind.LOW).value, 0.0)
    }
}
