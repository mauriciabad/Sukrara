// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.sensorend.Reminder
import app.mauri.sukrara.sensorend.SensorEnd
import app.mauri.sukrara.sensorend.SensorEndConfig
import app.mauri.sukrara.sensorend.SensorEndPlan
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.framework.theme.divider
import com.android.settingslib.spa.widget.dialog.SettingsDialog
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsDialogItem

/*
 * Settings → Sensor end (wearer): the reminders before the sensor ends and the live countdown (sensorend/SensorEnd).
 * Per the settings guidelines (source.android.com/docs/core/settings/settings-guidelines): the status below each
 * title ("show the specific details"), radio buttons only in a dialog ("Radio buttons should not be used in settings
 * list"), explanations in the footer. Each reminder is a row (when · time); tapping it opens its dialog (days before
 * as radio buttons, the time through the M3 time picker, Remove); "Add reminder", a button under the list (SettingsLib
 * SectionButtonPreference), opens the same dialog empty.
 * Round "routine": a switch for first-unlock reminders in its own group under the list, and a Night group (the evening-before reminder,
 * bedtime and wake-up through the same time picker: Android's Bedtime schedule isn't readable by other apps).
 */

/** "On the end day" / "1 day before". */
@Composable
fun reminderDays(d: Int) = if (d == 0) stringResource(R.string.sensorend_end_day) else pluralStringResource(R.plurals.sensorend_days_before, d, d)

private fun minuteText(ctx: android.content.Context, m: Int): String =
    app.mauri.sukrara.graph.Clock.minuteOfDay(m)

/** The overview row's summary: "2 reminders · Countdown 8 h". */
@Composable
fun sensorEndSummary(): String {
    val c by SensorEnd.config.collectAsState()
    val h by SensorLife.countdown.collectAsState()
    val n = if (c.reminders.isEmpty()) stringResource(R.string.sensorend_no_reminders)
        else pluralStringResource(R.plurals.sensorend_reminders_count, c.reminders.size, c.reminders.size)
    return if (c.countdown) stringResource(R.string.sensorend_summary_countdown, n, h) else n
}

@Composable
fun SensorEndSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val c by SensorEnd.config.collectAsState()
    val hours by SensorLife.countdown.collectAsState()
    val sensor by SensorLife.current.collectAsState()
    // null = closed; index -1 = a new reminder
    var editing by remember { mutableStateOf<Int?>(null) }
    var choosingHours by remember { mutableStateOf(false) }
    // the night window's time picker: true = bedtime, false = wake-up, null = closed
    var pickingNight by remember { mutableStateOf<Boolean?>(null) }
    fun change(f: (SensorEndConfig) -> SensorEndConfig) = SensorEnd.update(ctx, f)
    val calendar = sensorCalendarRows()
    SettingsPage(stringResource(R.string.sensorend_title), onBack) {
        SettingsIntro(stringResource(R.string.sensorend_intro))
        SettingsGroup {
            val now = System.currentTimeMillis() / 1000
            val s = sensor
            val at = s?.let { app.mauri.sukrara.graph.Clock.dateTime(it.endEpoch) }
            info(stringResource(R.string.sensorend_current), when {
                s == null || at == null -> stringResource(R.string.sensorend_current_none)
                SensorLife.ended(s.endEpoch, now) -> stringResource(R.string.sensorend_current_ended, at)
                else -> pluralStringResource(R.plurals.sensorend_current_ends, SensorLife.labelQuantity(s.endEpoch, now), at, SensorLife.label(s.endEpoch, now))
            }, icon = R.drawable.ic_dia_sensor)
        }
        SettingsGroup(stringResource(R.string.sensorend_reminders)) {
            c.reminders.forEachIndexed { i, r ->
                value(reminderDays(r.daysBefore), minuteText(ctx, r.minuteOfDay), icon = R.drawable.ic_sym_notifications) { editing = i }
            }
        }
        // adding to the list: a button under it (SettingsLib SectionButtonPreference, SPA CategoryButton), not a row of it
        if (c.reminders.size < SensorEndConfig.MAX_REMINDERS) SettingsSectionButton(R.drawable.ic_sym_add, stringResource(R.string.sensorend_add)) { editing = -1 }
        SettingsGroup {
            switch(stringResource(R.string.sensorend_unlock_switch), c.unlock, summary = stringResource(R.string.sensorend_unlock_summary),
                icon = R.drawable.ic_sym_lock_open) { v -> change { it.copy(unlock = v) } }
        }
        SettingsGroup(stringResource(R.string.sensorend_night_group)) {
            val evening = (c.nightStart - SensorEndPlan.EVENING_BEFORE + 24 * 60) % (24 * 60)
            switch(stringResource(R.string.sensorend_evening_switch), c.evening, icon = R.drawable.ic_sym_nightlight,
                summary = stringResource(R.string.sensorend_evening_summary, minuteText(ctx, evening))) { v -> change { it.copy(evening = v) } }
            value(stringResource(R.string.sensorend_bedtime), minuteText(ctx, c.nightStart), enabled = c.evening, icon = R.drawable.ic_sym_bedtime) { pickingNight = true }
            value(stringResource(R.string.sensorend_wakeup), minuteText(ctx, c.nightEnd), enabled = c.evening, icon = R.drawable.ic_sym_sunny) { pickingNight = false }
        }
        SettingsGroup(stringResource(R.string.sensorend_countdown_group)) {
            switch(stringResource(R.string.sensorend_countdown_switch), c.countdown, icon = R.drawable.ic_sym_timer, summary = stringResource(R.string.sensorend_countdown_summary)) { v -> change { it.copy(countdown = v) } }
            value(stringResource(R.string.sensorend_countdown_hours), pluralStringResource(R.plurals.sensorend_hours_before, hours, hours),
                enabled = c.countdown, icon = R.drawable.ic_sym_schedule) { choosingHours = true }
            switch(stringResource(R.string.sensorend_warmup_switch), c.warmup, icon = R.drawable.ic_sym_hourglass_top, summary = stringResource(R.string.sensorend_warmup_summary)) { v -> change { it.copy(warmup = v) } }
        }
        SettingsGroup { calendar() }   // sensor end in calendar (features/SensorCalendar)
        SettingsFooter(stringResource(R.string.sensorend_footer))
    }
    editing?.let { i ->
        ReminderDialog(c.reminders.getOrNull(i), onClose = { editing = null }, onRemove = { change { it.copy(reminders = it.reminders - it.reminders[i]) } }) { r ->
            change { cfg -> cfg.copy(reminders = if (i < 0) cfg.reminders + r else cfg.reminders.mapIndexed { j, o -> if (j == i) r else o }) }
        }
    }
    if (choosingHours) IntNumberDialog(stringResource(R.string.sensorend_countdown_hours), app.mauri.sukrara.settings.SettingsSpec.COUNTDOWN_HOURS, hours,
        { ctx.resources.getQuantityString(R.plurals.sensorend_hours_before, it, it) },
        onClose = { choosingHours = false }) { SensorEnd.setCountdownHours(ctx, it) }
    pickingNight?.let { bed ->
        TimeDialog(stringResource(if (bed) R.string.sensorend_bedtime else R.string.sensorend_wakeup),
            if (bed) c.nightStart else c.nightEnd, onClose = { pickingNight = null }) { m ->
            // the window needs two different times: the same time as its other end is ignored
            change { cfg -> when {
                bed && m != cfg.nightEnd -> cfg.copy(nightStart = m)
                !bed && m != cfg.nightStart -> cfg.copy(nightEnd = m)
                else -> cfg
            } }
        }
    }
}

/** The M3 time picker in a dialog (a reminder's time, bedtime, wake-up). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(title: String, minute: Int, onClose: () -> Unit, onPick: (Int) -> Unit) {
    val state = rememberTimePickerState(minute / 60, minute % 60)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = { Button(onClick = { onPick(state.hour * 60 + state.minute); onClose() }) { Text(stringResource(R.string.settings_dialog_ok)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    )
}

/** A reminder's dialog: days before (radio buttons), the time (a row that opens the time picker), Remove / Cancel / Save. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderDialog(r: Reminder?, onClose: () -> Unit, onRemove: () -> Unit, onSave: (Reminder) -> Unit) {
    val ctx = LocalContext.current
    var days by rememberSaveable { mutableStateOf(r?.daysBefore ?: 1) }
    var minute by rememberSaveable { mutableStateOf(r?.minuteOfDay ?: (9 * 60)) }
    var picking by remember { mutableStateOf(false) }
    SettingsDialog(title = stringResource(if (r == null) R.string.sensorend_reminder_new else R.string.sensorend_reminder_edit), onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Column(Modifier.selectableGroup()) {
                for (d in 0..Reminder.MAX_DAYS) Radio(reminderDays(d), null, d == days, true, null) { days = d }
            }
            HorizontalDivider(Modifier.padding(horizontal = SettingsDimension.dialogItemPaddingHorizontal, vertical = SettingsSpace.extraSmall4),
                color = MaterialTheme.colorScheme.divider)
            Column(Modifier.fillMaxWidth().clickable { picking = true }.padding(SettingsDimension.dialogItemPadding)) {
                SettingsDialogItem(text = stringResource(R.string.sensorend_time))
                SettingsBody(body = minuteText(ctx, minute))
            }
            Row(Modifier.fillMaxWidth().padding(SettingsDimension.dialogItemPadding), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4, Alignment.End)) {
                if (r != null) TextButton(onClick = { onRemove(); onClose() }, modifier = Modifier.weight(1f, fill = false)) { Text(stringResource(R.string.sensorend_remove)) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4, Alignment.End)) {
                    OutlinedButton(onClick = onClose) { Text(stringResource(R.string.settings_dialog_cancel)) }
                    Button(onClick = { onSave(Reminder(days, minute)); onClose() }) { Text(stringResource(R.string.sensorend_save)) }
                }
            }
        }
    }
    if (picking) TimeDialog(stringResource(R.string.sensorend_time), minute, onClose = { picking = false }) { minute = it }
}
