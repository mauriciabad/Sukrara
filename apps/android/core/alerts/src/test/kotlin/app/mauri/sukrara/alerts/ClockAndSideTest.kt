// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Safety cases the scenarios don't cover: a mirroring follower's actions stay on their side (low and high share one
 * group), the clock set back or forward, and a reading dated in the future. None may make an alert go missing.
 */
class ClockAndSideTest {
    private val t0 = 1_760_000_040L
    private var ids = 0

    private fun machine(role: AlertRole = AlertRole.WEARER) =
        AlertMachine(role, AlertSettings(), newId = { "id${ids++}" })
            .also { it.start(null, null, null, t0) }

    private fun List<AlertEffect>.fires() = filterIsInstance<AlertEffect.Fire>().map { it.firing.kind }

    private fun List<AlertEffect>.clears() = filterIsInstance<AlertEffect.Clear>().map { it.kind }

    private fun fire(id: String, k: AlertKind, at: Long = t0) =
        AlertInput.Remote(AlertEvent(id = id, k = k, state = AlertEvent.State.FIRE, at = at, t = at, sgv = 65, th = 70.0, n = 1))

    // region follower mirror: side scope (a high's Mute never hides a showing low)

    @Test fun aHighsMuteNeverHidesAShowingLow() {
        val f = machine(AlertRole.FOLLOWER_MIRROR)
        assertEquals(listOf(AlertKind.LOW), f.handle(fire("a", AlertKind.LOW), t0).fires())
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.Mute(AlertKind.HIGH, 30), t0 + 60).clears())
        assertTrue(f.wearerMirror!!.showing(AlertGroup.LEVEL))
        // the low's own Mute does hide it
        assertEquals(listOf(AlertKind.LOW), f.handle(AlertInput.Mute(AlertKind.VERY_LOW, 30), t0 + 120).clears())
    }

    @Test fun aHighsUnmuteNeverReShowsAMutedLow() {
        val f = machine(AlertRole.FOLLOWER_MIRROR)
        f.handle(fire("a", AlertKind.LOW), t0)
        f.handle(AlertInput.Mute(AlertKind.LOW, 60), t0 + 60)
        f.handle(AlertInput.Mute(AlertKind.HIGH, 60), t0 + 60)
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.Unmute(AlertKind.HIGH), t0 + 120).fires())
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.RemindAsUsual(AlertKind.HIGH), t0 + 120).fires())
        // the low's Unmute shows the wearer's low again at once
        assertEquals(listOf(AlertKind.LOW), f.handle(AlertInput.Unmute(AlertKind.LOW), t0 + 180).fires())
    }

    @Test fun aHighsMuteStillDropsTheWearersHighs() {
        val f = machine(AlertRole.FOLLOWER_MIRROR)
        f.handle(AlertInput.Mute(AlertKind.HIGH, 30), t0)
        assertEquals(emptyList<AlertKind>(), f.handle(fire("h", AlertKind.EXTREMELY_HIGH), t0 + 60).fires())
        assertEquals(listOf(AlertKind.LOW), f.handle(fire("l", AlertKind.LOW, t0 + 120), t0 + 120).fires())
    }

    // endregion
    // region the clock (the time between the last input and the jump is unknown: it counts as zero, so what was due
    // moves at most one input interval later — never by the length of the jump)

    /** Set back 1 h while a low is up: the repeat still comes ~5 min after the last alert, not 65 min later. */
    @Test fun clockSetBackKeepsTheRepeatOnTime() {
        val m = machine()
        assertEquals(listOf(AlertKind.LOW), m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0).fires())
        val back = t0 - 3600
        val first = m.handle(AlertInput.Reading(AlertReading(back + 60, 60)), back + 60)
        assertEquals(emptyList<AlertKind>(), first.fires())
        assertEquals(back + 60, m.engine.last!!.t) // the new reading counts, the old one doesn't hide it
        assertEquals(back + 60 + 300, (first.last() as AlertEffect.Schedule).at)
        assertEquals(listOf(AlertKind.LOW), m.handle(AlertInput.Reading(AlertReading(back + 360, 60)), back + 360).fires())
    }

    /** Set back 1 h during a 30 min Mute: it still ends 30 min after it started, not 90. */
    @Test fun clockSetBackKeepsAMutesLength() {
        val m = machine()
        m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        m.handle(AlertInput.Mute(AlertKind.LOW, 30), t0)
        val back = t0 - 3600
        m.handle(AlertInput.Tick, back + 60)
        assertEquals(
            back + 60 + 30 * 60,
            m.engine
                .snoozes(back + 60)
                .getValue(Side.LOW)
                .until,
        )
    }

    /** No data counts from the last reading's age, also across a clock set back while the process was down. */
    @Test fun clockSetBackAcrossARestartKeepsNoDataOnTime() {
        val m = machine()
        m.handle(AlertInput.Reading(AlertReading(t0, 120)), t0)
        val saved = AlertStateCodec.decode(AlertStateCodec.encode(m.snapshot()))
        val back = t0 - 3600
        val again = AlertMachine(AlertRole.WEARER, AlertSettings())
        again.start(saved, null, null, back + 60)
        assertEquals(emptyList<AlertKind>(), again.handle(AlertInput.Tick, back + 60 + 19 * 60).fires())
        assertEquals(listOf(AlertKind.NO_DATA), again.handle(AlertInput.Tick, back + 60 + 20 * 60).fires())
    }

    /** A clock jumping forward never silences: the stale reading stops alerting and no data alerts at once. */
    @Test fun clockForwardAlertsNoDataInsteadOfGoingQuiet() {
        val m = machine()
        m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        val out = m.handle(AlertInput.Tick, t0 + 2 * 3600)
        assertEquals(listOf(AlertKind.LOW), out.clears())
        assertEquals(listOf(AlertKind.NO_DATA), out.fires())
    }

    /** NTP-sized corrections (under a minute back) change nothing. */
    @Test fun smallBackwardCorrectionsAreIgnored() {
        val m = machine()
        m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        m.handle(AlertInput.Tick, t0 - 30)
        assertEquals(t0, m.engine.last!!.t)
    }

    // endregion

    /** A reading dated 10 min ahead counts as now, and the next real reading isn't hidden behind it. */
    @Test fun futureDatedReadingDoesNotHideTheNextOnes() {
        val m = machine()
        m.handle(AlertInput.Reading(AlertReading(t0 + 600, 120)), t0)
        assertEquals(t0, m.engine.last!!.t)
        assertEquals(listOf(AlertKind.LOW), m.handle(AlertInput.Reading(AlertReading(t0 + 60, 60)), t0 + 60).fires())
    }
}
