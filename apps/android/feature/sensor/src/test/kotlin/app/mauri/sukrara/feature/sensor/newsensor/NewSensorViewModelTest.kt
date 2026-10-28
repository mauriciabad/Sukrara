// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import android.nfc.Tag
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.pairing.PairOutcome
import app.mauri.sukrara.sensor.source.SensorStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class NewSensorViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val t0: Instant = Instant.parse("2026-10-27T08:00:00Z")

    /** Follows the test scheduler, so the 1 s tick loop sees virtual time pass (and the 2 min timeout ends it). */
    private val clock =
        object : Clock {
            override fun now(): Instant = t0.plusMillis(dispatcher.scheduler.currentTime)

            override val zone: ZoneId = ZoneOffset.UTC
        }

    private class FakePort : NewSensorPort {
        var env = SensorEnv(true, true, true, true, false, true, true, true)
        var retries = 0
        var juggluco = 0
        var libre3 = 0

        override suspend fun env() = env

        override suspend fun pair(tag: Tag): PairOutcome = error("tests pass the read directly")

        override val detail = MutableStateFlow<SensorStatus>(SensorStatus.NoSensor)
        override val sensor = MutableStateFlow<SensorState>(SensorState.None)

        override fun retryLink() {
            retries++
        }

        override suspend fun useJuggluco() {
            juggluco++
        }

        override suspend fun onPaired() {
            libre3++
        }
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.vm(port: FakePort, appScope: TestScope = this) =
        NewSensorViewModel(port, clock, appScope.backgroundScope).also {
            it.start()
            runCurrent()
        }

    @Test fun `a successful tap connects, then the paired sensor's live data is Ready, one haptic each`() =
        runTest(dispatcher) {
            val port = FakePort()
            val vm = vm(port)
            val effects = mutableListOf<NewSensorEffect>()
            backgroundScope.launch { vm.effects.toList(effects) }
            vm.onTagRead { PairOutcome.Paired("ABC", activated = false) }
            runCurrent()
            assertTrue(vm.state.value.step is NewSensorStep.ConnectingBle)
            port.detail.value = SensorStatus.Libre3("ABC", LinkStatus.Live)
            port.sensor.value = SensorState.Active(null)
            runCurrent()
            assertEquals(NewSensorStep.Ready, vm.state.value.step)
            assertEquals(listOf(NewSensorEffect.TapSucceeded, NewSensorEffect.Ready), effects)
        }

    @Test fun `a paired sensor becomes the source and monitoring starts, a failed tap changes nothing`() =
        runTest(dispatcher) {
            val port = FakePort()
            val vm = vm(port)
            vm.onTagRead { PairOutcome.Failed("tag lost") }
            runCurrent()
            assertEquals(0, port.libre3)
            vm.onTagRead { PairOutcome.Paired("ABC", activated = true) }
            runCurrent()
            assertEquals(1, port.libre3)
        }

    @Test fun `a read that throws ends the tap page as a failed tap (it has no other way out)`() =
        runTest(dispatcher) {
            val vm = vm(FakePort())
            vm.onTagRead { throw java.io.IOException("datastore") }
            runCurrent()
            assertEquals(NewSensorStep.NfcFailed(NfcFailure.MovedTooSoon), vm.state.value.step)
        }

    @Test fun `a second tag during a read is ignored`() =
        runTest(dispatcher) {
            val vm = vm(FakePort())
            val answer = CompletableDeferred<PairOutcome>()
            var reads = 0
            vm.onTagRead {
                reads++
                answer.await()
            }
            vm.onTagRead {
                reads++
                answer.await()
            }
            runCurrent()
            assertEquals(1, reads)
            assertEquals(NewSensorStep.ReadingNfc, vm.state.value.step)
        }

    @Test fun `closing the screen mid-tap doesn't cancel the read and its outcome still lands`() =
        runTest(dispatcher) {
            val vm = vm(FakePort())
            val answer = CompletableDeferred<PairOutcome>()
            vm.onTagRead { answer.await() }
            runCurrent()
            vm.onCancel() // ignored while reading
            assertEquals(NewSensorStep.ReadingNfc, vm.state.value.step)
            answer.complete(PairOutcome.Paired("ABC", activated = true))
            runCurrent()
            assertTrue(vm.state.value.step is NewSensorStep.ConnectingBle)
        }

    @Test fun `bluetooth times out under virtual time and retry reconnects the link`() =
        runTest(dispatcher) {
            val port = FakePort()
            val vm = vm(port)
            vm.onTagRead { PairOutcome.Paired("ABC", activated = false) }
            runCurrent()
            advanceTimeBy(121_000)
            runCurrent()
            assertEquals(NewSensorStep.BleFailed("ABC", BleFailure.Timeout), vm.state.value.step)
            vm.onRetry()
            runCurrent()
            assertEquals(1, port.retries)
            assertTrue(vm.state.value.step is NewSensorStep.ConnectingBle)
        }

    @Test fun `use Juggluco switches the source`() =
        runTest(dispatcher) {
            val port = FakePort().apply { env = env.copy(nfcAvailable = false) }
            val vm = vm(port)
            assertEquals(NewSensorStep.NoNfc, vm.state.value.step)
            vm.onUseJuggluco()
            runCurrent()
            assertEquals(1, port.juggluco)
            assertEquals(NewSensorStep.UsingJuggluco, vm.state.value.step)
        }

    @Test fun `coming back after signing in re-checks the gates`() =
        runTest(dispatcher) {
            val port = FakePort().apply { env = env.copy(signedIn = false) }
            val vm = vm(port)
            assertEquals(NewSensorStep.NeedsLibreViewSignIn, vm.state.value.step)
            port.env = port.env.copy(signedIn = true)
            vm.start()
            runCurrent()
            assertEquals(NewSensorStep.WaitingForTap, vm.state.value.step)
        }
}
