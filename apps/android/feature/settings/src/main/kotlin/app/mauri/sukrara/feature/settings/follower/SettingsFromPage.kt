// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.follower

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.core.data.settings.SettingsFrom
import app.mauri.sukrara.core.model.Clock
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import app.mauri.sukrara.graph.Clock as ClockText

/**
 * A follower's *Settings from* (null [state] = this phone isn't a follower: nothing is offered). [choose] stores the
 * choice; [copyWearer] writes the wearer's mirrored values into this phone's own (false: none received yet);
 * [resetToDefault] writes the defaults into them. The alert runtime and every card follow at once. [alertsInUse] = the
 * alert settings the alert runtime runs (the mirrored rows per the choice over this phone's own), null while this
 * phone's own are used (Custom, or not a follower).
 */
interface SettingsFromPort {
    val state: Flow<SettingsFromState?>

    val alertsInUse: Flow<AlertSettings?> get() = flowOf(null)

    suspend fun choose(from: SettingsFrom)

    suspend fun copyWearer(): Boolean

    suspend fun resetToDefault()

    object None : SettingsFromPort {
        override val state: Flow<SettingsFromState?> = flowOf(null)

        override suspend fun choose(from: SettingsFrom) = Unit

        override suspend fun copyWearer() = false

        override suspend fun resetToDefault() = Unit
    }
}

/** The choice, the wearer's name (null = unknown) and when they last changed their settings (null = none received). */
data class SettingsFromState(
    val from: SettingsFrom,
    val wearerName: String?,
    val wearerChangedAt: Instant?,
)

/**
 * A follower not in Custom: the mirrored rows (Alerts, Ranges, Graph, the sensor countdown) show the values in use and
 * can't be changed here. Null (not a follower) never is.
 */
val SettingsFromState?.readOnly: Boolean get() = this != null && from != SettingsFrom.Custom

class SettingsFromViewModel(
    private val port: SettingsFromPort,
) : ViewModel() {
    val state = port.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun choose(from: SettingsFrom) {
        viewModelScope.launch { port.choose(from) }
    }

    fun copyWearer(done: (Boolean) -> Unit) {
        viewModelScope.launch { done(port.copyWearer()) }
    }

    fun resetToDefault(done: () -> Unit) {
        viewModelScope.launch {
            port.resetToDefault()
            done()
        }
    }
}

class SettingsFromActions(
    val choose: (SettingsFrom) -> Unit = {},
    val copyWearer: ((Boolean) -> Unit) -> Unit = {},
    val resetToDefault: (() -> Unit) -> Unit = {},
)

/** "Ana's settings" / "Default" / "Custom" (the overview row's summary and the other pages' note). */
@Composable
fun settingsFromLabel(s: SettingsFromState): String =
    when (s.from) {
        SettingsFrom.Wearer -> {
            s.wearerName?.let { stringResource(R.string.st_from_wearer_named, it) }
                ?: stringResource(R.string.st_from_wearer)
        }

        SettingsFrom.Default -> {
            stringResource(R.string.st_from_default)
        }

        SettingsFrom.Custom -> {
            stringResource(R.string.st_from_custom)
        }
    }

/**
 * Settings › Settings from (followers): where the ranges, the graph, the sensor countdown and the alert rules come
 * from. Every choice is one tap away from the others; in Custom, *Copy the wearer's settings* and *Reset to default*
 * (each after a confirmation: they replace this phone's own values) start the own values from somewhere.
 */
@Composable
fun SettingsFromContent(
    state: SettingsFromState,
    clock: Clock,
    actions: SettingsFromActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var confirm by rememberSaveable { mutableStateOf<SettingsFrom?>(null) }
    val name = state.wearerName
    val copiedText = stringResource(R.string.st_from_copied)
    val resetText = stringResource(R.string.st_from_reset_done)
    val nothingText = stringResource(R.string.st_from_nothing_yet)
    SettingsPage(stringResource(R.string.st_from_title), onBack, modifier, snackbar = snackbar) {
        SettingsGroup(Modifier.selectableGroup(), title = stringResource(R.string.st_from_header)) {
            for (from in SettingsFrom.entries) {
                val selected = from == state.from
                CardRow {
                    Row(
                        Modifier.selectable(selected, role = Role.RadioButton) {
                            if (!selected) {
                                haptics.toggle(true)
                                actions.choose(from)
                            }
                        },
                    ) {
                        RadioButton(selected = selected, onClick = null)
                        Column(Modifier.padding(start = 16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(settingsFromLabel(state.copy(from = from)), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when (from) {
                                    SettingsFrom.Wearer -> stringResource(R.string.st_from_wearer_summary)
                                    SettingsFrom.Default -> stringResource(R.string.st_from_default_summary)
                                    SettingsFrom.Custom -> stringResource(R.string.st_from_custom_summary)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
        if (state.from == SettingsFrom.Wearer) {
            SettingsGroup {
                InfoRow(
                    state.wearerChangedAt?.let {
                        stringResource(
                            R.string.st_from_updated,
                            ClockText.dayTime(it.epochSecond, zone = clock.zone),
                        )
                    }
                        ?: stringResource(R.string.st_from_waiting),
                    summary = if (state.wearerChangedAt == null) stringResource(R.string.st_from_waiting_summary) else null,
                )
            }
        }
        if (state.from == SettingsFrom.Custom) {
            SettingsGroup(title = stringResource(R.string.st_from_start_from)) {
                ValueRow(
                    name?.let { stringResource(R.string.st_from_copy_named, it) } ?: stringResource(R.string.st_from_copy),
                    if (state.wearerChangedAt == null) nothingText else stringResource(R.string.st_from_copy_summary),
                    { confirm = SettingsFrom.Wearer },
                    enabled = state.wearerChangedAt != null,
                )
                ValueRow(stringResource(R.string.st_from_reset), stringResource(R.string.st_from_reset_summary), {
                    confirm = SettingsFrom.Default
                })
            }
        }
        FooterText(stringResource(R.string.st_from_footer))
    }
    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = {
                Text(
                    if (what == SettingsFrom.Wearer) {
                        name?.let { stringResource(R.string.st_from_copy_named, it) } ?: stringResource(R.string.st_from_copy)
                    } else {
                        stringResource(R.string.st_from_reset)
                    },
                )
            },
            text = { Text(stringResource(R.string.st_from_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    haptics.confirm()
                    val show: (String) -> Unit = { text ->
                        scope.launch { snackbar.showSnackbar(text, withDismissAction = true, duration = SnackbarDuration.Short) }
                    }
                    if (what == SettingsFrom.Wearer) {
                        actions.copyWearer { ok -> show(if (ok) copiedText else nothingText) }
                    } else {
                        actions.resetToDefault { show(resetText) }
                    }
                }) { Text(stringResource(R.string.st_from_replace)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.st_cancel)) } },
        )
    }
}

/**
 * On Alerts, Ranges and Graph of a follower that doesn't use its own values: where the values in use come from, and the
 * way to change it (the page's own values are kept for Custom); then [preview] (the live card of Ranges and Graph).
 */
@Composable
fun SettingsFromNote(
    state: SettingsFromState?,
    open: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = {},
) {
    Column(modifier) {
        if (state != null && state.from != SettingsFrom.Custom) {
            SettingsGroup {
                NavigationRow(
                    stringResource(R.string.st_from_note, settingsFromLabel(state)),
                    open,
                    summary = stringResource(R.string.st_from_note_summary),
                )
            }
        }
        preview()
    }
}
