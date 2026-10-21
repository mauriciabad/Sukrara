// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Validation 21, item 7: *Mute 30 min* and a typed *Mute for…* leave the same thing in the shade (the alert becomes
 * "Low muted until …", silent, no buttons), the history says "Low muted for N min" and never "Low cleared" while the
 * episode goes on; the defaults offer *Slow down* on every level alert; *Mute for…* has one-tap durations.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlertMuteTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    private fun lowAlert() {
        Alerts.init(ctx)
        Alerts.attach(ctx, "wearer", doses = { AlertEngine.Doses() }, publish = null)
        Alerts.update(ctx) { AlertSettings(on = false) }   // the singleton's episodes from another test end
        Alerts.update(ctx) { AlertSettings() }
        Alerts.history.value = emptyList()
        Alerts.onReading(ctx, Reading(Proto.nowS(), 65, 0.0, "test"))
    }

    @After fun tearDown() { Alerts.detach(); nm.cancelAll() }

    private fun alert() = nm.activeNotifications.single { it.id == AlertNotifier.notificationId(Kind.LOW) }.notification

    private fun assertMuted(min: Int) {
        val n = alert()
        assertTrue(n.extras.getCharSequence("android.title").toString(), n.extras.getCharSequence("android.title").toString().startsWith("Low muted until"))
        assertTrue("no buttons", n.actions.isNullOrEmpty())
        assertTrue(n.timeoutAfter in 1..(min * 60_000L))
        val h = Alerts.history.value.map { it.english() }
        assertTrue(h.first(), h.first().endsWith("Low muted for $min min (notification)"))
        assertFalse(h.toString(), h.any { "cleared" in it })
    }

    @Test fun muteButtonLeavesAMutedNotification() {
        lowAlert()
        assertEquals(3, alert().actions.size)   // Mute 30, Slow to 30 (low repeats every 5), Mute for…
        Alerts.action(ctx, Kind.LOW, slow = false)
        assertMuted(30)
    }

    @Test fun typedMuteForLeavesTheSame() {
        lowAlert()
        val reply = alert().actions.single { it.remoteInputs != null }
        val i = Intent(AlertActionReceiver.MUTE_FOR).putExtra(AlertActionReceiver.KIND, Kind.LOW)
        RemoteInput.addResultsToIntent(reply.remoteInputs, i, Bundle().apply { putCharSequence(AlertActionReceiver.TYPED, "45") })
        AlertActionReceiver().onReceive(ctx, i)
        assertMuted(45)
    }

    @Test fun muteForOffersOneTapDurations() {
        lowAlert()
        val input = alert().actions.single { it.remoteInputs != null }.remoteInputs.single()
        assertEquals(listOf("15 min", "60 min", "120 min"), input.choices.map { it.toString() })
        assertTrue(input.allowFreeFormInput)
        assertEquals(listOf(15, 60, 120), input.choices.map { AlertNotifier.minutes(it) })
    }

    @Test fun defaultsOfferSlowDownOnEveryLevelAlertAndOnlyUsefulIntervals() {
        val s = AlertSettings()
        for (k in Kind.LEVELS) assertTrue(k, s.cfg(k).repeatMin in 1 until s.slowMin)
        assertTrue(s.slowMin in s.slowChoices())
        assertTrue(s.slowChoices().all { it > s.slowFloor() })
        assertEquals(listOf(30, 45, 60, 90, 120), s.copy(kinds = s.kinds + (Kind.HIGH to s.cfg(Kind.HIGH).copy(repeatMin = 20))).slowChoices())
        assertNotNull(s.slowChoices().firstOrNull())
    }
}
