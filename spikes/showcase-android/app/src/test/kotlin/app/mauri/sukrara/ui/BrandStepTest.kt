// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.ui.NewSensorFlow.PairStep
import app.mauri.sukrara.ui.NewSensorFlow.Stage
import app.mauri.sukrara.ui.NewSensorFlow.Step
import app.mauri.sukrara.ui.NewSensorFlow.Tapped
import app.mauri.sukrara.ui.NewSensorFlow.Trouble
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The New sensor flow's brand step and the untested confirmation (investigation 23, steps 2–5). */
class BrandStepTest {
    private val at = 1_000_000L

    /** The Libre 3 is first and preselected, and picking it leads to exactly the old NFC flow, with no confirmation. */
    @Test fun libre3IsTheDefault() {
        assertEquals(SensorProfiles.LIBRE_3, NewSensorFlow.DEFAULT_PROFILE)
        assertEquals(SensorProfiles.LIBRE_3, NewSensorFlow.choices.first())
        assertEquals(Stage.Waiting, NewSensorFlow.afterChoice(SensorProfiles.LIBRE_3))
        assertFalse(NewSensorFlow.needsConfirm(SensorProfiles.LIBRE_3))
        assertEquals(Step.SENSOR, Stage.Choose().step)
        assertEquals(NewSensorFlow.Continue.Go(Stage.Waiting), NewSensorFlow.onContinue(SensorProfiles.LIBRE_3))   // no confirmation
        assertEquals(Step.SCAN, Stage.Waiting.step)
    }

    @Test fun everyOtherBrandIsUntestedAndAsksFirst() {
        for (p in NewSensorFlow.choices.drop(1)) {
            assertFalse(p.id, p.verified)
            assertTrue(p.id, NewSensorFlow.needsConfirm(p))
            assertEquals(Stage.Setup(p.id), NewSensorFlow.afterChoice(p))
            // the confirmation comes on Continue (the brand step), not at pairing; accepting it leads to the setup
            assertEquals(NewSensorFlow.Continue.Confirm(p), NewSensorFlow.onContinue(p))
        }
    }

    @Test fun brandPairingWords() {
        val tap = Tapped("8GABCD", activated = false, at = at, profile = SensorProfiles.DEXCOM_G6)
        assertEquals(PairStep.CONNECTING, NewSensorFlow.pairStep("scanning for the sensor"))
        assertEquals(PairStep.SECURING, NewSensorFlow.pairStep("authenticating"))
        assertEquals(PairStep.SECURING, NewSensorFlow.pairStep("waiting for the Dexcom app"))
        assertEquals(PairStep.SECURING, NewSensorFlow.pairStep("pairing — enter the PIN from the sensor's cap"))
        assertEquals(PairStep.SECURING, NewSensorFlow.pairStep("exchanging keys"))
        assertEquals(PairStep.STARTING, NewSensorFlow.pairStep("reading"))
        assertEquals(Stage.Problem(Trouble.PAIR_FAILED, "8GABCD", "wrong transmitter id"),
            NewSensorFlow.afterTap(tap, "direct: failed", null, null, at + 5, "wrong transmitter id"))
        assertEquals(Step.PAIR, Stage.Problem(Trouble.PAIR_FAILED).step)
        // a G7 read along with the Dexcom app has no serial: the screen names the family
        val g7 = Tapped("", activated = false, at = at, profile = SensorProfiles.DEXCOM_G7, label = "Dexcom G7 / ONE+")
        assertEquals(Stage.WarmingUp("Dexcom G7 / ONE+", at, at + 30 * 60), NewSensorFlow.afterTap(g7, "direct: warming up", null, null, at + 60))
    }

    /** The Libre 3's flow is unchanged: a "failed" source status still reads as connecting, as before step 2. */
    @Test fun libre3IgnoresTheBrandFailureWord() {
        val tap = Tapped("3MH0001", activated = false, at = at)
        assertEquals(Stage.Pairing("3MH0001", false, PairStep.CONNECTING), NewSensorFlow.afterTap(tap, "direct: failed", null, null, at + 5, "x"))
    }

    /** "Continue anyway (10)" … "(1)", then enabled; rounded up, so it never shows "(0)". */
    @Test fun countdown() {
        assertEquals(10, UntestedConfirm.WAIT_S)
        assertEquals(10, UntestedConfirm.secondsLeft(0))
        assertEquals(10, UntestedConfirm.secondsLeft(1))
        assertEquals(9, UntestedConfirm.secondsLeft(1_000))
        assertEquals(5, UntestedConfirm.secondsLeft(5_000))   // 0.11.27's whole wait: still disabled
        assertFalse(UntestedConfirm.enabled(5_000))
        assertEquals(1, UntestedConfirm.secondsLeft(9_999))
        assertEquals(0, UntestedConfirm.secondsLeft(10_000))
        assertEquals(0, UntestedConfirm.secondsLeft(60_000))
        assertFalse(UntestedConfirm.enabled(9_999))
        assertTrue(UntestedConfirm.enabled(10_000))
        assertEquals(10, UntestedConfirm.secondsLeft(-100))   // a clock step back doesn't shorten it
    }

    @Test fun previews() {
        assertEquals(Stage.Choose(), NewSensorFlow.previewStage("choose", at))
        assertEquals(Stage.Choose(confirm = "dexcom-g7"), NewSensorFlow.previewStage("untested", at))
        assertEquals(Stage.Setup("aidex-x"), NewSensorFlow.previewStage("setup-aidex-x", at))
        assertEquals(null, NewSensorFlow.previewStage("setup-libre3", at))
    }
}
