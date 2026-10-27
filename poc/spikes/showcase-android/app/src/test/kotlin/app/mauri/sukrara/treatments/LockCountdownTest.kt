// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The lock-screen countdown's rules (round 52, PRODUCT A2): duration, pause / resume floor, expiry, unlock. */
class LockCountdownTest {
    @Test fun durationIsTheSettingRaisedByAccessibility() {
        assertEquals(20_000L, LockCountdown.duration(20, 20_000, touchExploration = false))
        // Android's *Time to take action* = 2 min
        assertEquals(120_000L, LockCountdown.duration(20, 120_000, touchExploration = false))
        // TalkBack: at least 60 s
        assertEquals(60_000L, LockCountdown.duration(20, 20_000, touchExploration = true))
        // the setting at 120 s with TalkBack: 120 s
        assertEquals(120_000L, LockCountdown.duration(120, 120_000, touchExploration = true))
        assertEquals(90_000L, LockCountdown.duration(90, 0, touchExploration = true))
    }

    @Test fun emptiesAndExpires() {
        val c = LockCountdown(20_000, startedAt = 1_000)
        assertEquals(1f, c.fraction(1_000), 1e-6f)
        assertEquals(0.5f, c.fraction(11_000), 1e-6f)
        assertFalse(c.expired(20_999))
        assertTrue(c.expired(21_000))
        assertEquals(0L, c.remaining(30_000))
    }

    @Test fun countsFromTheFirstFrameOnce() {
        val c = LockCountdown(20_000, 0)
        c.firstFrame(3_000)             // a slow first frame
        assertEquals(20_000L, c.remaining(3_000))
        c.firstFrame(10_000)            // only once
        assertEquals(13_000L, c.remaining(10_000))
    }

    @Test fun touchesNeverAddTime() {
        // nothing but pause / resume / stop changes the deadline: there's no "touched" call to make
        val c = LockCountdown(20_000, 0)
        assertTrue(c.expired(20_000))
    }

    @Test fun pauseHoldsTimeAndResumeKeepsAtLeastFiveSeconds() {
        val c = LockCountdown(20_000, 0)
        c.pause(18_000)                 // 2 s left when the unlock prompt went up
        assertTrue(c.paused)
        assertEquals(2_000L, c.remaining(60_000))
        assertFalse(c.expired(60_000))
        c.resume(60_000)                // cancelled: at least 5 s
        assertEquals(5_000L, c.remaining(60_000))
        assertTrue(c.expired(65_000))
        // with more left than the floor, resume keeps what was left
        val d = LockCountdown(20_000, 0)
        d.pause(4_000); d.resume(100_000)
        assertEquals(16_000L, d.remaining(100_000))
    }

    @Test fun unlockStopsIt() {
        val c = LockCountdown(20_000, 0)
        c.stop()
        assertTrue(c.stopped)
        assertFalse(c.expired(1_000_000))
        assertFalse(c.warning(1_000_000))
    }

    @Test fun warningAnnouncementAndSpokenSteps() {
        val c = LockCountdown(20_000, 0)
        assertFalse(c.warning(14_999))
        assertTrue(c.warning(15_000))
        assertEquals(20, c.spokenSeconds(0))
        assertEquals(20, c.spokenSeconds(1_000))   // 19 s left -> 20
        assertEquals(15, c.spokenSeconds(5_000))
        assertEquals(5, c.spokenSeconds(19_000))
        // the number in the ring: whole seconds, rounded up
        assertEquals(20, c.wholeSeconds(0))
        assertEquals(19, c.wholeSeconds(1_000))
        assertEquals(1, c.wholeSeconds(19_500))
        assertEquals(0, c.wholeSeconds(20_000))
    }

    @Test fun reducedMotionStepsOncePerSecond() {
        val c = LockCountdown(20_000, 0)
        assertEquals(1f, c.fraction(500, stepped = true), 1e-6f)
        assertEquals(0.95f, c.fraction(1_000, stepped = true), 1e-6f)
        assertEquals(0.95f, c.fraction(1_900, stepped = true), 1e-6f)
    }
}
