// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.LinkState
import app.mauri.sukrara.core.model.NotMonitoringReason
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.ServiceState
import app.mauri.sukrara.core.model.StopReason
import app.mauri.sukrara.feature.home.Samples.NOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class NoDataReasonTest {
    private fun reason(state: GlucoseState) = NoDataReason.of(state, homeLook(state, NOW))

    @Test fun `a fresh value has no reason`() = assertNull(reason(Samples.wearer()))

    @Test fun `a stale value under 20 min is still shown (struck), no reason`() = assertNull(reason(Samples.wearer(ageMin = 6)))

    @Test fun `no role yet asks to set up`() = assertEquals(NoDataReason.SetupIncomplete, reason(GlucoseState.Initial))

    @Test fun `before the first reading ever it is not monitoring, never a no-readings alarm state`() {
        val state = Samples.noSensorYet()
        assertEquals(NotMonitoringReason.NoSensorYet, state.notMonitoring) // the hub arms no data alarm in this state
        assertEquals(NoDataReason.NoSensorYet(follower = false), reason(state))
        assertEquals(HomeAction.PairSensor, reason(state)?.action)
    }

    @Test fun `a follower that never got a reading is asked to join`() {
        val r = reason(Samples.noSensorYet(Role.Follower))
        assertEquals(NoDataReason.NoSensorYet(follower = true), r)
        assertEquals(HomeAction.OpenSetup, r?.action)
    }

    @Test fun `25 min without readings names the last one`() {
        val state = Samples.wearer(ageMin = 25)
        assertEquals(NoDataReason.NoReadings(state.latest!!.t), reason(state))
    }

    @Test fun `follower silence names when the wearer was last heard`() {
        val heard = NOW.minus(Duration.ofMinutes(30))
        val state = Samples.wearer(ageMin = 30).copy(role = Role.Follower, link = LinkState("Ana", heard))
        assertEquals(NoDataReason.WearerSilent(heard), reason(state))
    }

    @Test fun `bluetooth off on a wearer with no value asks to turn it on`() {
        val r = reason(Samples.wearer(ageMin = 25, sensor = SensorState.BluetoothOff))
        assertEquals(NoDataReason.BluetoothOff, r)
        assertEquals(HomeAction.EnableBluetooth, r?.action)
    }

    @Test fun `a fresh value wins over bluetooth just turned off`() = assertNull(reason(Samples.wearer(sensor = SensorState.BluetoothOff)))

    @Test fun `ended and warming up replace any number`() {
        assertEquals(NoDataReason.SensorEnded, reason(Samples.wearer(sensor = SensorState.Ended)))
        val ready = NOW.plus(Duration.ofMinutes(40))
        assertEquals(NoDataReason.WarmingUp(ready), reason(Samples.wearer(sensor = SensorState.WarmingUp(ready))))
    }

    @Test fun `native core missing has no action but says why`() {
        val r = reason(Samples.wearer(sensor = SensorState.CoreMissing))
        assertEquals(NoDataReason.CoreMissing, r)
        assertNull(r?.action)
    }

    @Test fun `monitoring stopped by Android offers resume`() {
        val state = Samples.wearer(ageMin = 25).copy(service = ServiceState.Stopped(StopReason.StartDenied))
        assertEquals(NoDataReason.MonitoringStopped, reason(state))
    }

    @Test fun `a revoked follower is told so`() {
        val state = Samples.wearer().copy(role = Role.Follower, link = LinkState("Ana", NOW, revoked = true))
        assertEquals(NoDataReason.LinkRevoked, reason(state))
    }
}
