// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Every state an alert can be in on this phone and every way out of it (SPEC §2 "States and exits", round f29 +
 * f30), through the notification and through the app (Alerts.unmute / unrelax / mute / muteAll / unmuteAll: what
 * the home control, the sheet, Active now and TalkBack call), checked on what is posted or cancelled, the badge, Active
 * now and the history. The rules themselves: the timeline scenarios (AlertScenarioTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlertExitsTest {
    private val k = AlertKit(RuntimeEnvironment.getApplication())

    @After fun tearDown() = k.stop()

    // ---------------------------------------------------------------- side muted

    @Test fun unmuteFromTheMutedNotificationAlertsAgainAtOnce() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "30 min")
        k.tap(Kind.LOW, "Unmute")
        assertEquals("Low glucose", k.title(Kind.LOW))   // still low: at once, with its buttons back
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertFalse(k.silent(Kind.LOW))
        assertTrue(Alerts.active.value.isEmpty())
        assertTrue(Alerts.paused.value.isEmpty())
        assertEquals(listOf("Low glucose (2) · 65 mg/dL", "Low alerts unmuted"), k.history().take(2))
    }

    @Test fun changeOnTheMutedNotificationSetsANewEnd() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "30 min")
        k.tap(Kind.LOW, "Change time", typed = "2 h")
        assertTrue(k.title(Kind.LOW) == "Low alerts muted")
        assertEquals(listOf("Unmute", "Change time"), k.buttons(Kind.LOW))
        assertTrue(k.notification(Kind.LOW)!!.timeoutAfter in 119 * 60_000L..120 * 60_000L)
        assertEquals(k.now + 7200, Alerts.active.value.single().until)
        assertEquals("Low alerts muted for 2 h", k.history().first())
        // the sheet's / Active now's "Change to 15 min": the muted notification follows
        Alerts.mute(k.ctx, Kind.LOW, 15)
        assertTrue(k.notification(Kind.LOW)!!.timeoutAfter in 14 * 60_000L..15 * 60_000L)
        assertEquals("Low alerts muted for 15 min", k.history().first())
    }

    @Test fun aSideMuteEndsAtItsTimeAndAlertsAtOnce() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "15 min")
        k.advance(15 * 60L + 1)
        k.reading(64)
        assertEquals("Low glucose", k.title(Kind.LOW))
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertFalse(k.silent(Kind.LOW))
        assertTrue(Alerts.paused.value.isEmpty())
    }

    @Test fun backInRangeByTheMarginEndsTheMuteAndRemovesItsNotification() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "1 h")
        k.advance(60); k.reading(85)            // ≥ 70 + 10: back by the margin, the count starts
        assertTrue(k.title(Kind.LOW) == "Low alerts muted")
        k.advance(5 * 60); k.reading(75)        // crosses back out of the margin: the count restarts
        k.advance(5 * 60); k.reading(85)
        k.advance(9 * 60); k.reading(86)
        assertTrue("not 10 min in a row yet", Alerts.paused.value.containsKey("low"))
        k.advance(60); k.reading(86)            // 10 min in a row
        assertNull(k.notification(Kind.LOW))
        assertEquals("Low back in range: alerts as usual", k.history().first())
        assertTrue(Alerts.paused.value.isEmpty())
        assertTrue(Alerts.active.value.isEmpty())
        k.advance(60); k.reading(65)            // a new low alerts at once
        assertEquals("Low glucose", k.title(Kind.LOW))
    }

    @Test fun backInRangeByTheMarginEndsRelaxToo() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Remind less")
        k.advance(60); k.reading(90)
        k.advance(10 * 60); k.reading(90)
        assertEquals("Low back in range: alerts as usual", k.history().first())
        assertTrue(Alerts.active.value.isEmpty())
    }

    @Test fun unmuteBackInRangeShowsNothing() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "30 min")
        k.advance(60); k.reading(100)
        Alerts.unmute(k.ctx, Kind.LOW)
        assertNull(k.notification(Kind.LOW))    // the muted notification went, no alert
        assertTrue(Alerts.active.value.isEmpty())
    }

    // ---------------------------------------------------------------- quiet after a treatment / falling

    @Test fun alertMeAnywayLiftsTheCarbsMuteOnThisPhone() {
        k.start(doses = { AlertEngine.Doses(carbs = listOf(k.now - 60)) })
        k.reading(65)
        assertNull(k.notification(Kind.LOW))    // quiet after carbs: nothing posted
        assertEquals(AlertEngine.QUIET_CARBS, Alerts.active.value.single().quiet)
        Alerts.unmute(k.ctx, Kind.LOW)          // Alert me anyway
        assertEquals("Low glucose · eat more", k.title(Kind.LOW))
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertTrue(Alerts.active.value.isEmpty())
    }

    @Test fun aFallingHighIsQuietUntilAlertMeAnyway() {
        k.start()
        k.reading(190, rate = -3.0)             // under 180 within 20 min
        assertNull(k.notification(Kind.HIGH))
        assertEquals(AlertEngine.QUIET_FALLING, Alerts.active.value.single().quiet)
        Alerts.unmute(k.ctx, Kind.HIGH)
        assertEquals("High glucose", k.title(Kind.HIGH))
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.HIGH))
    }

    // ---------------------------------------------------------------- relaxed, muted + relaxed

    @Test fun stopRelaxingFromTheApp() {
        k.start()
        k.reading(65)
        Alerts.relax(k.ctx, Kind.LOW)           // the sheet's Relax
        assertEquals(listOf("Mute…", "Remind as usual"), k.buttons(Kind.LOW))
        Alerts.unrelax(k.ctx, Kind.LOW)         // the sheet's / Active now's Stop relaxing
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertTrue(Alerts.active.value.isEmpty())
    }

    @Test fun mutedAndRelaxedTogether() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Remind less")
        k.tap(Kind.LOW, "Mute…", typed = "1 h")
        assertEquals(listOf("Unmute", "Change time"), k.buttons(Kind.LOW))
        val a = Alerts.active.value.single()
        assertEquals(AlertEngine.QUIET_NOTIFICATION, a.quiet)
        assertEquals(30, a.relaxMin)
        k.tap(Kind.LOW, "Unmute")
        assertEquals("Low glucose", k.title(Kind.LOW))
        assertEquals(listOf("Mute…", "Remind as usual"), k.buttons(Kind.LOW))   // still relaxed
        assertEquals("Reminders every 30 min", k.sub(Kind.LOW))
    }

    // ---------------------------------------------------------------- Mute all

    @Test fun muteAllForATimeSilencesEveryKindThenTurnsBackOn() {
        k.start()
        k.reading(65)
        assertEquals("Low glucose", k.title(Kind.LOW))
        Alerts.muteAll(k.ctx, 120)
        assertNull(k.notification(Kind.LOW))    // the alert showing goes
        assertEquals(k.now + 7200, Alerts.allMuted.value)
        assertEquals("All alerts muted for 2 h", k.history().first())
        assertTrue("Mute all is its own entry, not a falling mute", Alerts.active.value.isEmpty())
        assertFalse(k.history().toString(), k.history().any { "cleared" in it })
        k.advance(60); k.reading(50)            // very low: nothing posted
        assertNull(k.notification(Kind.VERY_LOW))
        assertEquals(AlertStatus.Paused(Kind.VERY_LOW, k.now - 60 + 7200), k.status(50))
        k.advance(60); k.reading(320)           // extremely high: nothing posted
        assertNull(k.notification(Kind.EXTREMELY_HIGH))
        assertTrue(k.nm.activeNotifications.isEmpty())
        Alerts.muteAll(k.ctx, 30)               // Change
        assertEquals(k.now + 1800, Alerts.allMuted.value)
        Alerts.unmuteAll(k.ctx)                 // Turn alerts back on: still extremely high, at once
        assertEquals(0L, Alerts.allMuted.value)
        assertEquals("Extremely high glucose", k.title(Kind.EXTREMELY_HIGH))
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.EXTREMELY_HIGH))
        assertTrue(k.history().take(3).toString(), "Alerts unmuted" in k.history().take(3))
        assertTrue(Alerts.paused.value.isEmpty())
    }

    @Test fun muteAllNeverLastsUntilTurnedBackOnAndCoversNoData() {
        k.start()
        k.reading(120)
        Alerts.muteAll(k.ctx, Alerts.NEVER_MIN)
        assertEquals(AlertEngine.NEVER, Alerts.allMuted.value)
        assertEquals("All alerts muted until you turn it off", k.history().first())
        assertEquals(AlertStatus.Muted(AlertEngine.NEVER), k.status(120))
        k.advance(2 * 86_400L)                  // two days without readings: no "no data" either
        Alerts.check(k.ctx)
        assertTrue(k.nm.activeNotifications.isEmpty())
        k.reading(50)
        assertTrue(k.nm.activeNotifications.isEmpty())
        Alerts.unmuteAll(k.ctx)
        assertEquals("Very low glucose", k.title(Kind.VERY_LOW))
        assertEquals("Alerts unmuted", k.history()[1])
    }

    @Test fun muteAllEndsAtItsTime() {
        k.start()
        k.reading(65)
        Alerts.muteAll(k.ctx, 30)
        k.advance(30 * 60L + 1)
        k.reading(64)
        assertEquals("Low glucose", k.title(Kind.LOW))
        assertEquals(0L, Alerts.allMuted.value)
        assertTrue(k.history().toString(), "Alerts unmuted" in k.history())
    }

    @Test fun muteAllSurvivesARestart() {
        k.start()
        k.reading(120)
        Alerts.muteAll(k.ctx, Alerts.NEVER_MIN)
        // an app update kills the process: the saved state is all that's left
        Alerts.detach()
        Alerts.resetForTests(keepSaved = true)
        Alerts.init(k.ctx)
        assertEquals(AlertEngine.NEVER, Alerts.allMuted.value)
        Alerts.attach(k.ctx, "wearer", doses = { AlertEngine.Doses() }, publish = {})
        k.advance(60); k.reading(50)
        assertTrue(k.nm.activeNotifications.isEmpty())
        assertEquals(AlertEngine.NEVER, Alerts.allMuted.value)
    }

    // ---------------------------------------------------------------- a follower mirroring the wearer

    @Test fun followerMutesBackByTheMarginAndMuteAllOnTheWearersAlerts() {
        k.start(role = "follower")
        k.reading(65)
        k.remote(Kind.LOW, "a1", "fire", 65)
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        k.tap(Kind.LOW, "Mute…", typed = "1 h")
        assertTrue(k.title(Kind.LOW) == "Low alerts muted")
        k.advance(60); k.remote(Kind.LOW, "a1", "fire", 64, n = 2)
        assertTrue(k.title(Kind.LOW) == "Low alerts muted")   // the wearer's repeat stays quiet here
        k.advance(60); k.reading(85)
        k.advance(10 * 60); k.reading(85)
        assertNull(k.notification(Kind.LOW))    // back by the margin on the readings this phone gets
        assertEquals("Low back in range: alerts as usual", k.history().first())
        Alerts.muteAll(k.ctx, 60)
        k.advance(60); k.remote(Kind.VERY_LOW, "a2", "fire", 50)
        assertNull(k.notification(Kind.VERY_LOW))
        Alerts.unmuteAll(k.ctx)                 // the wearer's alert still up shows at once
        assertEquals("Very low glucose", k.title(Kind.VERY_LOW))
    }
}
