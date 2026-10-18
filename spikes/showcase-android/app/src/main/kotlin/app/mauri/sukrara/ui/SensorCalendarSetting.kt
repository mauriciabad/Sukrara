// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import app.mauri.sukrara.features.SensorCalendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Sensor end in calendar" rows (features/SensorCalendar): the switch and where the events go. Call it in the page's
 * composable and put the returned rows in a [SettingsGroup]. Turning it on asks for READ/WRITE_CALENDAR (runtime
 * permission, the system dialog); refused → stays off and says why; refused for good (the system no longer asks) →
 * the next tap opens the app's system settings, like AOSP Settings rows that need a permission.
 */
@Composable
fun sensorCalendarRows(): SettingsGroupScope.() -> Unit {
    val ctx = LocalContext.current
    val activity = LocalActivity.current
    // state is read here, in the page's scope; the returned rows are plain (a composable lambda would be a restart
    // scope of its own, see SettingsGroup)
    val settings = SensorCalendar.settings.collectAsState()
    var denied by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.isNotEmpty() && r.values.all { it }) { denied = false; blocked = false; SensorCalendar.enable(ctx) }
        else {
            denied = true
            blocked = activity != null && SensorCalendar.PERMISSIONS.none { activity.shouldShowRequestPermissionRationale(it) }
        }
    }
    if (choosing) {
        val choices by produceState(emptyList<SensorCalendar.Choice>()) { value = withContext(Dispatchers.IO) { SensorCalendar.choices(ctx) } }
        val options = listOf(Choice(stringResource(R.string.calendar_own), SensorCalendar.OWN, stringResource(R.string.calendar_own_sub))) +
            choices.map { Choice(it.name, it.id, if (it.synced) stringResource(R.string.calendar_picked_sub, it.account) else it.account) }
        ChoiceDialog(stringResource(R.string.calendar_where), options, settings.value.target, { choosing = false }) { id ->
            SensorCalendar.choose(ctx, id, choices.firstOrNull { it.id == id }?.name ?: "")
        }
    }
    val s = settings.value
    val on = s.on && SensorCalendar.granted(ctx)
    val where = if (s.target == SensorCalendar.OWN) stringResource(R.string.calendar_own) else s.targetName
    val summary = when {
        on -> stringResource(R.string.settings_on_with, where)
        blocked -> stringResource(R.string.calendar_summary_blocked)
        denied -> stringResource(R.string.calendar_summary_denied)
        else -> stringResource(R.string.calendar_summary_off)
    }
    val title = stringResource(R.string.calendar_title)
    val whereTitle = stringResource(R.string.calendar_where)
    return {
        switch(title, on, summary = summary) { v ->
            when {
                !v -> SensorCalendar.disable(ctx)
                SensorCalendar.granted(ctx) -> SensorCalendar.enable(ctx)
                blocked -> ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
                else -> ask.launch(SensorCalendar.PERMISSIONS)
            }
        }
        value(whereTitle, where, enabled = on) { choosing = true }
    }
}
