// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.Notification
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
 * Round f30: the alert notification's two actions (*Mute…* with its reply chips, *Remind less* / *Remind as usual*) on
 * every kind, and what is posted after each, read back from the NotificationManager: the silent muted notification
 * with Unmute + Change time (round f31: "Low alerts muted" / "Until 10:42" and a count-down chronometer to the end),
 * the one that reminds less with its interval in the secondary text, a bad reply leaving it as it was.
 * The rest of the states and their exits: AlertExitsTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlertMuteTest {
    private val k = AlertKit(RuntimeEnvironment.getApplication())
    private val choices = listOf("30 min", "15 min", "1 h", "2 h")

    @After fun tearDown() = k.stop()

    private fun assertAlert(kind: String, title: String, buttons: List<String>) {
        assertEquals(title, k.title(kind))
        assertEquals(buttons, k.buttons(kind))
        assertEquals(choices, k.choices(kind, "Mute…"))
        val input = k.input(kind, "Mute…")
        assertEquals("Mute for", input.label.toString())
        assertTrue(input.allowFreeFormInput)
        assertEquals(AlertNotifier.channelId(kind, Alerts.settings.value.cfg(kind).alarm), k.notification(kind)!!.channelId)
    }

    @Test fun everyKindHasExactlyMuteAndRelax() {
        for ((sgv, kind, title) in listOf(Triple(65, Kind.LOW, "Low glucose"), Triple(50, Kind.VERY_LOW, "Very low glucose"),
                Triple(200, Kind.HIGH, "High glucose"), Triple(320, Kind.EXTREMELY_HIGH, "Extremely high glucose"))) {
            k.start()
            k.reading(sgv)
            // very low and extremely high too (round f30: no exception any more)
            assertAlert(kind, title, listOf("Mute…", "Remind less"))
            assertNull(k.sub(kind))
            assertFalse(k.silent(kind))
            k.stop()
        }
        assertEquals(AlertNotifier.channelId(Kind.VERY_LOW, alarm = true), "alert_a_very_low")
    }

    @Test fun noDataRepeatsSlowerThanRelaxSoOnlyMute() {
        k.start()
        k.reading(120)
        k.advance(21 * 60)
        Alerts.check(k.ctx)
        // no data repeats every 60 min: Relax (30 min) would change nothing, so the slot stays empty
        assertEquals(listOf("Mute…"), k.buttons(Kind.NO_DATA))
    }

    @Test fun muteReplyLeavesTheSilentMutedNotification() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "1 h")
        assertTrue(k.title(Kind.LOW)!!, k.title(Kind.LOW) == "Low alerts muted")
        assertEquals(listOf("Unmute", "Change time"), k.buttons(Kind.LOW))
        assertEquals(choices, k.choices(Kind.LOW, "Change time"))
        assertTrue(k.silent(Kind.LOW))
        assertTrue(k.notification(Kind.LOW)!!.timeoutAfter in 59 * 60_000L..60 * 60_000L)
        assertEquals("Low alerts muted for 1 h", k.history().first())
        assertFalse(k.history().toString(), k.history().any { "cleared" in it })
        assertEquals(k.now + 3600, Alerts.paused.value["low"])
        assertEquals(AlertStatus.Paused(Kind.LOW, k.now + 3600), k.status(65))
        assertEquals(AlertEngine.QUIET_NOTIFICATION, Alerts.active.value.single().quiet)
    }

    /** Round f31: the muted notification counts down to the mute's end (`when`), and Change time moves it. */
    @Test fun mutedNotificationCountsDownToTheEnd() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "1 h")
        fun check(until: Long) {
            val n = k.notification(Kind.LOW)!!
            assertEquals("Low alerts muted", k.title(Kind.LOW))
            assertEquals("Until " + app.mauri.sukrara.graph.Freshness.timeText(until, k.now),
                n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertTrue("usesChronometer", n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
            assertTrue("chronometerCountDown", n.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
            assertTrue("showWhen", n.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
            assertEquals(until * 1000, n.`when`)
            assertTrue(n.timeoutAfter in (until - k.now - 60) * 1000..(until - k.now) * 1000)
            assertEquals(listOf("Unmute", "Change time"), k.buttons(Kind.LOW))
        }
        check(k.now + 3600)
        k.advance(10 * 60)
        k.tap(Kind.LOW, "Change time", typed = "2 h")
        check(k.now + 7200)
        assertEquals("Low alerts muted for 2 h", k.history().first())
    }

    /** Very low inside a low mute is still "Low alerts" (the side's name, MuteCopy.side). */
    @Test fun aVeryLowsMuteNamesTheLowSide() {
        k.start()
        k.reading(50)
        k.tap(Kind.VERY_LOW, "Mute…", typed = "30 min")
        assertEquals("Low alerts muted", k.title(Kind.VERY_LOW))
        assertTrue(k.notification(Kind.VERY_LOW)!!.extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN))
        assertEquals("Low alerts muted for 30 min", k.history().first())
    }

    @Test fun aChipReplyAndTypedLengthsMute() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "15 min")   // the one-tap chip sends its text
        assertEquals("Low alerts muted for 15 min", k.history().first())
        k.tap(Kind.LOW, "Change time", typed = "1:30")
        assertEquals("Low alerts muted for 1 h 30 min", k.history().first())
        assertEquals(listOf(15, 30, 60, 120, 90), listOf("15 min", "30", "1 h", "2h", "1 h 30").map { AlertNotifier.minutes(it) })
    }

    @Test fun aBadReplyRepostsTheAlertUnchanged() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Mute…", typed = "soon")
        assertEquals("Low glucose", k.title(Kind.LOW))
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertTrue(k.silent(Kind.LOW))   // posted again without sound (the reply's spinner goes)
        assertTrue(Alerts.paused.value.isEmpty())
        k.tap(Kind.LOW, "Mute…", typed = "13 h")   // longer than 12 h: out of range
        assertTrue(Alerts.paused.value.isEmpty())
    }

    @Test fun relaxPutsTheIntervalInTheSecondaryText() {
        k.start()
        k.reading(65)
        k.tap(Kind.LOW, "Remind less")
        assertEquals("Low glucose", k.title(Kind.LOW))
        assertEquals(listOf("Mute…", "Remind as usual"), k.buttons(Kind.LOW))
        assertEquals("Reminders every 30 min", k.sub(Kind.LOW))
        assertTrue(k.silent(Kind.LOW))
        assertEquals("Low reminders every 30 min", k.history().first())
        assertEquals(30, Alerts.active.value.single().relaxMin)
        k.tap(Kind.LOW, "Remind as usual")
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertNull(k.sub(Kind.LOW))
        assertEquals("Low reminders as usual (every 5 min)", k.history().first())
        assertTrue(Alerts.active.value.isEmpty())
    }

    @Test fun aHighsMuteNeverSilencesALow() {
        k.start()
        k.reading(200)
        k.tap(Kind.HIGH, "Mute…", typed = "2 h")
        assertTrue(k.title(Kind.HIGH) == "High alerts muted")
        k.advance(60)
        k.reading(65)
        assertEquals("Low glucose", k.title(Kind.LOW))
        assertEquals(listOf("Mute…", "Remind less"), k.buttons(Kind.LOW))
        assertFalse(k.silent(Kind.LOW))
        assertEquals(AlertStatus.Needed(Kind.LOW), k.status(65))
    }

    /** User, 2026-10-21: a mute on the wearer's phone mutes only that phone (scenarios: devices.txt). */
    @Test fun wearerMuteAndRelaxStayOnThisPhone() {
        k.start()
        k.reading(65)
        assertEquals(listOf("low/fire"), k.sent.map { "${it.getString("k")}/${it.getString("state")}" })
        k.tap(Kind.LOW, "Remind less")
        k.tap(Kind.LOW, "Mute…", typed = "30 min")
        Alerts.muteAll(k.ctx, 60)
        assertEquals("the followers' alert goes on: no clear, no other event", 1, k.sent.size)
    }

    @Test fun defaultsOfferRelaxOnEveryLevelAlertAndOnlyUsefulIntervals() {
        val s = AlertSettings()
        for (kind in Kind.LEVELS) assertTrue(kind, s.cfg(kind).repeatMin in 1 until s.relaxMin)
        assertTrue(s.relaxMin in s.relaxChoices())
        assertTrue(s.relaxChoices().all { it > s.relaxFloor() })
        assertEquals(listOf(30, 45, 60, 90, 120), s.copy(kinds = s.kinds + (Kind.HIGH to s.cfg(Kind.HIGH).copy(repeatMin = 20))).relaxChoices())
        assertEquals(listOf(30, 15, 60, 120), s.muteChoices())
    }
}
