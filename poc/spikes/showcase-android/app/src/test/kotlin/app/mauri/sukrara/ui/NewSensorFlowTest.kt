// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.ui.NewSensorFlow.PairStep
import app.mauri.sukrara.ui.NewSensorFlow.Stage
import app.mauri.sukrara.ui.NewSensorFlow.Step
import app.mauri.sukrara.ui.NewSensorFlow.Tapped
import app.mauri.sukrara.ui.NewSensorFlow.Trouble
import org.junit.Assert.assertEquals
import org.junit.Test

/** The New sensor screen's steps after a tap, from the direct source's status words, and home's "Pair new sensor". */
class NewSensorFlowTest {
    private val at = 1_000_000L
    private val started = Tapped("3MH0001", activated = true, at = at)
    private val takenOver = Tapped("3MH0001", activated = false, at = at)
    private val info = SensorInfo("3MH0001", at, at + 15 * 86_400L, at + 3_600)

    @Test fun bluetoothSteps() {
        assertEquals(Stage.Pairing("3MH0001", true, PairStep.CONNECTING), NewSensorFlow.afterTap(started, "direct: connecting to 3MH0001", null, null, at + 5))
        assertEquals(PairStep.CONNECTING, NewSensorFlow.pairStep("discovering services"))
        assertEquals(PairStep.CONNECTING, NewSensorFlow.pairStep("reconnecting"))
        assertEquals(PairStep.SECURING, NewSensorFlow.pairStep("pairing with the sensor"))
        assertEquals(PairStep.SECURING, NewSensorFlow.pairStep("authorizing"))
        assertEquals(PairStep.STARTING, NewSensorFlow.pairStep("authorized, subscribing"))
    }

    @Test fun slowPairingGetsTheHint() {
        val s = NewSensorFlow.afterTap(takenOver, "direct: reconnecting", null, null, at + NewSensorFlow.SLOW_S + 1) as Stage.Pairing
        assertEquals(true, s.slow)
        assertEquals(Step.PAIR, s.step)
    }

    @Test fun startedSensorWarmsUpUntilItsFirstReading() {
        // an old sensor's last reading just before the tap is not the new sensor's first
        assertEquals(Stage.WarmingUp("3MH0001", at, at + 3_600), NewSensorFlow.afterTap(started, "direct: live", at - 30, info, at + 60))
        assertEquals(Stage.WarmingUp("3MH0001", at, at + 3_600),
            NewSensorFlow.afterTap(started, "direct: warming up — readings in 40 min", null, info, at + 1_200))
        assertEquals(Stage.Ready("3MH0001", at + 15 * 86_400L), NewSensorFlow.afterTap(started, "direct: live", at + 3_660, info, at + 3_700))
    }

    @Test fun warmUpWithoutTheSensorReportYet() {
        assertEquals(Stage.WarmingUp("3MH0001", at, at + 3_600), NewSensorFlow.afterTap(started, "direct: live", null, null, at + 60))
    }

    @Test fun takenOverSensorIsReadyWithItsCurrentReading() {
        assertEquals(Stage.Pairing("3MH0001", false, PairStep.STARTING), NewSensorFlow.afterTap(takenOver, "direct: live", null, null, at + 20))
        assertEquals(Stage.Ready("3MH0001", null), NewSensorFlow.afterTap(takenOver, "direct: live", at - 40, null, at + 20))
        // a reading from before the tap's minute is the old app's, not this pairing's
        assertEquals(PairStep.STARTING, (NewSensorFlow.afterTap(takenOver, "direct: live", at - 600, null, at + 20) as Stage.Pairing).pair)
    }

    @Test fun troubles() {
        assertEquals(Stage.Problem(Trouble.ENDED, "3MH0001"), NewSensorFlow.afterTap(takenOver, "direct: sensor ended", null, null, at))
        assertEquals(Stage.Problem(Trouble.BLUETOOTH_OFF, "3MH0001"), NewSensorFlow.afterTap(takenOver, "direct: waiting for Bluetooth", null, null, at))
        assertEquals(Step.PAIR, Stage.Problem(Trouble.BLUETOOTH_OFF).step)
        assertEquals(Step.SCAN, Stage.Problem(Trouble.FAILED).step)
    }

    @Test fun homeButton() {
        val now = at + 5 * 86_400L
        assertEquals(NewSensorDue.FIRST, NewSensorDue.of("direct: no sensor", null, now))
        assertEquals(NewSensorDue.NOW, NewSensorDue.of("direct: sensor ended", info, now))
        assertEquals(NewSensorDue.NO, NewSensorDue.of("direct: live", info, now))
        assertEquals(NewSensorDue.NO, NewSensorDue.of("juggluco", null, now))
        assertEquals(NewSensorDue.SOON, NewSensorDue.of("direct: live", info, info.endEpoch - NewSensorDue.SOON_S))
        assertEquals(NewSensorDue.NOW, NewSensorDue.of("juggluco", info, info.endEpoch))
    }
}
