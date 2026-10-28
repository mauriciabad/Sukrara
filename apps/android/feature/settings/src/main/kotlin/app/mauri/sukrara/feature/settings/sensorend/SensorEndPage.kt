// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.sensorend

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.data.settings.SurfaceSettings
import app.mauri.sukrara.core.model.SensorEndConfig
import app.mauri.sukrara.core.model.SensorReminder
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SchemaPrefs
import app.mauri.sukrara.feature.settings.SettingsPage
import app.mauri.sukrara.graph.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import app.mauri.sukrara.core.designsystem.R as DsR

/** Everything Settings › Sensor end shows. [sensor] = the wearer's sensor as the hub knows it. */
data class SensorEndPrefs(
    val config: SensorEndConfig,
    val countdownHours: Int,
    val sensor: SensorState,
)

class SensorEndViewModel(
    private val settings: SettingsRepository,
    sensor: Flow<SensorState>,
) : ViewModel() {
    val prefs: StateFlow<SensorEndPrefs?> =
        combine(SurfaceSettings.sensorEnd(settings), settings.flow(SurfaceSettings.CountdownHours), sensor) { c, h, s ->
            SensorEndPrefs(c, h.toInt(), s)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Applies [transform] to the stored settings (never to a stale copy). */
    fun change(transform: (SensorEndConfig) -> SensorEndConfig) {
        viewModelScope.launch {
            val now = SurfaceSettings.sensorEnd(settings).first()
            SurfaceSettings.setSensorEnd(settings, transform(now).normalized())
        }
    }

    fun setCountdownHours(hours: Double) {
        viewModelScope.launch { settings.set(SurfaceSettings.CountdownHours, hours) }
    }
}

/** The page's actions. */
class SensorEndActions(
    val change: ((SensorEndConfig) -> SensorEndConfig) -> Unit = {},
    val setCountdownHours: (Double) -> Unit = {},
)

/** "On the end day" / "1 day before". */
@Composable
fun reminderDays(d: Int): String =
    if (d == 0) stringResource(R.string.st_sensorend_end_day) else pluralStringResource(R.plurals.st_sensorend_days_before, d, d)

/**
 * Settings › Sensor end (the wearer): this sensor's end, the reminders (rows; a row opens its dialog with Remove,
 * *Add reminder* below the list up to six), first-unlock delivery, the night (evening reminder, bedtime, wake-up:
 * Android doesn't share its Bedtime schedule), the live countdowns (end countdown and its hours, warm-up) and the
 * sensor's end in the calendar ([calendar]: null = not offered).
 * [countdownReadOnly] = a follower not in Custom: the countdown's hours are mirrored (*Settings from*), shown in use
 * under [countdownNote] (why, and the way to change it).
 */
@Composable
fun SensorEndContent(
    prefs: SensorEndPrefs,
    now: Instant,
    actions: SensorEndActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    calendar: Pair<SensorCalendarState, SensorCalendarPort>? = null,
    countdownReadOnly: Boolean = false,
    countdownNote: @Composable () -> Unit = {},
) {
    val c = prefs.config
    // null = closed; -1 = a new reminder
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    var choosingHours by rememberSaveable { mutableStateOf(false) }
    // the night's time picker: true = bedtime, false = wake-up, null = closed
    var pickingNight by rememberSaveable { mutableStateOf<Boolean?>(null) }
    SettingsPage(stringResource(R.string.st_sensorend_title), onBack, modifier) {
        SettingsGroup {
            InfoRow(
                stringResource(R.string.st_sensorend_current),
                summary = currentSensor(prefs.sensor, now),
                icon = DsR.drawable.ds_ic_dia_sensor,
            )
        }
        SettingsGroup(title = stringResource(R.string.st_sensorend_reminders)) {
            if (c.reminders.isEmpty()) InfoRow(stringResource(R.string.st_sensorend_no_reminders), icon = DsR.drawable.ds_ic_calendar_month)
            c.reminders.forEachIndexed { i, r ->
                ValueRow(
                    reminderDays(r.daysBefore),
                    Clock.minuteOfDay(r.minuteOfDay),
                    { editing = i },
                    icon = DsR.drawable.ds_ic_calendar_month,
                )
            }
        }
        if (c.reminders.size < SensorEndConfig.MAX_REMINDERS) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                OutlinedButton(onClick = { editing = -1 }) { Text(stringResource(R.string.st_sensorend_add)) }
            }
        }
        SettingsGroup {
            SwitchRow(
                stringResource(R.string.st_sensorend_unlock),
                c.unlock,
                { v -> actions.change { it.copy(unlock = v) } },
                summary = stringResource(R.string.st_sensorend_unlock_summary),
                icon = DsR.drawable.ds_ic_lock_open,
            )
        }
        SettingsGroup(title = stringResource(R.string.st_sensorend_night)) {
            val evening = (c.nightStart - EVENING_BEFORE_MIN + SensorReminder.MINUTES_PER_DAY) % SensorReminder.MINUTES_PER_DAY
            SwitchRow(
                stringResource(R.string.st_sensorend_evening),
                c.evening,
                { v -> actions.change { it.copy(evening = v) } },
                summary = stringResource(R.string.st_sensorend_evening_summary, Clock.minuteOfDay(evening)),
                icon = DsR.drawable.ds_ic_bedtime,
            )
            ValueRow(
                stringResource(R.string.st_sensorend_bedtime),
                Clock.minuteOfDay(c.nightStart),
                { pickingNight = true },
                enabled = c.evening,
                icon = DsR.drawable.ds_ic_bedtime,
            )
            ValueRow(
                stringResource(R.string.st_sensorend_wakeup),
                Clock.minuteOfDay(c.nightEnd),
                { pickingNight = false },
                enabled = c.evening,
                icon = DsR.drawable.ds_ic_sunny,
            )
        }
        if (countdownReadOnly) countdownNote()
        SettingsGroup(title = stringResource(R.string.st_sensorend_countdowns)) {
            SwitchRow(
                stringResource(R.string.st_sensorend_countdown),
                c.countdown,
                { v -> actions.change { it.copy(countdown = v) } },
                summary = stringResource(R.string.st_sensorend_countdown_summary),
                icon = DsR.drawable.ds_ic_timelapse,
            )
            ValueRow(
                stringResource(R.string.st_sensorend_countdown_hours),
                pluralStringResource(R.plurals.st_sensorend_hours_before, prefs.countdownHours, prefs.countdownHours),
                { choosingHours = true },
                enabled = c.countdown,
                icon = DsR.drawable.ds_ic_hourglass_top,
                readOnly = countdownReadOnly,
            )

            SwitchRow(
                stringResource(R.string.st_sensorend_warmup),
                c.warmup,
                { v -> actions.change { it.copy(warmup = v) } },
                summary = stringResource(R.string.st_sensorend_warmup_summary),
                icon = DsR.drawable.ds_ic_hourglass_top,
            )
        }
        calendar?.let { (state, port) -> SensorCalendarGroup(state, port) }
        FooterText(stringResource(R.string.st_sensorend_footer))
    }
    editing?.let { i ->
        ReminderDialog(
            c.reminders.getOrNull(i),
            onClose = { editing = null },
            onRemove = { actions.change { cfg -> cfg.copy(reminders = cfg.reminders.filterIndexed { j, _ -> j != i }) } },
        ) { r ->
            actions.change { cfg ->
                cfg.copy(reminders = if (i < 0) cfg.reminders + r else cfg.reminders.mapIndexed { j, o -> if (j == i) r else o })
            }
        }
    }
    if (choosingHours) {
        val spec = SchemaPrefs.spec("sensor.countdownHours")
        val res = LocalContext.current.resources
        NumberDialog(
            stringResource(R.string.st_sensorend_countdown_hours),
            NumberInput(spec),
            prefs.countdownHours.toDouble(),
            { res.getQuantityString(R.plurals.st_sensorend_hours_before, it.toInt(), it.toInt()) },
            { choosingHours = false },
        ) { actions.setCountdownHours(it) }
    }
    pickingNight?.let { bed ->
        TimeDialog(
            stringResource(if (bed) R.string.st_sensorend_bedtime else R.string.st_sensorend_wakeup),
            if (bed) c.nightStart else c.nightEnd,
            onClose = { pickingNight = null },
        ) { m ->
            // the night needs two different times: the other end's time is ignored
            actions.change { cfg ->
                when {
                    bed && m != cfg.nightEnd -> cfg.copy(nightStart = m)
                    !bed && m != cfg.nightStart -> cfg.copy(nightEnd = m)
                    else -> cfg
                }
            }
        }
    }
}

/** The evening reminder comes this long before bedtime (the planner's rule). */
private const val EVENING_BEFORE_MIN = 2 * 60

/** "Ends 17 Jan 2026, 14:30" / "Ended …" / "Warming up, readings at 19:45" / "No sensor yet". */
@Composable
private fun currentSensor(
    sensor: SensorState,
    now: Instant,
): String =
    when (sensor) {
        is SensorState.Active -> {
            sensor.endsAt?.let { end ->
                val at = Clock.dateTime(end.epochSecond)
                if (end <=
                    now
                ) {
                    stringResource(R.string.st_sensorend_current_ended, at)
                } else {
                    stringResource(R.string.st_sensorend_current_ends, at)
                }
            } ?: stringResource(R.string.st_sensorend_current_unknown)
        }

        is SensorState.WarmingUp -> {
            stringResource(R.string.st_sensorend_current_warming, Clock.time(sensor.readyAt.epochSecond))
        }

        SensorState.Ended -> {
            stringResource(R.string.st_sensorend_current_ended_now)
        }

        SensorState.None -> {
            stringResource(R.string.st_sensorend_current_none)
        }

        else -> {
            stringResource(R.string.st_sensorend_current_unknown)
        }
    }

/** The M3 time picker in a dialog (a reminder's time, bedtime, wake-up). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    minute: Int,
    onClose: () -> Unit,
    onPick: (Int) -> Unit,
) {
    val state = rememberTimePickerState(minute / 60, minute % 60, is24Hour = Clock.hour24)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = {
                onPick(state.hour * 60 + state.minute)
                onClose()
            }) { Text(stringResource(R.string.st_sensorend_ok)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.st_sensorend_cancel)) } },
    )
}

/** A reminder's dialog: days before (radio buttons), its time (opens the time picker), Remove / Cancel / Save. */
@Composable
private fun ReminderDialog(
    r: SensorReminder?,
    onClose: () -> Unit,
    onRemove: () -> Unit,
    onSave: (SensorReminder) -> Unit,
) {
    var days by rememberSaveable { mutableStateOf(r?.daysBefore ?: 1) }
    var minute by rememberSaveable { mutableStateOf(r?.minuteOfDay ?: (9 * 60)) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (r == null) R.string.st_sensorend_reminder_new else R.string.st_sensorend_reminder_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Column(Modifier.selectableGroup()) {
                    for (d in 0..SensorReminder.MAX_DAYS) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(d == days, role = Role.RadioButton) { days = d }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = d == days, onClick = null)
                            Text(reminderDays(d), Modifier.padding(start = 16.dp))
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { picking = true }.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.st_sensorend_time), style = MaterialTheme.typography.bodyLarge)
                    Text(Clock.minuteOfDay(minute), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(SensorReminder(days, minute))
                onClose()
            }) { Text(stringResource(R.string.st_sensorend_save)) }
        },
        dismissButton = {
            Row {
                if (r != null) {
                    TextButton(onClick = {
                        onRemove()
                        onClose()
                    }) { Text(stringResource(R.string.st_sensorend_remove), color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onClose) { Text(stringResource(R.string.st_sensorend_cancel)) }
            }
        },
    )
    if (picking) TimeDialog(stringResource(R.string.st_sensorend_time), minute, onClose = { picking = false }) { minute = it }
}
