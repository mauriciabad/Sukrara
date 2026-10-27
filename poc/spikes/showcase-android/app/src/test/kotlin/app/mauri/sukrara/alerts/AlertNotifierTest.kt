// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.app.Notification
import android.app.NotificationManager
import android.provider.Settings
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards against two sounds for one alert (docs/15): every alert channel rings only its own Sukrara sound, an alert
 * notification adds no sound of its own and rings as a child of Sukrara's alert group (round 53: GROUP_ALERT_ALL, the
 * summary never alerts), and a re-shown alert (after a restart) is silent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AlertNotifierTest {
    private val ctx = RuntimeEnvironment.getApplication()

    @Test fun everyAlertChannelHasItsOwnSound() {
        AlertNotifier.channels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)
        for (k in Kind.ALL) for (alarm in listOf(false, true)) {
            val ch = nm.getNotificationChannel(AlertNotifier.channelId(k, alarm))
            assertEquals(AlertSounds.uri(ctx, k), ch.sound)
            assertNotEquals(Settings.System.DEFAULT_NOTIFICATION_URI, ch.sound)
        }
    }

    @Test fun alertNotificationAddsNoSecondSound() {
        val n = AlertNotifier.build(ctx, Kind.HIGH, 200, 0.0, 180.0, 1_760_000_000L, null, alarm = false, treated = 0)
        assertNull(n.sound)
        assertEquals(0, n.defaults and Notification.DEFAULT_SOUND)
        assertEquals(app.mauri.sukrara.core.NotifGroups.Group.ALERTS.key, n.group)
        assertEquals(Notification.GROUP_ALERT_ALL, n.groupAlertBehavior)
        assertFalse(n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }

    @Test fun reShownAlertIsSilent() {
        val quiet = AlertNotifier.build(ctx, Kind.HIGH, 200, 0.0, 180.0, 1_760_000_000L, null, alarm = false, treated = 0, silent = true)
        assertEquals(app.mauri.sukrara.core.NotifGroups.Group.ALERTS.key, quiet.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, quiet.groupAlertBehavior)
        assertFalse(quiet.flags and Notification.FLAG_GROUP_SUMMARY != 0)
    }
}
