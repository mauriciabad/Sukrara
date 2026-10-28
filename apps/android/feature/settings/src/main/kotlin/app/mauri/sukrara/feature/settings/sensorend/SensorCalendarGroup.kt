// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.sensorend

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.SwitchRow
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import app.mauri.sukrara.core.designsystem.R as DsR

/**
 * *Sensor end in calendar* (the wearer's phone): an event when each sensor ends, in Sukrara's own calendar or one the
 * person picks. The app writes the events; this is the switch and the choice.
 */
interface SensorCalendarPort {
    /** null = not offered here (a follower has no sensor). */
    val state: Flow<SensorCalendarState?>

    /** The runtime permissions the switch asks for. */
    val permissions: Array<String> get() = emptyArray()

    fun granted(): Boolean = false

    /** On (with the permissions granted: writes the current sensor's event) or off (removes what Sukrara wrote). */
    fun setOn(on: Boolean) = Unit

    /** The calendars the person may pick (needs the permissions). */
    suspend fun choices(): List<CalendarChoice> = emptyList()

    /** Moves the events to [choice] (null = Sukrara's own calendar). */
    fun choose(choice: CalendarChoice?) = Unit

    /** The page is shown again: a permission revoked in Android's settings turns the setting off. */
    fun recheck() = Unit

    object None : SensorCalendarPort {
        override val state: Flow<SensorCalendarState?> = flowOf(null)
    }
}

/** [on]; where the events go ([own] = Sukrara's calendar, else the picked one's [name] and [id]); [googleCalendar] installed. */
data class SensorCalendarState(
    val on: Boolean,
    val own: Boolean = true,
    val name: String = "",
    val id: Long = -1,
    val googleCalendar: Boolean = false,
)

/** A calendar on the phone the events can go to; [synced] = its account syncs to the person's other devices. */
data class CalendarChoice(
    val id: Long,
    val name: String,
    val account: String,
    val synced: Boolean,
)

/** Google Calendar's package (declared in the app manifest's `<queries>`). */
private const val GOOGLE_CALENDAR = "com.google.android.calendar"

/**
 * The page's *Calendar* group. Turning it on asks for the calendar permissions (the system dialog); refused → stays
 * off and says why; refused for good (Android no longer asks) → the next tap opens Sukrara's page in Android's
 * settings, and coming back re-reads it. *Calendar* picks where the events go. With Sukrara's own calendar and Google
 * Calendar installed, one more row says where to turn the calendar on there (Google Calendar hides it until then) and
 * opens Google Calendar.
 */
@Composable
fun SensorCalendarGroup(
    state: SensorCalendarState,
    port: SensorCalendarPort,
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    var denied by rememberSaveable { mutableStateOf(false) }
    var blocked by rememberSaveable { mutableStateOf(false) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(port) {
        port.recheck()
        if (port.granted()) {
            denied = false
            blocked = false
        }
        onPauseOrDispose {}
    }
    val ask =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.isNotEmpty() && result.values.all { it }) {
                denied = false
                blocked = false
                port.setOn(true)
            } else {
                denied = true
                blocked = activity != null && port.permissions.none { activity.shouldShowRequestPermissionRationale(it) }
            }
        }
    val where = if (state.own) stringResource(R.string.st_calendar_own) else state.name
    SettingsGroup(title = stringResource(R.string.st_calendar_group)) {
        SwitchRow(
            stringResource(R.string.st_calendar_title),
            state.on,
            { on ->
                when {
                    !on -> {
                        port.setOn(false)
                    }

                    port.granted() -> {
                        port.setOn(true)
                    }

                    blocked -> {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                        )
                    }

                    else -> {
                        ask.launch(port.permissions)
                    }
                }
            },
            summary =
                when {
                    state.on -> stringResource(R.string.st_calendar_on, where)
                    blocked -> stringResource(R.string.st_calendar_blocked)
                    denied -> stringResource(R.string.st_calendar_denied)
                    else -> stringResource(R.string.st_calendar_off)
                },
            icon = DsR.drawable.ds_ic_calendar_month,
        )
        ValueRow(
            stringResource(R.string.st_calendar_where),
            where,
            { choosing = true },
            enabled = state.on,
            icon = DsR.drawable.ds_ic_edit_calendar,
        )
        if (state.on && state.own && state.googleCalendar) {
            val open = remember { context.packageManager.getLaunchIntentForPackage(GOOGLE_CALENDAR) }
            NavigationRow(
                stringResource(R.string.st_calendar_gcal_title),
                { open?.let(context::startActivity) },
                summary = stringResource(R.string.st_calendar_gcal_hint),
                icon = DsR.drawable.ds_ic_open_in_new,
            )
        }
    }
    if (choosing) CalendarDialog(state, port) { choosing = false }
}

/** Sukrara's own calendar first, then every writable calendar on the phone; picking one moves the events there. */
@Composable
private fun CalendarDialog(
    state: SensorCalendarState,
    port: SensorCalendarPort,
    onClose: () -> Unit,
) {
    val choices by produceState(emptyList<CalendarChoice>()) { value = port.choices() }
    val ownName = stringResource(R.string.st_calendar_own)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.st_calendar_where)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).selectableGroup()) {
                CalendarOption(ownName, stringResource(R.string.st_calendar_own_sub), state.own) {
                    port.choose(null)
                    onClose()
                }
                for (c in choices) {
                    val sub = if (c.synced) stringResource(R.string.st_calendar_picked_sub, c.account) else c.account
                    CalendarOption(c.name, sub, !state.own && c.id == state.id) {
                        port.choose(c)
                        onClose()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.st_cancel)) } },
    )
}

@Composable
private fun CalendarOption(
    name: String,
    sub: String,
    selected: Boolean,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected, role = Role.RadioButton, onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 16.dp)) {
            Text(name)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
