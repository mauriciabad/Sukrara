// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockCountdownTest {
    @Test fun durationIsTheSettingRaisedForAccessibility() {
        assertEquals(20_000L, LockCountdown.duration(20, 0, touchExploration = false))
        assertEquals(45_000L, LockCountdown.duration(20, 45_000, touchExploration = false))
        assertEquals(60_000L, LockCountdown.duration(20, 0, touchExploration = true))
        assertEquals(120_000L, LockCountdown.duration(120, 0, touchExploration = true))
    }

    @Test fun countsFromTheFirstFrameAndExpires() {
        val cd = LockCountdown(20_000, startedAt = 0)
        cd.firstFrame(500)
        assertEquals(20, cd.wholeSeconds(500))
        assertFalse(cd.expired(20_499))
        assertTrue(cd.warning(15_500))
        assertTrue(cd.expired(20_500))
        // only the first frame counts
        cd.firstFrame(10_000)
        assertTrue(cd.expired(20_500))
    }

    @Test fun unlockPromptPausesAndACancelGivesAtLeastFiveSeconds() {
        val cd = LockCountdown(20_000, startedAt = 0)
        cd.pause(19_000)
        assertFalse(cd.expired(60_000))
        assertEquals(1_000L, cd.remaining(60_000))
        cd.resume(60_000)
        assertEquals(LockCountdown.RESUME_FLOOR_MS, cd.remaining(60_000))
        assertTrue(cd.expired(65_000))
    }

    @Test fun unlockingStopsItForGood() {
        val cd = LockCountdown(20_000, startedAt = 0)
        cd.stop()
        assertFalse(cd.expired(100_000))
        assertFalse(cd.warning(100_000))
        assertEquals(20_000L, cd.remaining(100_000))
    }

    @Test fun spokenSecondsRoundUpToFiveSecondSteps() {
        val cd = LockCountdown(20_000, startedAt = 0)
        assertEquals(20, cd.spokenSeconds(0))
        assertEquals(15, cd.spokenSeconds(5_001))
        assertEquals(0.5f, cd.fraction(10_000), 0.001f)
        assertEquals(0.55f, cd.fraction(9_500, stepped = true), 0.001f)
    }
}
