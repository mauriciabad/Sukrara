// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import app.mauri.sukrara.alerts.AlertGroup
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Role
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** CHANGES P3: no "no data" alarm before monitoring has started, and it arrives on time once it has. */
class NoAlarmBeforeMonitoringTest {
    private val rig = AlertRig()

    @Test fun freshInstallWithoutRoleNeverAlertsOrSchedules() = runTest {
        val runtime = rig.runtime()
        runtime.onState(GlucoseState.Initial)
        repeat(6) {
            rig.clock.advance(Duration.ofHours(1))
            runtime.tick()
        }
        assertTrue(rig.presenter.log.isEmpty())
        assertNull(rig.alarm.at)
    }

    @Test fun setupSkippedWithoutASensorNeverAlerts() = runTest {
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = null, monitoring = false))
        repeat(6) {
            rig.clock.advance(Duration.ofHours(1))
            runtime.tick()
            runtime.onState(rig.wearerState(latest = null, monitoring = false))
        }
        assertTrue(rig.presenter.log.isEmpty())
        assertNull(rig.alarm.at)
    }

    @Test fun firstReadingThenSilenceAlertsNoDataOnTime() = runTest {
        val runtime = rig.runtime()
        val first = rig.reading(120)
        runtime.onState(rig.wearerState(latest = first))
        val deadline = first.t.epochSecond + 20 * 60 // the default no-data threshold
        assertEquals(deadline, followAlarmsUntilNoData(runtime))
        assertTrue("no_data" in rig.published.firingKinds)
    }

    @Test fun monitoringStoppedClearsNoDataAndDisarms() = runTest {
        val runtime = rig.runtime()
        runtime.onState(rig.wearerState(latest = rig.reading(120)))
        rig.clock.advance(Duration.ofMinutes(25))
        runtime.tick()
        assertEquals("no_data", rig.presenter.slots[AlertGroup.NO_DATA])
        // the source reports no sensor (removed / monitoring stopped on purpose): the hub clears monitoringStartedAt
        runtime.onState(GlucoseState.Initial.copy(role = Role.Wearer, latest = null, monitoringStartedAt = null))
        assertNull(rig.presenter.slots[AlertGroup.NO_DATA])
        assertNull(rig.alarm.at)
    }

    /** Wakes at every alarm the runtime sets (as AlarmManager would) until no data posts; returns when it did. */
    private suspend fun followAlarmsUntilNoData(runtime: AlertRuntime): Long {
        repeat(20) {
            val at = requireNotNull(rig.alarm.at) { "no alarm armed before no data" }
            rig.clock.instant = java.time.Instant.ofEpochSecond(at)
            runtime.tick()
            if (rig.presenter.slots[AlertGroup.NO_DATA] == "no_data") return at
        }
        error("no data never posted")
    }
}
