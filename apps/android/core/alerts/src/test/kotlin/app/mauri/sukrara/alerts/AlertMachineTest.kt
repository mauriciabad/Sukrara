// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AlertMachine] beyond the scenarios: persistence, the service-dead no-data path, follower mirroring, effect order. */
class AlertMachineTest {
    private val t0 = 1_760_000_040L
    private var ids = 0

    private fun machine(role: AlertRole = AlertRole.WEARER, s: AlertSettings = AlertSettings()) =
        AlertMachine(role, s, newId = { "id${ids++}" })

    private fun List<AlertEffect>.fires() = filterIsInstance<AlertEffect.Fire>().map { it.firing.kind }

    @Test fun scheduleIsAlwaysTheLastEffect() {
        val m = machine()
        m.start(null, null, null, t0)
        val out = m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        assertTrue(out.last() is AlertEffect.Schedule)
        assertEquals(1, out.count { it is AlertEffect.Schedule })
        assertEquals(t0 + 5 * 60, (out.last() as AlertEffect.Schedule).at) // the low's repeat
    }

    /** The alarm receiver with the service dead: only the saved state and `now` → no data alerts on time. */
    @Test fun noDataFiresFromThePersistedStateAlone() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.Reading(AlertReading(t0, 120)), t0)
        val saved = AlertStateCodec.encode(m.snapshot())

        val receiver = machine()
        val due = t0 + 20 * 60
        val start = receiver.start(AlertStateCodec.decode(saved), null, null, due - 60)
        assertEquals(due, (start.last() as AlertEffect.Schedule).at)
        val out = receiver.handle(AlertInput.Tick, due)
        assertEquals(listOf(AlertKind.NO_DATA), out.fires())
        assertTrue(out.any { it is AlertEffect.Mirror && it.event.k == AlertKind.NO_DATA && it.event.state == AlertEvent.State.FIRE })
    }

    /** A restart never re-rings an ongoing alert, locally or to the followers (the shared run is saved too). */
    @Test fun restartResumesSilentlyAndDoesNotRepublish() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.Reading(AlertReading(t0, 200)), t0)
        val saved = AlertStateCodec.decode(AlertStateCodec.encode(m.snapshot()))
        val again = machine()
        val start = again.start(saved, AlertReading(t0 + 60, 200), null, t0 + 60)
        assertEquals(listOf(AlertEffect.Fire::class), start.filterIsInstance<AlertEffect.Fire>().map { it::class })
        assertTrue(start.filterIsInstance<AlertEffect.Fire>().single().silent)
        val next = again.handle(AlertInput.Reading(AlertReading(t0 + 60, 200)), t0 + 60)
        assertTrue(next.none { it is AlertEffect.Fire || it is AlertEffect.Mirror })
    }

    @Test fun stateOfAnotherRoleOrCorruptIsDropped() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.MuteAll(Rules.NEVER), t0)
        val follower = machine(AlertRole.FOLLOWER_OWN)
        follower.start(m.snapshot(), null, null, t0)
        assertEquals(0, follower.engine.muteAllUntil)
        assertNull(AlertStateCodec.decode("{\"v\":1,\"role\":\"WEARER\",\"local\":{\"muteAllUntil\":\"x\"}}"))
        assertNull(AlertStateCodec.decode("{\"v\":99,\"role\":\"WEARER\"}"))
    }

    /** The wearer's own Mute quiets its notification only: the followers still get the rules' alerts. */
    @Test fun localActionsNeverReachTheFollowers() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        val muted = m.handle(AlertInput.Mute(AlertKind.LOW, 30), t0 + 60)
        assertTrue(muted.any { it is AlertEffect.Clear })
        assertTrue(muted.none { it is AlertEffect.Mirror })
        val repeat = m.handle(AlertInput.Reading(AlertReading(t0 + 300, 60)), t0 + 300)
        assertEquals(emptyList<AlertKind>(), repeat.fires())
        assertEquals(1, repeat.count { it is AlertEffect.Mirror && it.event.state == AlertEvent.State.FIRE })
    }

    @Test fun mirroringFollowerShowsTheWearersEventsAndChecksItsOwnNoData() {
        val f = machine(AlertRole.FOLLOWER_MIRROR)
        f.start(null, null, null, t0)
        val fire = AlertEvent(id = "a", k = AlertKind.LOW, state = AlertEvent.State.FIRE, at = t0, t = t0, sgv = 65, th = 70.0, n = 1)
        assertEquals(listOf(AlertKind.LOW), f.handle(AlertInput.Remote(fire), t0).fires())
        // the same firing again (BLE + relays) and a stale one show nothing
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.Remote(fire), t0).fires())
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.Remote(fire.copy(id = "old", at = t0 - 11 * 60)), t0).fires())
        // its own reading of 65 never alerts here (the wearer decides), but no data is its own
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.Reading(AlertReading(t0, 65)), t0).fires())
        assertEquals(listOf(AlertKind.NO_DATA), f.handle(AlertInput.Tick, t0 + 20 * 60).fires())
        val clear = f.handle(AlertInput.Remote(fire.copy(state = AlertEvent.State.CLEAR, at = t0 + 20 * 60)), t0 + 20 * 60)
        assertTrue(clear.any { it == AlertEffect.Clear(AlertKind.LOW, "a") })
    }

    @Test fun eventsRoundTripTheWireShape() {
        val e = AlertEvent.fire(Firing(AlertKind.HIGH, "0123456789abcdef", 2, AlertReading(1760649990, 192, -0.4), 180.0, 1760650000, 1))
        val text = AlertEvent.encode(e)
        assertTrue(text, text.contains("\"k\":\"high\"") && text.contains("\"state\":\"fire\"") && text.contains("\"type\":\"alert\""))
        assertEquals(e, AlertEvent.decode(text))
        assertNull(AlertEvent.decode(text.replace("\"v\":2", "\"v\":3")))
    }

    @Test fun alertsOffClearsEverything() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        val off = m.handle(AlertInput.Settings(AlertSettings(on = false)), t0 + 60)
        assertTrue(off.any { it is AlertEffect.Clear })
        assertNull((off.last() as AlertEffect.Schedule).at)
    }

    @Test fun uiStateListsQuietSidesWithTheirExits() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.Reading(AlertReading(t0, 60)), t0)
        m.handle(AlertInput.Mute(AlertKind.LOW, 30), t0)
        m.handle(AlertInput.RemindLess(AlertKind.HIGH), t0)
        val ui = AlertUiState.of(m.engine, m.settings, m.doses, t0 + 60)
        assertEquals(QuietSide(AlertKind.LOW, true, QuietReason.NOTIFICATION, t0 + 30 * 60, 0, true), ui.quiet[0])
        assertEquals(QuietSide(AlertKind.HIGH, false, null, 0, 30, false), ui.quiet[1])
        assertEquals(
            AlertStatus.Paused(AlertKind.LOW, t0 + 30 * 60),
            AlertStatus.of(AlertReading(t0, 60), m.settings, m.doses, t0 + 60, ui),
        )
        val treated = AlertStatus.of(AlertReading(t0, 60), m.settings, Doses(carbs = listOf(t0)), t0 + 60, ui)
        assertEquals(AlertStatus.Taken(AlertKind.LOW, TreatmentKind.CARBS, t0, t0 + 15 * 60), treated)
    }

    /** A restart after the no-data deadline alerts at once (nothing else would wake it: no readings come). */
    @Test fun restartAfterADeadlineAlertsAtOnce() {
        val m = machine()
        m.start(null, null, null, t0)
        m.handle(AlertInput.Reading(AlertReading(t0, 120)), t0)
        val saved = AlertStateCodec.decode(AlertStateCodec.encode(m.snapshot()))
        val out = machine().start(saved, null, null, t0 + 25 * 60)
        assertEquals(listOf(AlertKind.NO_DATA), out.fires())
        assertTrue(out.last() is AlertEffect.Schedule)
    }

    @Test fun followerWithAlertsOffShowsNothingAndClearsWhatShowed() {
        val f = machine(AlertRole.FOLLOWER_MIRROR)
        f.start(null, null, null, t0)
        val fire = AlertEvent(id = "a", k = AlertKind.LOW, state = AlertEvent.State.FIRE, at = t0, t = t0, sgv = 65, th = 70.0, n = 1)
        f.handle(AlertInput.Remote(fire), t0)
        val off = f.handle(AlertInput.Settings(AlertSettings(on = false)), t0)
        assertTrue(off.contains(AlertEffect.Clear(AlertKind.LOW, "a")))
        assertEquals(emptyList<AlertKind>(), f.handle(AlertInput.Remote(fire.copy(id = "b")), t0).fires())
    }

    @Test fun followerMuteShowsInUiStateAndZeroMinutesChangesNothing() {
        val f = machine(AlertRole.FOLLOWER_MIRROR)
        f.start(null, null, null, t0)
        val fire = AlertEvent(id = "a", k = AlertKind.HIGH, state = AlertEvent.State.FIRE, at = t0, t = t0, sgv = 200, th = 180.0, n = 1)
        f.handle(AlertInput.Remote(fire), t0)
        assertTrue(f.handle(AlertInput.Mute(AlertKind.HIGH, 0), t0).none { it is AlertEffect.Clear })
        assertTrue(f.handle(AlertInput.Mute(AlertKind.HIGH, 30), t0).contains(AlertEffect.Clear(AlertKind.HIGH, "a")))
        assertEquals(QuietSide(AlertKind.HIGH, false, QuietReason.NOTIFICATION, t0 + 30 * 60, 0, true), f.uiState(t0).quiet.single())
        // the mirror's state survives a restart
        val again = machine(AlertRole.FOLLOWER_MIRROR)
        again.start(AlertStateCodec.decode(AlertStateCodec.encode(f.snapshot())), null, null, t0 + 60)
        assertEquals(
            t0 + 30 * 60,
            again
                .uiState(t0 + 60)
                .quiet
                .single()
                .until,
        )
    }
}
