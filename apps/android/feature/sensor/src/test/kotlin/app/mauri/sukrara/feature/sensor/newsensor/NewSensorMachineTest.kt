// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Cancel
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Continue
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Retry
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Source
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Start
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.TagFound
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Tapped
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.Tick
import app.mauri.sukrara.feature.sensor.newsensor.NewSensorEvent.UseJuggluco
import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.pairing.PairOutcome
import app.mauri.sukrara.sensor.source.SensorStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Duration
import java.time.Instant

class NewSensorMachineTest {
    private val t0: Instant = Instant.parse("2026-10-27T08:00:00Z")
    private val ok =
        SensorEnv(
            coreAvailable = true,
            nfcAvailable = true,
            nfcOn = true,
            signedIn = true,
            officialAppInstalled = false,
            bluetoothPermission = true,
            bluetoothOn = true,
            notificationsAllowed = true,
        )

    private fun run(vararg events: NewSensorEvent, from: NewSensorState = NewSensorState(), now: Instant = t0) =
        events.fold(from) { s, e -> NewSensorMachine.reduce(s, e, now) }

    private fun step(vararg events: NewSensorEvent, now: Instant = t0) = run(*events, now = now).step

    private val paired = arrayOf(Start(ok), TagFound, Tapped(PairOutcome.Paired("ABC", activated = true)))

    private fun live(serial: String, link: LinkStatus, state: SensorState = SensorState.Active(null)) =
        Source(SensorStatus.Direct(SensorProfile.Libre3, serial, serial, link), state)

    @Test fun `all gates open goes straight to the tap`() =
        assertEquals(NewSensorStep.WaitingForTap, step(Start(ok)))

    @Test fun `gates come in order and each blocks before the next`() {
        val cases =
            listOf(
                ok.copy(coreAvailable = false, nfcAvailable = false, signedIn = false) to NewSensorStep.CoreMissing,
                ok.copy(nfcAvailable = false, signedIn = false) to NewSensorStep.NoNfc,
                ok.copy(nfcOn = false, signedIn = false) to NewSensorStep.NfcOff,
                ok.copy(signedIn = false, officialAppInstalled = true) to NewSensorStep.NeedsLibreViewSignIn,
                ok.copy(officialAppInstalled = true, bluetoothPermission = false) to NewSensorStep.OfficialAppWarning,
                ok.copy(bluetoothPermission = false, bluetoothOn = false) to NewSensorStep.NeedsBluetoothPermission,
                ok.copy(bluetoothOn = false, notificationsAllowed = false) to NewSensorStep.BluetoothOff,
                ok.copy(notificationsAllowed = false) to NewSensorStep.NeedsNotifications,
            )
        cases.forEach { (env, expected) -> assertEquals(env.toString(), expected, step(Start(env))) }
    }

    @Test fun `no LibreView account never reaches a tap, so nothing is sent to the sensor`() {
        val noAccount = ok.copy(signedIn = false)
        val s = run(Start(noAccount), Continue, TagFound, Tapped(PairOutcome.Paired("ABC", true)))
        assertEquals(NewSensorStep.NeedsLibreViewSignIn, s.step)
        assertFalse(s.step.acceptsTap)
    }

    @Test fun `signing in and coming back re-checks the gates`() {
        val s = run(Start(ok.copy(signedIn = false)), Start(ok))
        assertEquals(NewSensorStep.WaitingForTap, s.step)
    }

    @Test fun `a tap answering that sign-in is needed asks for it`() =
        assertEquals(
            NewSensorStep.NeedsLibreViewSignIn,
            step(Start(ok), TagFound, Tapped(PairOutcome.NeedsLibreViewSignIn("ABC", neverStarted = true))),
        )

    @Test fun `official app warning, continued once`() {
        val env = ok.copy(officialAppInstalled = true)
        assertEquals(NewSensorStep.OfficialAppWarning, step(Start(env)))
        assertEquals(NewSensorStep.WaitingForTap, step(Start(env), Continue))
        // re-checking after coming back doesn't ask again
        assertEquals(NewSensorStep.WaitingForTap, step(Start(env), Continue, Start(env)))
    }

    @Test fun `notifications can be skipped`() =
        assertEquals(NewSensorStep.WaitingForTap, step(Start(ok.copy(notificationsAllowed = false)), Continue))

    @Test fun `tap outcomes map to their steps`() {
        val cases =
            mapOf(
                PairOutcome.OwnedByAnotherApp("ABC") to NewSensorStep.TakeoverFailed,
                PairOutcome.Ended("ABC") to NewSensorStep.NfcFailed(NfcFailure.SensorEnded),
                PairOutcome.Libre2Unsupported to NewSensorStep.NfcFailed(NfcFailure.NotLibre3),
                PairOutcome.Unsupported("lingo") to NewSensorStep.NfcFailed(NfcFailure.NotLibre3),
                PairOutcome.Failed("tag lost") to NewSensorStep.NfcFailed(NfcFailure.MovedTooSoon),
                PairOutcome.Paired("ABC", activated = false) to NewSensorStep.ConnectingBle("ABC", t0, activated = false),
            )
        cases.forEach { (outcome, expected) -> assertEquals(expected, step(Start(ok), TagFound, Tapped(outcome))) }
    }

    @Test fun `taps are taken only while waiting or after a failed read`() {
        assertEquals(NewSensorStep.Idle, step(TagFound))
        assertEquals(NewSensorStep.ReadingNfc, step(Start(ok), TagFound, TagFound))
        val failed = arrayOf(Start(ok), TagFound, Tapped(PairOutcome.Failed("moved")))
        assertEquals(NewSensorStep.ReadingNfc, step(*failed, TagFound))
        assertEquals(NewSensorStep.WaitingForTap, step(*failed, Retry))
        // an outcome without a read in progress is ignored
        assertEquals(NewSensorStep.WaitingForTap, step(Start(ok), Tapped(PairOutcome.Paired("ABC", true))))
    }

    @Test fun `takeover refused - stop the other app, then scan again`() =
        assertEquals(
            NewSensorStep.WaitingForTap,
            step(Start(ok), TagFound, Tapped(PairOutcome.OwnedByAnotherApp("ABC")), Retry),
        )

    @Test fun `new sensor warms up then is ready`() {
        val readyAt = t0.plus(Duration.ofMinutes(60))
        val warming = run(*paired, live("ABC", LinkStatus.WarmingUp(60), SensorState.WarmingUp(readyAt)))
        assertEquals(NewSensorStep.WarmingUp("ABC", readyAt), warming.step)
        assertEquals(NewSensorStep.Ready, run(live("ABC", LinkStatus.Live), from = warming).step)
    }

    @Test fun `warm-up without the hub's time counts from the link's minutes`() =
        assertEquals(
            NewSensorStep.WarmingUp("ABC", t0.plus(Duration.ofMinutes(42))),
            step(*paired, live("ABC", LinkStatus.WarmingUp(42), SensorState.None)),
        )

    @Test fun `takeover of a running sensor is ready at the first live data`() =
        assertEquals(NewSensorStep.Ready, step(*paired, live("ABC", LinkStatus.Live)))

    @Test fun `serial check - the previous sensor's live data never shows Ready for the new one`() {
        val s = step(*paired, live("OLD", LinkStatus.Live), live("OLD", LinkStatus.WaitingForBluetooth))
        assertEquals(NewSensorStep.ConnectingBle("ABC", t0, activated = true), s)
    }

    @Test fun `connecting phases keep connecting`() =
        assertEquals(
            NewSensorStep.ConnectingBle("ABC", t0, true),
            step(*paired, live("ABC", LinkStatus.Authorizing), live("ABC", LinkStatus.Reconnecting("x"))),
        )

    @Test fun `bluetooth problems after the tap`() {
        assertEquals(
            NewSensorStep.BleFailed("ABC", BleFailure.BluetoothOff),
            step(*paired, live("ABC", LinkStatus.WaitingForBluetooth)),
        )
        assertEquals(
            NewSensorStep.BleFailed("ABC", BleFailure.PermissionMissing),
            step(
                *paired,
                Source(
                    SensorStatus.BluetoothPermissionMissing,
                    SensorState.ActionNeeded(app.mauri.sukrara.core.model.SensorAction.GrantBluetooth),
                ),
            ),
        )
        assertEquals(
            NewSensorStep.TakeoverFailed,
            step(*paired, Source(SensorStatus.OwnedByAnotherApp, SensorState.None)),
        )
    }

    // SourceManager publishes an ended link as SensorStatus.Ended (no serial) and keeps the previous sensor's detail
    // after a restart: Ended counts only once this sensor was seen warming up
    @Test fun `an ended sensor after its warm-up was seen, never the previous sensor's stale Ended`() {
        val warming = live("ABC", LinkStatus.WarmingUp(40))
        val ended = Source(SensorStatus.Ended, SensorState.Ended)
        assertEquals(NewSensorStep.NfcFailed(NfcFailure.SensorEnded), step(*paired, warming, ended))
        assertEquals(NewSensorStep.ConnectingBle("ABC", t0, true), step(*paired, ended))
    }

    @Test fun `a bluetooth failure recovers by itself when the link comes back`() {
        val off = live("ABC", LinkStatus.WaitingForBluetooth)
        assertEquals(NewSensorStep.ConnectingBle("ABC", t0, activated = false), step(*paired, off, live("ABC", LinkStatus.Connecting)))
        assertEquals(NewSensorStep.Ready, step(*paired, off, live("ABC", LinkStatus.Live)))
        val noPermission = Source(SensorStatus.BluetoothPermissionMissing, SensorState.None)
        assertEquals(NewSensorStep.Ready, step(*paired, noPermission, live("ABC", LinkStatus.Live)))
    }

    @Test fun `the warm-up page keeps its end while the source repeats the status`() {
        val first = run(*paired, live("ABC", LinkStatus.WarmingUp(40)))
        val again = NewSensorMachine.reduce(first, live("ABC", LinkStatus.WarmingUp(39)), t0.plusSeconds(90))
        assertEquals(first.step, again.step)
    }

    @Test fun `the tap page re-checks the gates on return, and try again goes through them`() {
        assertEquals(NewSensorStep.NfcOff, step(Start(ok), Start(ok.copy(nfcOn = false))))
        assertEquals(NewSensorStep.NeedsLibreViewSignIn, step(Start(ok), Start(ok.copy(signedIn = false))))
        val failed = run(Start(ok), TagFound, Tapped(PairOutcome.Failed("x")))
        val signedOut = failed.copy(env = ok.copy(signedIn = false))
        assertEquals(NewSensorStep.NeedsLibreViewSignIn, NewSensorMachine.reduce(signedOut, Retry, t0).step)
    }

    @Test fun `bluetooth times out after two minutes and retry connects again`() {
        val s = run(*paired)
        val almost = NewSensorMachine.reduce(s, Tick(t0.plus(Duration.ofSeconds(119))), t0)
        assertEquals(s.step, almost.step)
        val timedOut = NewSensorMachine.reduce(s, Tick(t0.plus(NewSensorMachine.BLE_TIMEOUT)), t0)
        assertEquals(NewSensorStep.BleFailed("ABC", BleFailure.Timeout), timedOut.step)
        val later = t0.plus(Duration.ofMinutes(5))
        assertEquals(
            NewSensorStep.ConnectingBle("ABC", later, activated = false),
            NewSensorMachine.reduce(timedOut, Retry, later).step,
        )
    }

    @Test fun `cancel before the tap aborts, during the read is ignored, after the tap leaves it running`() {
        val beforeTap =
            listOf(
                arrayOf<NewSensorEvent>(),
                arrayOf<NewSensorEvent>(Start(ok.copy(coreAvailable = false))),
                arrayOf<NewSensorEvent>(Start(ok.copy(nfcAvailable = false))),
                arrayOf<NewSensorEvent>(Start(ok.copy(nfcOn = false))),
                arrayOf<NewSensorEvent>(Start(ok.copy(signedIn = false))),
                arrayOf<NewSensorEvent>(Start(ok.copy(officialAppInstalled = true))),
                arrayOf<NewSensorEvent>(Start(ok.copy(bluetoothPermission = false))),
                arrayOf<NewSensorEvent>(Start(ok.copy(bluetoothOn = false))),
                arrayOf<NewSensorEvent>(Start(ok.copy(notificationsAllowed = false))),
                arrayOf<NewSensorEvent>(Start(ok)),
                arrayOf<NewSensorEvent>(Start(ok), TagFound, Tapped(PairOutcome.Failed("x"))),
                arrayOf<NewSensorEvent>(Start(ok), TagFound, Tapped(PairOutcome.OwnedByAnotherApp("A"))),
            )
        beforeTap.forEach { assertEquals(NewSensorStep.Cancelled, step(*it, Cancel)) }
        assertEquals(NewSensorStep.ReadingNfc, step(Start(ok), TagFound, Cancel))
        assertEquals(NewSensorStep.ReadingNfc, step(Start(ok), TagFound, UseJuggluco))
        val afterTap =
            listOf(
                arrayOf(*paired),
                arrayOf(*paired, live("ABC", LinkStatus.WarmingUp(60))),
                arrayOf(*paired, live("ABC", LinkStatus.WaitingForBluetooth)),
            )
        afterTap.forEach { assertEquals(NewSensorStep.LeftRunning, step(*it, Cancel)) }
    }

    @Test fun `use Juggluco is an exit from the steps before the read`() {
        assertEquals(NewSensorStep.UsingJuggluco, step(Start(ok.copy(nfcAvailable = false)), UseJuggluco))
        assertEquals(NewSensorStep.UsingJuggluco, step(Start(ok.copy(coreAvailable = false)), UseJuggluco))
        assertEquals(
            NewSensorStep.UsingJuggluco,
            step(Start(ok), TagFound, Tapped(PairOutcome.Ended("A")), UseJuggluco),
        )
    }

    @Test fun `final steps ignore everything`() {
        listOf(
            arrayOf(*paired, live("ABC", LinkStatus.Live)),
            arrayOf<NewSensorEvent>(Cancel),
            arrayOf<NewSensorEvent>(UseJuggluco),
            arrayOf(*paired, Cancel),
        ).forEach { events ->
            val final = run(*events)
            assertEquals(final, run(Start(ok), TagFound, Retry, Cancel, Tick(t0.plusSeconds(999)), from = final))
        }
    }
}
