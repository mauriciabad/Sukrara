// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sensor.newsensor

import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.LinkStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

class NewSensorProgressTest {
    private val now = Steps.NOW

    @Test
    fun `every step that stays on screen has a stage on the bar, in flow order`() {
        Steps.all.forEach { (name, step) ->
            val closes = step == NewSensorStep.LeftRunning || step == NewSensorStep.Cancelled || step == NewSensorStep.UsingJuggluco
            if (closes) assertNull(name, step.stage) else assertTrue(name, step.stage != null)
        }
        assertEquals(FlowStage.Sensor, NewSensorStep.ChooseSensor(SensorProfile.Libre3).stage)
        assertEquals(FlowStage.Scan, NewSensorStep.WaitingForTap.stage)
        assertEquals(FlowStage.Scan, NewSensorStep.NfcFailed(NfcFailure.NotASensor).stage)
        assertEquals(FlowStage.Pair, NewSensorStep.ConnectingBle("s", now, activated = true).stage)
        assertEquals(FlowStage.Readings, NewSensorStep.WarmingUp("s", now).stage)
        assertEquals(FlowStage.Readings, NewSensorStep.Ready.stage)
    }

    @Test
    fun `the second stage is Set up for a family found by a scan, Scan for the Libre 3 tap`() {
        assertFalse(NewSensorStep.WaitingForTap.setsUp(SensorProfile.Libre3))
        assertTrue(NewSensorStep.SensorSetupStep(SensorProfile.DexcomG6).setsUp(null))
        assertTrue(NewSensorStep.NeedsBluetoothPermission.setsUp(SensorProfile.DexcomG6))
        assertTrue(NewSensorStep.ChooseSensor(SensorProfile.DexcomG7).setsUp(null))
        assertFalse(NewSensorStep.ChooseSensor(SensorProfile.Libre3).setsUp(null))
    }

    @Test
    fun `pairing steps tick off as the link moves on`() {
        assertEquals(PairProgress.Connecting, pairProgress(null))
        assertEquals(PairProgress.Connecting, pairProgress(LinkStatus.Connecting))
        assertEquals(PairProgress.Connecting, pairProgress(LinkStatus.DiscoveringServices))
        assertEquals(PairProgress.Securing, pairProgress(LinkStatus.Pairing))
        assertEquals(PairProgress.Securing, pairProgress(LinkStatus.Authorizing))
        assertEquals(PairProgress.Securing, pairProgress(LinkStatus.EnterPin))
        assertEquals(PairProgress.Starting, pairProgress(LinkStatus.Authorized))
    }

    @Test
    fun `taking longer shows from 45 s after the tap`() {
        val step = NewSensorStep.ConnectingBle("s", now, activated = false)
        assertFalse(step.isSlow(now.plusSeconds(44)))
        assertTrue(step.isSlow(now.plusSeconds(45)))
        assertFalse(NewSensorStep.WaitingForTap.isSlow(now.plusSeconds(600)))
    }

    @Test
    fun `the warm-up ring fills over the hour`() {
        assertEquals(0f, warmUpDone(now.plus(Duration.ofMinutes(60)), now), 0.001f)
        assertEquals(0.5f, warmUpDone(now.plus(Duration.ofMinutes(30)), now), 0.001f)
        assertEquals(1f, warmUpDone(now.minusSeconds(5), now), 0.001f)
        assertEquals(0f, warmUpDone(now.plus(Duration.ofMinutes(90)), now), 0.001f)
    }

    @Test
    fun `the screen stays on until the warm-up or the end`() {
        assertTrue(NewSensorStep.WaitingForTap.keepsScreenOn)
        assertTrue(NewSensorStep.ReadingNfc.keepsScreenOn)
        assertTrue(NewSensorStep.ConnectingBle("s", now, activated = true).keepsScreenOn)
        assertFalse(NewSensorStep.WarmingUp("s", now).keepsScreenOn)
        assertFalse(NewSensorStep.Ready.keepsScreenOn)
        assertFalse(NewSensorStep.Cancelled.keepsScreenOn)
    }
}
