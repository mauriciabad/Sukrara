// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.Notification
import android.app.NotificationManager.INTERRUPTION_FILTER_ALARMS
import android.app.NotificationManager.INTERRUPTION_FILTER_ALL
import android.app.NotificationManager.INTERRUPTION_FILTER_NONE
import android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
import android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [AlertCategory]: an alert takes `CATEGORY_ALARM` (exempt from Android's notification cooldown) unless that would change
 * what Do Not Disturb lets through; then it stays `CATEGORY_REMINDER`.
 */
class AlertCategoryTest {
    private val alarm = Notification.CATEGORY_ALARM
    private val reminder = Notification.CATEGORY_REMINDER

    private fun dnd(filter: Int, alarms: Boolean = false, reminders: Boolean = false) = AlertCategory.Dnd(filter, alarms, reminders)

    @Test fun `an alarm-level kind is always an alarm`() {
        val all =
            listOf(
                INTERRUPTION_FILTER_ALL,
                INTERRUPTION_FILTER_PRIORITY,
                INTERRUPTION_FILTER_ALARMS,
                INTERRUPTION_FILTER_NONE,
                INTERRUPTION_FILTER_UNKNOWN,
            )
        for (f in all) {
            for (a in listOf(true, false)) {
                for (r in listOf(true, false)) {
                    assertEquals(alarm, AlertCategory.of(alarm = true, dnd = dnd(f, a, r)))
                }
            }
        }
    }

    @Test fun `with Do Not Disturb off a notification-volume alert is an alarm, so the cooldown can't quieten it`() {
        assertEquals(alarm, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_ALL)))
        // Do Not Disturb unknown: as before
        assertEquals(reminder, AlertCategory.of(alarm = false, dnd = null))
        assertEquals(alarm, AlertCategory.of(alarm = true, dnd = null))
    }

    @Test fun `Priority letting alarms and reminders through - an alarm passes exactly when a reminder would`() {
        assertEquals(alarm, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_PRIORITY, alarms = true, reminders = true)))
    }

    @Test fun `every other Do Not Disturb keeps the reminder, so DND decides as before`() {
        // alarms allowed but not reminders (Android's default): an alarm category would ring through DND
        assertEquals(reminder, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_PRIORITY, alarms = true)))
        // reminders allowed but not alarms: an alarm category would be blocked
        assertEquals(reminder, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_PRIORITY, reminders = true)))
        assertEquals(reminder, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_PRIORITY)))
        // alarms only lets every CATEGORY_ALARM through
        assertEquals(reminder, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_ALARMS, alarms = true, reminders = true)))
        assertEquals(reminder, AlertCategory.of(alarm = false, dnd = dnd(INTERRUPTION_FILTER_NONE, alarms = true, reminders = true)))
    }
}
