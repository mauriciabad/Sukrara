// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.core.model.GlucoseState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The alarm receiver's path ([app.mauri.sukrara.monitor.AlertCheckReceiver] calls only `tick()`): with the service dead
 * every wake-up meets a fresh process, no hub, only the saved record, and still posts no data on time, exactly once.
 */
class AlertCheckReceiverTest {
    private val rig = AlertRig()

    @Test fun serviceDeadNoDataPostsOnceOnTime() = runTest {
        val first = rig.reading(120)
        rig.runtime().onState(rig.wearerState(latest = first))
        var headless = rig.runtime()
        while (rig.presenter.log.isEmpty()) {
            rig.clock.instant = Instant.ofEpochSecond(requireNotNull(rig.alarm.at))
            headless = rig.runtime() // a fresh process per wake-up
            headless.tick()
        }
        assertEquals(listOf("show no_data"), rig.presenter.log)
        assertEquals(first.t.epochSecond + 20 * 60, rig.clock.now().epochSecond)
        headless.tick() // a second wake-up (boot, time change) doesn't post again
        assertEquals(listOf("show no_data"), rig.presenter.log)
        rig.runtime().tick() // a new process shows it again, silently: it never rings twice
        assertEquals(listOf("show no_data", "resume no_data"), rig.presenter.log)
    }

    @Test fun theServiceAndTheReceiverShareOneRuntimeSoNothingPostsTwice() = runTest {
        val runtime = rig.runtime()
        val low = rig.reading(60)
        runtime.onState(rig.wearerState(latest = low))
        runtime.tick() // the receiver in the same process
        runtime.onState(rig.wearerState(latest = low)) // the hub emits the same reading again
        assertEquals(listOf("show low"), rig.presenter.log)
    }

    @Test fun neverStartedNeverAlertsFromTheReceiver() = runTest {
        rig.clock.instant = rig.clock.now().plusSeconds(6 * 3600)
        rig.runtime().tick()
        assertTrue(rig.presenter.log.isEmpty())
        assertNull(rig.alarm.at)
    }

    @Test fun startFreshClearsEverythingAndNothingComesBackAfterARestart() = runTest {
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = rig.reading(60)))
        assertEquals("low", rig.presenter.slots[AlertGroup.LEVEL])
        runtime.reset()
        assertTrue(rig.presenter.slots.isEmpty())
        assertNull(rig.alarm.at)
        assertEquals(AlertRecord(), rig.record.data.first())
        rig.clock.instant = rig.clock.now().plusSeconds(3600)
        val next = rig.runtime() // a restart after Start fresh, before setup chose a role
        next.onState(GlucoseState.Initial)
        next.tick()
        assertTrue(rig.presenter.slots.isEmpty())
        assertNull(rig.alarm.at)
    }
}
