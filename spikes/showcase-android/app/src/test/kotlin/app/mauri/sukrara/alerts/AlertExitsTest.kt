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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Round f29 (user, 2026-10-21: "There's no way to unmute! On any platform"): every quiet state on the phone has a way
 * out, through the app (Alerts.unmute / unslow / action, what the home sheet, Active now and TalkBack call) and the
 * notification (Unmute / Change… on the muted one, Stop slowing on a slowed one), and leaving it updates the
 * notification, the badge (paused), Active now and the history at once. The rules themselves: scenarios exits.txt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlertExitsTest {
    companion object { private var clock = 0 }
    private val ctx = RuntimeEnvironment.getApplication()
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    /**
     * Reading times: increasing, and in the past (the Alerts singleton keeps the newest reading across test classes,
     * and an engine ignores a reading older than its newest: a future one would silence the next class's).
     */
    private fun at() = Proto.nowS() - 600 + (clock++)

    private fun start(doses: AlertEngine.Doses = AlertEngine.Doses(), sgv: Int = 65) {
        Alerts.init(ctx)
        Alerts.attach(ctx, "wearer", doses = { doses }, publish = {})
        Alerts.update(ctx) { AlertSettings(on = false) }   // the singleton's episodes from another test end
        Alerts.update(ctx) { AlertSettings() }
        Alerts.history.value = emptyList()
        Alerts.onReading(ctx, Reading(at(), sgv, 0.0, "test"))
    }

    @After fun tearDown() { Alerts.detach(); nm.cancelAll() }

    private fun shown() = nm.activeNotifications.singleOrNull { it.id == AlertNotifier.notificationId(Kind.LOW) }?.notification
    private fun title() = shown()?.extras?.getCharSequence("android.title")?.toString()
    private fun buttons() = shown()?.actions?.map { it.title.toString() } ?: emptyList()
    private fun receive(action: String, typed: String? = null) {
        val i = Intent(action).putExtra(AlertActionReceiver.KIND, Kind.LOW)
        if (typed != null) RemoteInput.addResultsToIntent(shown()!!.actions.single { it.remoteInputs != null }.remoteInputs, i,
            Bundle().apply { putCharSequence(AlertActionReceiver.TYPED, typed) })
        AlertActionReceiver().onReceive(ctx, i)
    }

    @Test fun unmuteFromTheMutedNotificationAlertsAgainAtOnce() {
        start()
        Alerts.action(ctx, Kind.LOW, slow = false)
        assertTrue(title(), title()!!.startsWith("Low muted until"))
        assertEquals(listOf("Unmute", "Change…"), buttons())
        assertEquals(AlertEngine.QUIET_NOTIFICATION, Alerts.active.value.single().quiet)
        assertTrue(Alerts.paused.value.containsKey("low"))
        receive(AlertActionReceiver.UNMUTE)
        assertEquals("Low glucose", title())                    // still low: at once, with its buttons back
        assertEquals(listOf("Mute 30 min", "Slow to 30 min", "Mute for…"), buttons())
        assertTrue(Alerts.active.value.isEmpty())
        assertTrue(Alerts.paused.value.isEmpty())
        // newest first: the alert, then the unmute that brought it
        assertEquals(listOf("Low glucose (2)", "Low unmuted"), Alerts.history.value.take(2).map { it.english().substringAfter("  ").substringBefore(" ·") })
    }

    @Test fun changeFromTheMutedNotificationSetsANewEnd() {
        start()
        Alerts.action(ctx, Kind.LOW, slow = false)
        receive(AlertActionReceiver.MUTE_FOR, typed = "10")
        assertTrue(title(), title()!!.startsWith("Low muted until"))
        assertTrue(shown()!!.timeoutAfter in 1..(10 * 60_000L))
        assertEquals(listOf("Unmute", "Change…"), buttons())
        val until = Alerts.active.value.single().until
        assertTrue(until - Proto.nowS() in 9 * 60..10 * 60)
        assertTrue(Alerts.history.value.first().english().endsWith("Low muted for 10 min (notification)"))
    }

    @Test fun changeFromTheAppWhileMuted() {
        start()
        Alerts.action(ctx, Kind.LOW, slow = false)
        Alerts.action(ctx, Kind.LOW, slow = false, min = 120)  // the sheet's / Active now's "Change to 120 min"
        assertTrue(Alerts.active.value.single().until - Proto.nowS() in 119 * 60..120 * 60)
        assertTrue(shown()!!.timeoutAfter > 60 * 60_000L)    // the muted notification follows the new end
    }

    @Test fun stopSlowingDownFromTheNotificationAndTheApp() {
        start()
        Alerts.action(ctx, Kind.LOW, slow = true)
        assertEquals(listOf("Mute 30 min", "Stop slowing", "Mute for…"), buttons())
        assertEquals(30, Alerts.active.value.single().relaxMin)
        receive(AlertActionReceiver.UNSLOW)
        assertEquals(listOf("Mute 30 min", "Slow to 30 min", "Mute for…"), buttons())
        assertTrue(Alerts.active.value.isEmpty())
        Alerts.action(ctx, Kind.LOW, slow = true)
        Alerts.unslow(ctx, Kind.LOW)                              // the app's Stop slowing down
        assertTrue(Alerts.active.value.isEmpty())
        assertTrue(Alerts.history.value.first().english().endsWith("Low back to every 5 min"))
    }

    @Test fun alertMeAnywayLiftsTheCarbsMuteOnThisPhone() {
        start(AlertEngine.Doses(carbs = listOf(Proto.nowS() - 60)))
        assertEquals(null, shown())                               // quiet after carbs
        assertEquals(AlertEngine.QUIET_CARBS, Alerts.active.value.single().quiet)
        Alerts.unmute(ctx, Kind.LOW)                              // Alert me anyway
        assertTrue(title(), title()!!.startsWith("Low glucose"))
        assertTrue(Alerts.active.value.isEmpty())
    }

    @Test fun unmuteBackInRangeShowsNothing() {
        start()
        Alerts.action(ctx, Kind.LOW, slow = false)
        Alerts.onReading(ctx, Reading(at(), 100, 0.0, "test"))
        Alerts.unmute(ctx, Kind.LOW)
        assertEquals(null, shown())                               // the muted notification went, no alert
        assertTrue(Alerts.active.value.isEmpty())
    }
}
