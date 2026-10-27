// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.sensor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.core.model.SensorAction
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.DangerRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.feature.settings.ConfirmDialog
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import app.mauri.sukrara.feature.settings.StateStrip
import app.mauri.sukrara.graph.CardWords
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import app.mauri.sukrara.graph.Clock as ClockText

/** Where the page's buttons lead: the sensor feature's new-sensor flow and LibreView sign-in. */
class SensorSettingsActions(
    val pairNewSensor: () -> Unit = {},
    val signIn: () -> Unit = {},
    val setSource: (SourceChoice) -> Unit = {},
    val signOut: () -> Unit = {},
)

/** Settings › Sensor with its ViewModel; the "ends in" time moves every minute. */
@Composable
fun SensorSettingsScreen(
    viewModel: SensorSettingsViewModel,
    clock: Clock,
    onPairNewSensor: () -> Unit,
    onSignIn: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val now by produceState(clock.now()) {
        while (true) {
            delay(60_000)
            value = clock.now()
        }
    }
    SensorSettingsContent(
        state,
        now,
        clock.zone,
        SensorSettingsActions(onPairNewSensor, onSignIn, viewModel::setSource, viewModel::signOut),
        onBack,
    )
}

/**
 * The current sensor and its state, the reading source (with a plain confirmation of what changes), the LibreView
 * account (sign in / out; the password is never stored) and Pair new sensor. Every problem state shows its way out.
 */
@Composable
fun SensorSettingsContent(
    state: SensorSettingsState?,
    now: Instant,
    zone: ZoneId,
    actions: SensorSettingsActions,
    onBack: () -> Unit,
) {
    SettingsPage(stringResource(R.string.st_sensor), onBack) {
        if (state == null) return@SettingsPage
        var confirming by rememberSaveable { mutableStateOf<SourceChoice?>(null) }
        StateNotice(state, actions)
        SensorGroup(state, now, zone, actions)
        SourceGroup(state.source) { confirming = it }
        AccountGroup(state.account, actions)
        FooterText(stringResource(R.string.st_sensor_footer))
        confirming?.let { target ->
            val toJuggluco = target == SourceChoice.Juggluco
            ConfirmDialog(
                title = stringResource(if (toJuggluco) R.string.st_sensor_to_juggluco_title else R.string.st_sensor_to_direct_title),
                text = stringResource(if (toJuggluco) R.string.st_sensor_to_juggluco_text else R.string.st_sensor_to_direct_text),
                confirm = stringResource(if (toJuggluco) R.string.st_sensor_to_juggluco_confirm else R.string.st_sensor_to_direct_confirm),
                onClose = { confirming = null },
                onConfirm = { actions.setSource(target) },
            )
        }
    }
}

/** A state that needs the person: what's wrong and the button that fixes it. */
@Composable
private fun StateNotice(
    state: SensorSettingsState,
    actions: SensorSettingsActions,
) {
    val pair = stringResource(R.string.st_sensor_pair_new) to actions.pairNewSensor
    when (val s = state.state) {
        SensorState.None ->
            if (state.source == SourceChoice.Libre3Direct) StateStrip(stringResource(R.string.st_sensor_none_text), listOf(pair))

        SensorState.Ended -> StateStrip(stringResource(R.string.st_sensor_ended_text), listOf(pair))

        is SensorState.ActionNeeded ->
            when (s.action) {
                SensorAction.SignInLibreView -> StateStrip(stringResource(R.string.st_sensor_sign_in_needed), listOf(stringResource(R.string.st_sensor_sign_in) to actions.signIn))
                SensorAction.StopOtherApp -> StateStrip(stringResource(R.string.st_sensor_other_app_text), listOf(pair))
                SensorAction.GrantBluetooth -> StateStrip(stringResource(R.string.st_sensor_bluetooth_permission_text), listOf(pair))
                SensorAction.ScanSensor -> StateStrip(stringResource(R.string.st_sensor_scan_again_text), listOf(pair))
            }

        else -> Unit
    }
}

@Composable
private fun SensorGroup(
    state: SensorSettingsState,
    now: Instant,
    zone: ZoneId,
    actions: SensorSettingsActions,
) {
    SettingsGroup(title = stringResource(R.string.st_sensor_current)) {
        InfoRow(stringResource(R.string.st_sensor_status), summary = statusText(state, zone))
        state.sensor?.let { sensor ->
            InfoRow(stringResource(R.string.st_sensor_serial), summary = sensor.serial)
            InfoRow(stringResource(R.string.st_sensor_started), summary = ClockText.dateTime(sensor.startedAt.epochSecond, zone = zone))
            val endsAt = ClockText.dateTime(sensor.endsAt.epochSecond, zone = zone)
            val left = Duration.between(now, sensor.endsAt).toMinutes()
            val words = CardWords.of(LocalContext.current.resources)
            InfoRow(
                stringResource(R.string.st_sensor_ends),
                summary = if (left > 0) stringResource(R.string.st_sensor_ends_in, endsAt, words.sensorLeft(left)) else endsAt,
            )
        }
        NavigationRow(
            stringResource(R.string.st_sensor_pair_new),
            actions.pairNewSensor,
            summary =
                stringResource(
                    if (state.source == SourceChoice.Juggluco) R.string.st_sensor_pair_new_juggluco else R.string.st_sensor_pair_new_summary,
                ),
        )
    }
}

/** The sensor's state in words, for the Status row. */
@Composable
private fun statusText(
    state: SensorSettingsState,
    zone: ZoneId,
): String =
    when (val s = state.state) {
        SensorState.None -> stringResource(if (state.source == SourceChoice.Juggluco) R.string.st_sensor_status_waiting_juggluco else R.string.st_sensor_status_none)
        is SensorState.WarmingUp -> stringResource(R.string.st_sensor_status_warming, ClockText.time(s.readyAt.epochSecond, zone = zone))
        is SensorState.Active -> stringResource(if (state.source == SourceChoice.Juggluco) R.string.st_sensor_status_juggluco else R.string.st_sensor_status_active)
        SensorState.Ended -> stringResource(R.string.st_sensor_status_ended)
        SensorState.CoreMissing -> stringResource(R.string.st_sensor_status_core_missing)
        SensorState.BluetoothOff -> stringResource(R.string.st_sensor_status_bluetooth_off)
        is SensorState.ActionNeeded -> stringResource(R.string.st_sensor_status_action)
    }

/** Libre 3 direct ↔ Juggluco: tapping the other one asks first ([onChoose]); the current one does nothing. */
@Composable
private fun SourceGroup(
    current: SourceChoice,
    onChoose: (SourceChoice) -> Unit,
) {
    val haptics = rememberHaptics()
    SettingsGroup(Modifier.selectableGroup(), title = stringResource(R.string.st_sensor_source)) {
        for (source in SourceChoice.entries) {
            val selected = source == current
            CardRow(
                Modifier.selectable(selected, role = Role.RadioButton) {
                    if (!selected) {
                        haptics.toggle(true)
                        onChoose(source)
                    }
                },
            ) {
                Row {
                    RadioButton(selected = selected, onClick = null)
                    Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(if (source == SourceChoice.Juggluco) R.string.st_sensor_source_juggluco else R.string.st_sensor_source_direct),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(if (source == SourceChoice.Juggluco) R.string.st_sensor_source_juggluco_why else R.string.st_sensor_source_direct_why),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountGroup(
    account: LibreViewSummary?,
    actions: SensorSettingsActions,
) {
    SettingsGroup(title = stringResource(R.string.st_sensor_libreview)) {
        if (account == null) {
            CardRow {
                Text(stringResource(R.string.st_sensor_signed_out_text), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = actions.signIn, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.st_sensor_sign_in)) }
            }
        } else {
            InfoRow(stringResource(R.string.st_sensor_signed_in_as), summary = account.email)
            account.receiverId?.let { InfoRow(stringResource(R.string.st_sensor_receiver_id), summary = it.toString()) }
            DangerRow(
                title = stringResource(R.string.st_sensor_sign_out),
                confirmTitle = stringResource(R.string.st_sensor_sign_out_title),
                confirmText = stringResource(R.string.st_sensor_sign_out_text),
                confirmAction = stringResource(R.string.st_sensor_sign_out),
                onConfirm = actions.signOut,
            )
        }
    }
}
