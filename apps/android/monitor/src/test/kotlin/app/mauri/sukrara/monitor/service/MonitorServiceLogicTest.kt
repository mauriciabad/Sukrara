// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.service

import android.bluetooth.BluetoothAdapter
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.StopReason
import app.mauri.sukrara.core.testing.FakeClock
import app.mauri.sukrara.core.testing.InMemoryReadingsRepository
import app.mauri.sukrara.core.testing.InMemoryStateStore
import app.mauri.sukrara.core.testing.TestDispatchers
import app.mauri.sukrara.monitor.hub.GlucoseHubImpl
import app.mauri.sukrara.monitor.hub.MonitorRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** MonitorStarter, HealthCheck, FreshnessTicker and the Bluetooth state mapping. */
@OptIn(ExperimentalCoroutinesApi::class)
class MonitorServiceLogicTest {
    private val role = MutableStateFlow<Role?>(Role.Wearer)
    private var allowed = true
    private var launches = 0
    private var somethingToMonitor = true
    private val notice =
        object : StoppedNotice {
            var shown = 0
            var cancelled = 0

            override fun show() {
                shown++
            }

            override fun cancel() {
                cancelled++
            }
        }
    private val alarms =
        object : Alarms {
            val health = mutableListOf<Duration>()
            val steps = mutableListOf<Instant?>()

            override fun scheduleHealthCheck(after: Duration) {
                health += after
            }

            override fun scheduleFreshnessStep(at: Instant?) {
                steps += at
            }
        }

    private fun TestScope.setUp(): Pair<GlucoseHubImpl, MonitorStarter> {
        val dispatchers = TestDispatchers(StandardTestDispatcher(testScheduler))
        val hub =
            GlucoseHubImpl(
                CoroutineScope(backgroundScope.coroutineContext + dispatchers.dispatcher), dispatchers, FakeClock(),
                InMemoryReadingsRepository(), InMemoryStateStore(MonitorRecord()), role,
            )
        hub.start()
        runCurrent()
        val starter =
            MonitorStarter(hub, { somethingToMonitor }, {
                launches++
                allowed
            }, notice)
        return hub to starter
    }

    @Test
    fun `a refused start shows Stopped and the resume notice, never silently`() =
        runTest {
            val (hub, starter) = setUp()
            allowed = false
            assertEquals(StartResult.Denied, starter.start(StartReason.Boot))
            assertEquals(ServiceState.Stopped(StopReason.StartDenied), hub.state.value.service)
            assertEquals(1, notice.shown)

            allowed = true
            assertEquals(StartResult.Started, starter.start(StartReason.Resume))
            assertEquals(1, notice.cancelled)
        }

    @Test
    fun `nothing to monitor starts nothing and shows no notice`() =
        runTest {
            val (_, starter) = setUp()
            somethingToMonitor = false
            assertEquals(StartResult.NotNeeded, starter.start(StartReason.HealthCheck))
            role.value = null
            somethingToMonitor = true
            runCurrent()
            assertEquals(StartResult.NotNeeded, starter.start(StartReason.Boot))
            assertEquals(0, launches)
            assertEquals(0, notice.shown)
        }

    @Test
    fun `the health check restarts the service and schedules the next check every 15 minutes`() =
        runTest {
            val (_, starter) = setUp()
            val check = HealthCheck(alarms, starter)
            assertEquals(StartResult.Started, check.onFire())
            assertEquals(StartResult.Started, check.onFire())
            assertEquals(2, launches)
            assertEquals(listOf(Duration.ofMinutes(15), Duration.ofMinutes(15)), alarms.health)
        }

    @Test
    fun `the health check reschedules even when there is nothing to monitor`() =
        runTest {
            val (_, starter) = setUp()
            somethingToMonitor = false
            assertEquals(StartResult.NotNeeded, HealthCheck(alarms, starter).onFire())
            assertEquals(1, alarms.health.size)
        }

    @Test
    fun `the freshness ticker wakes only at the renderer's freshness steps`() {
        val t = Instant.parse("2026-01-15T12:00:00Z")
        assertEquals(t.plusSeconds(91), FreshnessTicker.nextStep(t, t.plusSeconds(30)))
        assertEquals(t.plusSeconds(301), FreshnessTicker.nextStep(t, t.plusSeconds(120)))
        assertEquals(t.plusSeconds(1201), FreshnessTicker.nextStep(t, t.plusSeconds(400)))
        assertEquals(t.plusSeconds(10801), FreshnessTicker.nextStep(t, t.plusSeconds(1300)))
        assertNull(FreshnessTicker.nextStep(t, t.plusSeconds(20000)))
        assertNull(FreshnessTicker.nextStep(null, t))

        val ticker = FreshnessTicker(alarms)
        ticker.plan(t, t.plusSeconds(30))
        ticker.plan(t, t.plusSeconds(60))
        assertEquals("no re-arm while nothing changes", listOf(t.plusSeconds(91)), alarms.steps)
        ticker.plan(t, t.plusSeconds(20000))
        assertEquals(listOf(t.plusSeconds(91), null), alarms.steps)
    }

    @Test
    fun `bluetooth adapter states map to on and off`() {
        assertEquals(true, bluetoothPower(BluetoothAdapter.STATE_ON))
        assertEquals(false, bluetoothPower(BluetoothAdapter.STATE_OFF))
        assertEquals(false, bluetoothPower(BluetoothAdapter.STATE_TURNING_OFF))
        assertNull(bluetoothPower(BluetoothAdapter.STATE_TURNING_ON))
    }
}
