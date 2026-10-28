// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.hub

import app.mauri.sukrara.core.model.NotMonitoringReason
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.ReadingSource
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.model.SourceStatus
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.core.testing.TestDispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class GlucoseHubTest {
    private val clock = FakeClock()
    private val repo = InMemoryReadingsRepository()
    private val record = InMemoryStateStore(MonitorRecord())
    private val role = MutableStateFlow<Role?>(Role.Wearer)

    private class FakeSource : ReadingSource {
        val emitted = MutableSharedFlow<List<Reading>>()
        override val readings = emitted
        override val sensor = MutableStateFlow<SensorState>(SensorState.Active(null))
        override val status = MutableStateFlow<SourceStatus>(SourceStatus.Connecting)
        var bluetoothOn = 0
        var bluetoothOff = 0

        override fun onBluetoothOn() {
            bluetoothOn++
        }

        override fun onBluetoothOff() {
            bluetoothOff++
        }
    }

    private fun reading(
        mgdl: Int,
        agoMin: Long = 0,
    ) = Reading(clock.now() - Duration.ofMinutes(agoMin), mgdl, 0.5, SourceId.Libre3)

    private fun TestScope.hub(): GlucoseHubImpl {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        return GlucoseHubImpl(
            CoroutineScope(backgroundScope.coroutineContext + dispatchers.dispatcher),
            dispatchers,
            clock,
            repo,
            record,
            role,
        ).also { it.start() }
    }

    @Test
    fun `not monitoring until the first reading, then monitoring from then on`() =
        runTest {
            val hub = hub()
            val source = FakeSource()
            runCurrent()
            assertEquals(NotMonitoringReason.NoSensorYet, hub.state.value.notMonitoring)
            assertNull(hub.state.value.monitoringStartedAt)

            backgroundScope.launch { hub.attach(source) }
            runCurrent()
            source.emitted.emit(listOf(reading(120)))
            runCurrent()

            assertNull(hub.state.value.notMonitoring)
            assertEquals(clock.now(), hub.state.value.monitoringStartedAt)
            assertEquals(
                120,
                hub.state.value.latest
                    ?.mgdl,
            )
            assertEquals(clock.now().toEpochMilli(), record.data.first().startedAtEpochMs)
        }

    @Test
    fun `no role means setup incomplete`() =
        runTest {
            role.value = null
            val hub = hub()
            runCurrent()
            assertEquals(NotMonitoringReason.SetupIncomplete, hub.state.value.notMonitoring)
        }

    @Test
    fun `readings are persisted and loaded back from the store`() =
        runTest {
            repo.upsert(listOf(reading(100, agoMin = 3)))
            val hub = hub()
            runCurrent()
            assertEquals(
                100,
                hub.state.value.latest
                    ?.mgdl,
            )

            val source = FakeSource()
            backgroundScope.launch { hub.attach(source) }
            runCurrent()
            source.emitted.emit(listOf(reading(110)))
            runCurrent()
            assertEquals(listOf(100, 110), repo.window(Instant.EPOCH).first().map { it.mgdl })
            assertEquals(
                listOf(100, 110),
                hub.state.value.recent
                    .map { it.mgdl },
            )
        }

    @Test
    fun `readings are ignored while no source is attached`() =
        runTest {
            val hub = hub()
            runCurrent()
            hub.ingest(listOf(reading(150)), SourceId.Libre3)
            runCurrent()
            assertNull(hub.state.value.latest)
            assertNull(hub.state.value.monitoringStartedAt)
        }

    @Test
    fun `attaching a second source detaches the first`() =
        runTest {
            val hub = hub()
            val first = FakeSource()
            val second = FakeSource()
            backgroundScope.launch { hub.attach(first) }
            runCurrent()
            backgroundScope.launch { hub.attach(second) }
            runCurrent()
            first.emitted.emit(listOf(reading(90)))
            runCurrent()
            assertNull("the detached source no longer feeds the hub", hub.state.value.latest)
            second.emitted.emit(listOf(reading(95)))
            runCurrent()
            assertEquals(
                95,
                hub.state.value.latest
                    ?.mgdl,
            )
        }

    @Test
    fun `LO and HI readings are stored as the flags, malformed and far-future ones dropped`() =
        runTest {
            val hub = hub()
            val source = FakeSource()
            backgroundScope.launch { hub.attach(source) }
            runCurrent()
            val future = Reading(clock.now() + Duration.ofHours(1), 130, null, SourceId.Libre3)
            source.emitted.emit(
                listOf(reading(0, agoMin = 5), reading(1200, agoMin = 4), reading(38, agoMin = 3), reading(450, agoMin = 2), future),
            )
            // Juggluco, the relay and Nearby pass raw numbers: the hub stores every LO / HI the same way, rate dropped
            hub.ingest(listOf(reading(12, agoMin = 1), reading(401, agoMin = 0)), SourceId.Relay)
            runCurrent()
            assertEquals(
                listOf(39 to null, 401 to null, 39 to null, 401 to null),
                hub.state.value.recent
                    .map { it.mgdl to it.rateMgdlPerMin },
            )
        }

    @Test
    fun `a source reporting no sensor never disarms monitoring, only an explicit reset does`() =
        runTest {
            val hub = hub()
            val source = FakeSource()
            backgroundScope.launch { hub.attach(source) }
            runCurrent()
            source.emitted.emit(listOf(reading(120)))
            runCurrent()
            source.sensor.value = SensorState.None
            runCurrent()
            assertNotNull(hub.state.value.monitoringStartedAt)

            hub.resetMonitoring()
            runCurrent()
            assertEquals(NotMonitoringReason.NoSensorYet, hub.state.value.notMonitoring)
        }

    @Test
    fun `bluetooth off shows on a wearer and the source reconnects when it is back`() =
        runTest {
            val hub = hub()
            val source = FakeSource()
            backgroundScope.launch { hub.attach(source) }
            runCurrent()
            hub.onBluetoothChanged(false)
            runCurrent()
            assertEquals(SensorState.BluetoothOff, hub.state.value.sensor)
            assertEquals(1, source.bluetoothOff)

            hub.onBluetoothChanged(true)
            runCurrent()
            assertEquals(SensorState.Active(null), hub.state.value.sensor)
            assertEquals(1, source.bluetoothOn)
        }

    @Test
    fun `the recent window holds the last 24 hours`() =
        runTest {
            repo.upsert(listOf(reading(80, agoMin = 25 * 60), reading(90, agoMin = 23 * 60)))
            val hub = hub()
            runCurrent()
            assertEquals(
                listOf(90),
                hub.state.value.recent
                    .map { it.mgdl },
            )
            assertTrue(
                hub.state.value.latest
                    ?.mgdl == 90,
            )
        }
}
