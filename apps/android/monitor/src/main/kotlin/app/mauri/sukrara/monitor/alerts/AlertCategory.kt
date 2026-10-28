// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.alerts

import android.app.Notification
import android.app.NotificationManager

/**
 * The notification category of a glucose alert, chosen at each post so every alert that rings is heard in full.
 *
 * Android 15+ *notification cooldown* (NotificationAttentionHelper `StrategyPerApp`, on for all apps by default where
 * the person has it on) keys on the app: any notification of ours that sounded in the last 60 s makes the next one play
 * at 30 % volume and vibration, a third within 10 s is muted. Low and Falling fast fire in the same reading, so the
 * second would be quiet. Only `CATEGORY_ALARM` (and the car categories) is exempt — the channel's audio usage doesn't
 * matter.
 *
 * Do Not Disturb (ZenModeFiltering) also reads the category, so a notification-volume alert (`alarm` off) takes
 * `CATEGORY_ALARM` only when that can't change whether it gets through:
 * - an alarm-level kind: always `CATEGORY_ALARM` (passes "Alarms" in DND, its channel is `USAGE_ALARM` anyway);
 * - DND off: `CATEGORY_ALARM` (nothing filters, it is only exempt from the cooldown);
 * - DND unknown (Android couldn't say): `CATEGORY_REMINDER`, as before;
 * - DND *Priority* letting both alarms and reminders through: `CATEGORY_ALARM` (passes exactly when a reminder would);
 * - any other DND (alarms only, nothing, Priority without alarms or reminders): `CATEGORY_REMINDER`, so DND decides
 *   as it always did — blocked unless the person let reminders or this channel through (then the cooldown can still
 *   soften it).
 *
 * The channel stays what it is (sound, notification volume, vibration, the person's overrides); only the category
 * moves. Read right before the post; DND turning on in between can let one alert through DND (louder, never quieter),
 * and an alert already showing when DND starts stays in the shade (it doesn't ring again). Separate from the cooldown,
 * Android makes one sound per app per second: see AlertRuntime's ring order.
 */
object AlertCategory {
    /** What Do Not Disturb lets through right now ([NotificationManager.getCurrentInterruptionFilter] + policy). */
    data class Dnd(
        val filter: Int,
        val allowsAlarms: Boolean,
        val allowsReminders: Boolean,
    ) {
        companion object {
            /** Null when Android can't say (the notification service unreachable). */
            fun of(nm: NotificationManager): Dnd? = runCatching { read(nm) }.getOrNull()

            private fun read(nm: NotificationManager): Dnd? {
                val filter = nm.currentInterruptionFilter
                if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return null
                // only Priority lets categories through one by one
                if (filter !=
                    NotificationManager.INTERRUPTION_FILTER_PRIORITY
                ) {
                    return Dnd(filter, allowsAlarms = false, allowsReminders = false)
                }
                val categories = nm.consolidatedNotificationPolicy.priorityCategories
                return Dnd(
                    filter,
                    allowsAlarms = categories and NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS != 0,
                    allowsReminders = categories and NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS != 0,
                )
            }
        }
    }

    /** [dnd] null = Do Not Disturb couldn't be read: the reminder, as before (DND decides). */
    fun of(alarm: Boolean, dnd: Dnd?): String {
        val asAlarm =
            alarm ||
                dnd?.filter == NotificationManager.INTERRUPTION_FILTER_ALL ||
                (dnd?.filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY && dnd.allowsAlarms && dnd.allowsReminders)
        return if (asAlarm) Notification.CATEGORY_ALARM else Notification.CATEGORY_REMINDER
    }
}
