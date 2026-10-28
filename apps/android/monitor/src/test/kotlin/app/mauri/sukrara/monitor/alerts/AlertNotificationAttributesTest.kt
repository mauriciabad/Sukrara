// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import androidx.test.core.app.ApplicationProvider
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertReading
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Firing
import app.mauri.sukrara.monitor.notify.NotificationIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What Android gets for each alert: the channel (audio usage, sound, Do Not Disturb) and the notification (category,
 * full-screen intent). Low at alarm level and Falling fast at notification level fire in the same reading — the case
 * where Android 15+ notification cooldown would play the second one at 30 % unless it is `CATEGORY_ALARM`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlertNotificationAttributesTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val settings =
        AlertSettings()
            .let { it.withRule(AlertKind.LOW, it.rule(AlertKind.LOW).copy(on = true, alarm = true)) }
            .let { it.withRule(AlertKind.FALLING_FAST, it.rule(AlertKind.FALLING_FAST).copy(on = true, alarm = false)) }

    @Before fun setUp() {
        // a library test has no launcher icon; the notifier uses the app's
        ctx.applicationInfo.icon = android.R.drawable.star_on
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        AlertChannels.ensure(ctx)
    }

    private fun fire(kind: AlertKind, mgdl: Int, rate: Double? = null) =
        Firing(kind, "e-${kind.wire}", 1, AlertReading(t = 1_000, mgdl = mgdl, rate = rate), threshold = 70.0, at = 1_000)

    private fun posted(kind: AlertKind): Notification = shadowOf(nm).getNotification(AlertNotifier.idOf(kind.group)).also(::assertNotNull)

    private fun showLowAndFallingFast() {
        val notifier = AlertNotifier(ctx)
        notifier.show(fire(AlertKind.LOW, 62, -2.5), settings, silent = false, who = null, relaxedMin = 0)
        notifier.show(fire(AlertKind.FALLING_FAST, 62, -2.5), settings, silent = false, who = null, relaxedMin = 0)
    }

    @Test fun `alarm channels ring at alarm volume and pass DND, alert channels at notification volume and don't`() {
        for (kind in AlertKind.entries) {
            val alarm = nm.getNotificationChannel(AlertChannels.id(kind, alarm = true))
            assertEquals(AudioAttributes.USAGE_ALARM, alarm.audioAttributes.usage)
            assertTrue(alarm.canBypassDnd())
            assertEquals(AlertChannels.soundUri(ctx, kind), alarm.sound)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, alarm.importance)

            val alert = nm.getNotificationChannel(AlertChannels.id(kind, alarm = false))
            assertEquals(AudioAttributes.USAGE_NOTIFICATION_EVENT, alert.audioAttributes.usage)
            assertFalse(alert.canBypassDnd())
            assertEquals(AlertChannels.soundUri(ctx, kind), alert.sound)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, alert.importance)
        }
    }

    @Test fun `with DND off both alerts are CATEGORY_ALARM on their own channels, only the alarm goes full screen`() {
        showLowAndFallingFast()
        val low = posted(AlertKind.LOW)
        assertEquals(Notification.CATEGORY_ALARM, low.category)
        assertEquals(AlertChannels.id(AlertKind.LOW, alarm = true), low.channelId)
        // full screen where Android allows it (Robolectric doesn't grant it)
        assertEquals(nm.canUseFullScreenIntent(), low.fullScreenIntent != null)

        val falling = posted(AlertKind.FALLING_FAST)
        assertEquals(Notification.CATEGORY_ALARM, falling.category)
        assertEquals(AlertChannels.id(AlertKind.FALLING_FAST, alarm = false), falling.channelId)
        assertNull(falling.fullScreenIntent)
    }

    @Test fun `in Android's default Priority DND the notification-volume alert stays a reminder (blocked as before)`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        shadowOf(
            nm,
        ).setConsolidatedNotificationPolicy(NotificationManager.Policy(NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS, 0, 0))
        showLowAndFallingFast()
        assertEquals(Notification.CATEGORY_ALARM, posted(AlertKind.LOW).category)
        assertEquals(Notification.CATEGORY_REMINDER, posted(AlertKind.FALLING_FAST).category)
    }

    @Test fun `Priority DND letting reminders and alarms through - the notification-volume alert is an alarm`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val both = NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS
        shadowOf(nm).setConsolidatedNotificationPolicy(NotificationManager.Policy(both, 0, 0))
        showLowAndFallingFast()
        assertEquals(Notification.CATEGORY_ALARM, posted(AlertKind.FALLING_FAST).category)
    }

    @Test fun `alarms-only DND keeps the notification-volume alert a reminder`() {
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        showLowAndFallingFast()
        assertEquals(Notification.CATEGORY_ALARM, posted(AlertKind.LOW).category)
        assertEquals(Notification.CATEGORY_REMINDER, posted(AlertKind.FALLING_FAST).category)
    }

    @Test fun `a test alert follows the same rule, on the kind's real channel, never full screen`() {
        AlertNotifier(ctx).showTest(AlertKind.FALLING_FAST, settings)
        val test = shadowOf(nm).getNotification(NotificationIds.ALERT_TEST)
        assertEquals(Notification.CATEGORY_ALARM, test.category)
        assertEquals(AlertChannels.id(AlertKind.FALLING_FAST, alarm = false), test.channelId)
        assertNull(test.fullScreenIntent)
    }
}
