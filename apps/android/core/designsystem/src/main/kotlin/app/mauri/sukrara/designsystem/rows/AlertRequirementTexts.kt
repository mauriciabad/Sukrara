// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.rows

import androidx.annotation.StringRes
import app.mauri.sukrara.core.designsystem.R
import app.mauri.sukrara.core.model.AlertRequirement

/**
 * An alert checklist item's name: the ONE wording first-run setup's permission step and Settings › Alert reliability
 * both show (home's reliability row names the first problem in its own sentence).
 */
@StringRes
fun AlertRequirement.nameRes(): Int =
    when (this) {
        AlertRequirement.Notifications -> R.string.ds_req_notifications
        AlertRequirement.FullScreenIntent -> R.string.ds_req_full_screen
        AlertRequirement.ExactAlarm -> R.string.ds_req_exact_alarm
        AlertRequirement.BatteryExemption -> R.string.ds_req_battery
        AlertRequirement.AlertChannel -> R.string.ds_req_channel
        AlertRequirement.Bluetooth -> R.string.ds_req_bluetooth
    }

/** What the item does for the person, under its [nameRes]. */
@StringRes
fun AlertRequirement.whyRes(): Int =
    when (this) {
        AlertRequirement.Notifications -> R.string.ds_req_notifications_why
        AlertRequirement.FullScreenIntent -> R.string.ds_req_full_screen_why
        AlertRequirement.ExactAlarm -> R.string.ds_req_exact_alarm_why
        AlertRequirement.BatteryExemption -> R.string.ds_req_battery_why
        AlertRequirement.AlertChannel -> R.string.ds_req_channel_why
        AlertRequirement.Bluetooth -> R.string.ds_req_bluetooth_why
    }
