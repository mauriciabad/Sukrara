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
    private val none = AlertEngine.Doses()

    private fun fires(a: List<AlertEngine.Action>) = a.filterIsInstance<AlertEngine.Fire>().map { it.kind }
    private fun clears(a: List<AlertEngine.Action>) = a.filterIsInstance<AlertEngine.Clear>().map { it.kind }
    private fun min(m: Int) = t0 + m * 60L

    /** Feeds one reading per minute; returns all actions with the minute they happened. */
    private fun run(e: AlertEngine, values: List<Pair<Int, Double?>>, d: AlertEngine.Doses = none, set: AlertSettings = s,
                    start: Long = t0): List<Pair<Int, AlertEngine.Action>> =
        values.flatMapIndexed { i, (sgv, rate) ->
            val t = start + i * 60L
            e.reading(Reading(t, sgv, rate), set, d, t).map { i to it }
        }

    /** (minute, headline) of every firing. */
    private fun headlines(a: List<Pair<Int, AlertEngine.Action>>) =
        a.mapNotNull { (m, x) -> (x as? AlertEngine.Fire)?.let { m to Kind.message(it.kind, it.treated) } }

    @Test fun defaultsAreTheUsersSettings() {
        fun k(kind: String) = s.cfg(kind)
        assertEquals(KindCfg(true, 55.0, 5, alarm = true), k(Kind.VERY_LOW))
        assertEquals(KindCfg(true, 70.0, 5, alarm = false), k(Kind.LOW))
        assertEquals(KindCfg(true, 180.0, 15, alarm = false), k(Kind.HIGH))
        assertEquals(KindCfg(true, 250.0, 10, alarm = false), k(Kind.VERY_HIGH))
        assertEquals(KindCfg(true, 300.0, 5, alarm = true), k(Kind.EXTREMELY_HIGH))
        assertEquals(KindCfg(false, 2.0, 30), k(Kind.FALLING_FAST))
        assertEquals(KindCfg(false, 2.0, 30), k(Kind.RISING_FAST))
        assertEquals(KindCfg(true, 20.0, 60), k(Kind.NO_DATA))
        assertTrue(s.muteInsulin && s.muteInsulinMin == 60 && s.muteFalling && s.muteFallingMin == 20 && s.muteCarbs && s.muteCarbsMin == 15)
        assertEquals("quest", s.pack)
        assertTrue(s.shuffle && s.packFor.isEmpty() && s.source == "wearer" && s.on)
    }

    @Test fun lowsAreStrictHighsInclusive() {
        assertEquals(emptyList<String>(), fires(AlertEngine().reading(Reading(t0, 70, 0.0), s, none, t0)))
        assertEquals(listOf("low"), fires(AlertEngine().reading(Reading(t0, 69, 0.0), s, none, t0)))
        assertEquals(listOf("low"), fires(AlertEngine().reading(Reading(t0, 55, 0.0), s, none, t0)))
        assertEquals(listOf("very_low"), fires(AlertEngine().reading(Reading(t0, 54, 0.0), s, none, t0)))
        assertEquals(emptyList<String>(), fires(AlertEngine().reading(Reading(t0, 179, 0.0), s, none, t0)))
        assertEquals(listOf("high"), fires(AlertEngine().reading(Reading(t0, 180, 0.0), s, none, t0)))
        assertEquals(listOf("very_high"), fires(AlertEngine().reading(Reading(t0, 250, 0.0), s, none, t0)))
        assertEquals(listOf("extremely_high"), fires(AlertEngine().reading(Reading(t0, 300, 0.0), s, none, t0)))
    }

    /**
     * The user's timeline (2026-10-18, repeat 5 min, carbs mute 5 min): a mute that ends while still low alerts at
     * once and the repeats count from there; a reading back at 70 for a minute doesn't start a new episode.
     */
    @Test fun usersCarbsTimeline() {
        val set = s.copy(muteCarbsMin = 5)
        val d = AlertEngine.Doses(carbs = listOf(min(3), min(14)))
        val values = List(26) { m -> (if (m == 15 || m == 20) 70 else 69) to 0.0 }
        val e = AlertEngine()
        val out = values.flatMapIndexed { m, (sgv, rate) ->
            val acts = e.reading(Reading(min(m), sgv, rate), set, d, min(m)).map { m to it }
            if (m == 3 || m == 14) assertEquals(min(m) + 5 * 60, e.nextCheckAt(set, min(m)))   // the timer wakes at the mute end
            acts
        }
        assertEquals(listOf(
            0 to "Low glucose",
            8 to "Low glucose · eat more",
            13 to "Low glucose · eat more",
            19 to "Low glucose · eat more again",
            24 to "Low glucose · eat more again",
        ), headlines(out))
        // logging carbs removes the alert that's up; so does each reading back at 70
        assertEquals(listOf(3, 14, 20), out.filter { it.second is AlertEngine.Clear }.map { it.first })
        assertEquals(listOf(1, 2, 3, 4, 5), out.mapNotNull { (it.second as? AlertEngine.Fire)?.n })
    }

    @Test fun carbsMuteEndsBetweenReadingsTimerFiresOnTime() {
        // readings every 5 min, carbs mute 15: the alert comes at the mute end (minute 18), not at the reading at 20
        val e = AlertEngine()
        val d = AlertEngine.Doses(carbs = listOf(min(3)))
        assertEquals(listOf("low"), fires(e.reading(Reading(min(0), 65, 0.0), s, d, min(0))))
        assertEquals(min(5), e.nextCheckAt(s, min(0)))
        assertEquals(listOf("low"), clears(e.evaluate(s, d, min(3))))   // the treatment is logged
        e.reading(Reading(min(5), 64, 0.0), s, d, min(5))
        e.reading(Reading(min(10), 64, 0.0), s, d, min(10))
        e.reading(Reading(min(15), 64, 0.0), s, d, min(15))
        assertEquals(min(18), e.nextCheckAt(s, min(15)))
        val f = e.evaluate(s, d, min(18)).filterIsInstance<AlertEngine.Fire>().single()
        assertEquals("Low glucose · eat more", Kind.message(f.kind, f.treated))
        assertEquals(min(23), e.nextCheckAt(s, min(18)))
        assertEquals(emptyList<String>(), fires(e.reading(Reading(min(20), 64, 0.0), s, d, min(20))))
        assertEquals(listOf("low"), fires(e.evaluate(s, d, min(23))))
    }

    @Test fun episodeEndsAfterTheHoldTime() {
        // back in range for a full repeat interval (5 min): the next low is a new episode, alerting at once, unacknowledged
        val e = AlertEngine()
        val d = AlertEngine.Doses(carbs = listOf(min(1)))
        val values = listOf(60, 60, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 75, 60, 60).map { it to 0.0 }
        val a = run(e, values, d)
        assertEquals(listOf(0 to "Low glucose", 22 to "Low glucose"), headlines(a))
        assertEquals(listOf(1, 1), a.mapNotNull { (it.second as? AlertEngine.Fire)?.n })
        // last low at 0, back at 4 (< 5 min): the same episode, the repeats go on from 0
        val v2 = listOf(60, 75, 75, 75, 60, 60, 60, 60, 60, 60, 60).map { it to 0.0 }
        val a2 = run(AlertEngine(), v2)
        assertEquals(listOf(0 to 1, 5 to 2, 10 to 3), a2.mapNotNull { (m, x) -> (x as? AlertEngine.Fire)?.let { m to it.n } })
    }

    @Test fun shortBreakDoesNotRealertEarly() {
        val e = AlertEngine()
        // fired at 0; out at 1–2; back at 3: repeat due at 5, not at 3
        val a = run(e, listOf(60, 75, 75, 60, 60, 60).map { it to 0.0 })
        assertEquals(listOf(0, 5), a.filter { it.second is AlertEngine.Fire }.map { it.first })
    }

    @Test fun highRepeatsEveryRepeatMin() {
        val e = AlertEngine()
        val out = run(e, List(32) { 200 to 0.0 })
        assertEquals(listOf(0 to "high", 15 to "high", 30 to "high"),
            out.filter { it.second is AlertEngine.Fire }.map { it.first to it.second.kind })
        assertEquals(3, (out.last { it.second is AlertEngine.Fire }.second as AlertEngine.Fire).n)
    }

    @Test fun escalatesAndClears() {
        val e = AlertEngine()
        val a = run(e, listOf(190 to 1.0, 260 to 1.0, 150 to -1.0))
        assertEquals(listOf("high", "very_high"), fires(a.map { it.second }))
        assertEquals(listOf("high", "very_high"), clears(a.map { it.second }))
    }

    @Test fun lowToVeryLowAtOnceButNoFlapping() {
        val e = AlertEngine()
        // low at 0, very low at 1 (at once), low at 2 (quiet: the user knows), very low at 3 (quiet: fired 2 min ago)
        val a = run(e, listOf(64, 54, 56, 54, 54, 54, 54).map { it to -1.0 })
        assertEquals(listOf(0 to "low", 1 to "very_low", 6 to "very_low"),
            a.filter { it.second is AlertEngine.Fire }.map { it.first to it.second.kind })
    }

    @Test fun insulinMutesHighThenFiresWhenWindowEnds() {
        val e = AlertEngine()
        val d = AlertEngine.Doses(rapid = listOf(t0 - 5 * 60))   // 5 min before the first reading, window 60 min → ends at minute 55
        val a = run(e, List(60) { 200 to 0.0 }, d)
        assertEquals(listOf(55 to "High glucose · still high after insulin"), headlines(a))
        assertEquals("high", e.states()["level"]?.kind)
    }

    @Test fun insulinTimelineNeverSuggestsMoreInsulin() {
        val e = AlertEngine()
        val d = AlertEngine.Doses(rapid = listOf(min(2), min(70)))
        val a = run(e, List(140) { 200 to 0.0 }, d)
        assertEquals(listOf(
            0 to "High glucose",
            62 to "High glucose · still high after insulin",
            130 to "High glucose · still high after more insulin",
        ), headlines(a))
    }

    @Test fun extremelyHighIsNeverMuted() {
        val d = AlertEngine.Doses(rapid = listOf(t0 - 60))
        val e = AlertEngine()
        // very high is muted by the insulin, extremely high fires at once (falling fast too: no falling mute)
        assertEquals(emptyList<String>(), fires(e.reading(Reading(t0, 260, 1.0), s, d, t0)))
        val f = e.reading(Reading(t0 + 60, 301, 1.0), s, d, t0 + 60).filterIsInstance<AlertEngine.Fire>().single()
        assertEquals("Extremely high glucose · still high after insulin", Kind.message(f.kind, f.treated))
        assertEquals(listOf("extremely_high"), fires(AlertEngine().reading(Reading(t0, 320, -3.0), s, none, t0)))
        assertEquals(listOf("very_high"), fires(AlertEngine().reading(Reading(t0, 299, 0.0), s, none, t0)))
    }

    @Test fun insulinAfterAlertClearsIt() {
        val e = AlertEngine()
        e.reading(Reading(t0, 200, 0.0), s, none, t0)
        val a = e.reading(Reading(t0 + 60, 201, 0.0), s, AlertEngine.Doses(rapid = listOf(t0 + 30)), t0 + 60)
        assertEquals(listOf("high"), clears(a))
        assertTrue(e.states()["level"]!!.muted!!.startsWith("insulin"))
    }

    @Test fun insulinDoesNotMuteLows() {
        val e = AlertEngine()
        assertEquals(listOf("low"), fires(e.reading(Reading(t0, 60, 0.0), s, AlertEngine.Doses(rapid = listOf(t0 - 60)), t0)))
    }

    @Test fun carbsLoggedBeforeTheLow() {
        // carbs at −5 (window 15 → ends at 10): quiet until then, then it says the carbs weren't enough
        val e = AlertEngine()
        val a = run(e, List(15) { 60 to 0.0 }, AlertEngine.Doses(carbs = listOf(t0 - 5 * 60)))
        assertEquals(listOf(10 to "Low glucose · eat more"), headlines(a))
    }

    @Test fun carbsMuteVeryLowButNeverHighs() {
        val d = AlertEngine.Doses(carbs = listOf(t0 - 60))
        val e = AlertEngine()
        assertEquals(emptyList<String>(), fires(e.reading(Reading(t0, 50, 0.0), s, d, t0)))
        assertTrue(e.states()["level"]!!.muted!!.startsWith("carbs"))
        assertEquals(listOf("high"), fires(AlertEngine().reading(Reading(t0, 200, 0.0), s, d, t0)))
        assertEquals(listOf("very_low"), fires(AlertEngine().reading(Reading(t0, 50, 0.0), s.copy(muteCarbs = false), d, t0)))
        assertEquals(listOf("low"), fires(AlertEngine().reading(Reading(t0, 60, 0.0), s.copy(muteCarbs = false), d, t0)))
    }

    @Test fun carbsWithTheMuteOffStillShowInTheMessage() {
        val off = s.copy(muteCarbs = false)
        val a = run(AlertEngine(), List(6) { 60 to 0.0 }, AlertEngine.Doses(carbs = listOf(min(2))), off)
        assertEquals(listOf(0 to "Low glucose", 5 to "Low glucose · eat more"), headlines(a))
    }

    @Test fun fallingHighMuteOnlyPostpones() {
        val e = AlertEngine()
        // high fires at 0; falling back into range at 2 (cleared, quiet); flat again at 3: the repeat stays due at 15
        val a = run(e, listOf(200 to 0.0, 200 to 0.0, 195 to -1.0) + List(14) { 200 to 0.0 })
        assertEquals(listOf(0, 15), a.filter { it.second is AlertEngine.Fire }.map { it.first })
        assertEquals(listOf(2), a.filter { it.second is AlertEngine.Clear }.map { it.first })
        // a fall that doesn't get under 180 in 20 min doesn't mute
        assertEquals(listOf("high"), fires(AlertEngine().reading(Reading(t0, 240, -1.0), s, none, t0)))
        val m = AlertEngine()
        assertEquals(emptyList<String>(), fires(m.reading(Reading(t0, 195, -1.0), s, none, t0)))
        assertTrue(m.states()["level"]!!.muted!!.contains("back in range"))
    }

    @Test fun mutesCanBeSwitchedOff() {
        val off = s.copy(muteInsulin = false, muteFalling = false)
        val e = AlertEngine()
        assertEquals(listOf("high"), fires(e.reading(Reading(t0, 200, -2.0), off, AlertEngine.Doses(rapid = listOf(t0)), t0)))
    }

    @Test fun rateAlertsAreIndependentAndOffByDefault() {
        val on = s.copy(kinds = s.kinds + (Kind.FALLING_FAST to KindCfg(true, 2.0, 30)))
        assertEquals(emptyList<String>(), fires(AlertEngine().reading(Reading(t0, 120, -3.0), s, none, t0)))
        assertEquals(listOf("falling_fast"), fires(AlertEngine().reading(Reading(t0, 120, -3.0), on, none, t0)))
        assertEquals(listOf("low", "falling_fast"), fires(AlertEngine().reading(Reading(t0, 60, -3.0), on, none, t0)))
        // carbs quiet falling fast too; its message never advises eating
        assertEquals(emptyList<String>(), fires(AlertEngine().reading(Reading(t0, 120, -3.0), on, AlertEngine.Doses(carbs = listOf(t0)), t0)))
        assertEquals("Falling fast", Kind.message(Kind.FALLING_FAST, 2))
    }

    @Test fun noDataByTimerAndClearedByNextReading() {
        val e = AlertEngine()
        e.reading(Reading(t0, 120, 0.0), s, none, t0)
        assertEquals(t0 + 15 * 60 + 1, e.nextCheckAt(s, t0))
        assertEquals(emptyList<String>(), fires(e.evaluate(s, none, t0 + 19 * 60)))
        assertEquals(listOf("no_data"), fires(e.evaluate(s, none, t0 + 20 * 60)))
        assertEquals(listOf("no_data"), clears(e.reading(Reading(t0 + 21 * 60, 121, 0.0), s, none, t0 + 21 * 60)))
    }

    @Test fun seededAfterRestartNoDataStillFires() {
        val e = AlertEngine()
        e.seed(Reading(t0 - 30 * 60, 50, 0.0))   // last stored reading: 30 min ago, low
        val a = e.evaluate(s, none, t0)
        assertEquals(listOf("no_data"), fires(a))   // no late low alert, but no data counts from it
    }

    @Test fun staleReadingsNeverAlert() {
        val e = AlertEngine()
        assertEquals(emptyList<String>(), fires(e.reading(Reading(t0 - 16 * 60, 40, 0.0), s.copy(), none, t0)))
    }

    @Test fun masterSwitchClearsEverything() {
        val e = AlertEngine()
        e.reading(Reading(t0, 50, 0.0), s, none, t0)
        assertEquals(listOf("very_low"), clears(e.evaluate(s.copy(on = false), none, t0 + 1)))
        assertNull(e.states()["level"])
    }

    @Test fun messages() {
        assertEquals("Very low glucose", Kind.message(Kind.VERY_LOW))
        assertEquals("Very low glucose · eat more", Kind.message(Kind.VERY_LOW, 1))
        assertEquals("Low glucose · eat more again", Kind.message(Kind.LOW, 2))
        assertEquals("Very high glucose · still high after more insulin", Kind.message(Kind.VERY_HIGH, 2))
        assertEquals("No glucose data", Kind.message(Kind.NO_DATA, 1))
    }

    @Test fun settingsJsonRoundTrip() {
        val x = s.copy(pack = "kart", packFor = mapOf("high" to "quest"), source = "own", muteCarbs = false, muteCarbsMin = 30,
            kinds = s.kinds + (Kind.RISING_FAST to KindCfg(true, 2.5, 10)))
        val back = AlertSettings.fromJson(JSONObject(x.toJson().toString()))
        assertEquals(x, back)
        assertEquals(55, x.toJson().getJSONObject("alerts").getJSONObject("very_low").getInt("mgdl"))
        assertTrue(x.toJson().getJSONObject("alerts").getJSONObject("very_low").getBoolean("alarm"))
        val flipped = AlertSettings.fromJson(JSONObject("{\"alerts\":{\"low\":{\"alarm\":true},\"high\":{\"alarm\":true},\"urgent_low\":{\"mgdl\":40}}}"))
        assertEquals(true, flipped.cfg(Kind.LOW).alarm)
        assertEquals(true, flipped.cfg(Kind.HIGH).alarm)
        assertEquals(70.0, flipped.cfg(Kind.LOW).value, 0.0)
        assertEquals(55.0, flipped.cfg(Kind.VERY_LOW).value, 0.0)   // the old id is simply gone
    }
    /**
     * Two sounds at once (docs/15): an app update kills the process; the new one must not take an ongoing alert for a
     * new one. The episode comes back from its snapshot, the notification that was up is handed back (shown again
     * silently), and the next sound is the repeat, on its normal time.
     */
    @Test fun restartResumesWithoutRingingAgain() {
        val before = AlertEngine()
        val first = run(before, List(4) { 200 to 0.0 })
        assertEquals(listOf(0 to "High glucose"), headlines(first))
        val saved = JSONObject(before.snapshot().toString())

        val after = AlertEngine()
        after.seed(Reading(min(3), 200, 0.0))
        val shown = after.restore(saved, s, min(4))
        assertEquals(listOf(Kind.HIGH), shown.map { it.kind })
        assertEquals((first.single().second as AlertEngine.Fire).id, shown.single().id)
        val rest = run(after, List(16) { 200 to 0.0 }, start = min(4)).map { (m, a) -> m + 4 to a }
        assertEquals(listOf(15 to "High glucose"), headlines(rest))   // repeat 15 min after the first, not at the restart

        // a fresh engine (the old behaviour) rings at once: the second sound
        assertEquals(listOf(0 to "High glucose"), headlines(run(AlertEngine(), listOf(200 to 0.0), start = min(4))))
    }

    @Test fun restoreDropsEpisodesThatEndedWhileDown() {
        val before = AlertEngine()
        run(before, listOf(200 to 0.0))
        val after = AlertEngine()
        assertTrue(after.restore(before.snapshot(), s, min(60)).isEmpty())
        assertNull(after.states()["level"])
    }
}
