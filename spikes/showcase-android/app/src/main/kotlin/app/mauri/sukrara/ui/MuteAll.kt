// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.alerts.AlertEngine
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.settings.SettingsSpec

/*
 * Mute all (round f30; the user, 2026-10-21: a global mute with Never), this device only. The home screen's control
 * ([MuteAllControl]) and Settings › Alerts › Active now ([MuteAllState]) show the state with its ways out; the card
 * carries them as TalkBack actions ([muteAllA11yActions]), the badge as the neutral "muted" pill (StatusBadge).
 *
 * Guidance: the lengths are AOSP's Do Not Disturb duration choice (Settings › Do Not Disturb › Duration: "Until you turn
 * off" next to fixed lengths), offered through THE number input ([IntNumberDialog] with SettingsSpec.MUTE_ALL: 30 min …
 * 8 h, Never, Custom). Buttons by emphasis (developer.android.com/develop/ui/compose/components/button): "Outlined —
 * medium-emphasis buttons, containing actions that are important but not primary" for Mute alerts beside the filled
 * tonal Add treatment; "Filled — high-emphasis buttons … for primary actions" for Turn alerts back on, the way out
 * that must be easy to find on the state's tonal surface; "Text — low-emphasis … secondary functions" for Change. The
 * state is a tonal surface in the neutral secondary colours, never the error colour (red only for errors). TalkBack: "Custom actions can be used
 * for more complex touchscreen gestures" (developer.android.com/develop/ui/compose/accessibility/semantics).
 */

/** "Alerts muted until 14:30" / "Alerts muted" (Never) for Mute all ending at [until]. */
fun muteAllTitle(ctx: Context, until: Long): String =
    if (until == AlertEngine.NEVER) ctx.getString(R.string.mute_all_on) else ctx.getString(R.string.mute_all_until, Clock.time(until))

/** The Mute all lengths: THE number input with the schema's spec; Never has its "Until I turn them back on" line. */
@Composable
fun MuteAllDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    IntNumberDialog(stringResource(R.string.mute_all_title), SettingsSpec.MUTE_ALL, SettingsSpec.MUTE_ALL.default.toInt(),
        { m -> if (m == Alerts.NEVER_MIN) ctx.getString(R.string.mute_all_never) else Durations.text(m) }, onClose,
        sub = { m -> if (m == Alerts.NEVER_MIN) ctx.getString(R.string.mute_all_never_summary) else null }) { m -> Alerts.muteAll(ctx, m) }
}

/**
 * The home screen's control: the "Mute alerts" pill while alerts ring, the muted state with Turn alerts back on and
 * Change while Mute all is on. [onChange] opens [MuteAllDialog] (hoisted so the card's TalkBack action opens it too).
 */
@Composable
fun MuteAllControl(onChange: () -> Unit, modifier: Modifier = Modifier) {
    val all by Alerts.allMuted.collectAsState()
    if (all == 0L) OutlinedButton(onClick = onChange, modifier = modifier.fillMaxWidth()) {
        Icon(painterResource(R.drawable.ic_sym_notifications_off), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
        Text(stringResource(R.string.mute_all_button))
    } else MuteAllState(all, onChange, modifier)
}

/** Mute all is on until [until]: what it is, Turn alerts back on, Change (the home and Active now). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuteAllState(until: Long, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sym_notifications_off), contentDescription = null, modifier = Modifier.padding(end = 12.dp).size(20.dp))
                Text(muteAllTitle(ctx, until), style = MaterialTheme.typography.titleMedium)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { Alerts.unmuteAll(ctx) }) { Text(stringResource(R.string.mute_all_turn_on)) }
                TextButton(onClick = onChange) { Text(stringResource(R.string.mute_all_change)) }
            }
        }
    }
}

/** The card's TalkBack actions for Mute all: "Alerts: Turn back on" and "Alerts: Change mute" while it is on. */
fun muteAllA11yActions(ctx: Context, until: Long, onChange: () -> Unit): List<CustomAccessibilityAction> =
    if (until == 0L) emptyList() else listOf(
        CustomAccessibilityAction(ctx.getString(R.string.mute_all_a11y_on)) { Alerts.unmuteAll(ctx); true },
        CustomAccessibilityAction(ctx.getString(R.string.mute_all_a11y_change)) { onChange(); true },
    )
