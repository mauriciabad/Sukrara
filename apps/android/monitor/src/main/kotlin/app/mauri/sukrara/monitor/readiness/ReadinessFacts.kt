// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.readiness

import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement

/**
 * What the phone answered for each item of the alert checklist, read by [AndroidAlertReadiness]. Kept apart from the
 * reads so the rule ([readiness]) is a plain function with JVM tests.
 */
data class ReadinessFacts(
    val notificationsEnabled: Boolean,
    /** At least one alert kind rings "like an alarm" (only then the full-screen intent matters). */
    val anyAlarmKind: Boolean,
    val fullScreenAllowed: Boolean,
    val exactAlarmAllowed: Boolean,
    val batteryExempt: Boolean,
    /** Settings → Apps → Battery "Restricted": Android stops the service in the background. */
    val backgroundRestricted: Boolean,
    /** An alert channel the settings use (or its group) is blocked or lowered below sound. */
    val alertChannelSilenced: Boolean,
    /** Do Not Disturb is on now and would silence the alarm-level alerts. */
    val dndSilencesAlarms: Boolean,
    /** The role's source talks to the sensor over Bluetooth (the wearer's own sensor, not Juggluco or a follower). */
    val needsBluetooth: Boolean,
    val bluetoothOn: Boolean,
    val bluetoothPermitted: Boolean,
) {
    fun readiness(): AlertReadiness =
        AlertReadiness(
            buildSet {
                if (!notificationsEnabled) add(AlertRequirement.Notifications)
                if (anyAlarmKind && !fullScreenAllowed) add(AlertRequirement.FullScreenIntent)
                if (!exactAlarmAllowed) add(AlertRequirement.ExactAlarm)
                if (!batteryExempt || backgroundRestricted) add(AlertRequirement.BatteryExemption)
                if (alertChannelSilenced || dndSilencesAlarms) add(AlertRequirement.AlertChannel)
                if (needsBluetooth && !(bluetoothOn && bluetoothPermitted)) add(AlertRequirement.Bluetooth)
            },
        )
}
