// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.alerts.AlertEngine
import app.mauri.sukrara.alerts.AlertNotifier
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.alerts.MuteCopy
import app.mauri.sukrara.core.Durations

/*
 * The ways out of a quiet alert (round f29; user, 2026-10-21: "There's no way to unmute! On any platform"), shown
 * wherever the state is: the home card's "Xm left" pill opens [AlertQuietSheet], the Alerts page lists them under
 * Active now ([QuietRow]), and the card carries them as accessibility actions ([quietA11yActions]), so a TalkBack user
 * reaches every one without the notification. Material: a modal bottom sheet for a short set of actions about one
 * thing (m3.material.io/components/bottom-sheets/guidelines: "Use bottom sheets … to show … actions related to the
 * current content"), the actions as tonal buttons like the card's action pills (memory: actions are pills).
 * What each does is SPEC §2 "States and exits"; every one acts on this phone only, like the notification's buttons.
 */

/** The sheet's title / Active now's line for [a], in the app's language. */
fun quietTitle(ctx: Context, a: Alerts.Active, now: Long): String {
    val name = AlertNotifier.label(ctx, a.kind)
    val time = { app.mauri.sukrara.graph.Clock.time(a.until) }
    return when (a.quiet) {
        AlertEngine.QUIET_NOTIFICATION -> MuteCopy.sideMutedUntil(ctx, a.kind, a.until, now)
        AlertEngine.QUIET_CARBS -> ctx.getString(R.string.quiet_title_carbs, name, time())
        AlertEngine.QUIET_INSULIN -> ctx.getString(R.string.quiet_title_insulin, name, time())
        AlertEngine.QUIET_FALLING -> ctx.getString(R.string.quiet_title_falling, name)
        else -> MuteCopy.sideLess(ctx, a.kind, a.relaxMin)
    }
}

/** The second line when it also reminds less ("Low reminders every 30 min"), or null. */
private fun relaxedLine(ctx: Context, a: Alerts.Active): String? =
    if (a.quiet != null && a.relaxMin > 0) MuteCopy.sideLess(ctx, a.kind, a.relaxMin) else null

/** The main way out of [a]: Unmute (a notification mute) or Alert me anyway (a treatment / falling mute here). */
private fun lift(a: Alerts.Active): Int? = when {
    a.quiet == AlertEngine.QUIET_NOTIFICATION -> R.string.quiet_unmute
    a.quiet != null && !a.mirrored -> R.string.quiet_alert_anyway
    else -> null
}

/** [a]'s ways out as TalkBack actions on the card (names say which alert: "Low: Unmute"). */
fun quietA11yActions(ctx: Context, list: List<Alerts.Active>): List<CustomAccessibilityAction> = list.flatMap { a ->
    val name = AlertNotifier.label(ctx, a.kind)
    listOfNotNull(
        lift(a)?.let { r -> CustomAccessibilityAction(ctx.getString(R.string.quiet_a11y_action, name, ctx.getString(r))) { Alerts.unmute(ctx, a.kind); true } },
        if (a.relaxMin > 0) CustomAccessibilityAction(ctx.getString(R.string.quiet_a11y_action, name, ctx.getString(R.string.alert_action_remind_usual))) {
            Alerts.unrelax(ctx, a.kind); true } else null,
    )
}

/**
 * One quiet alert with its ways out: the title (ticking down, round f31), Unmute / Alert me anyway, Remind as usual /
 * Remind less, and the mute lengths
 * (Change to … while muted, Mute for … otherwise; every kind, very low and extremely high included, round f30).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuietRow(a: Alerts.Active, now: Long) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(quietTitle(ctx, a, now), style = MaterialTheme.typography.bodyLarge)
        relaxedLine(ctx, a)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            lift(a)?.let { r -> FilledTonalButton(onClick = { Alerts.unmute(ctx, a.kind) }) { Text(stringResource(r)) } }
            if (a.relaxMin > 0) FilledTonalButton(onClick = { Alerts.unrelax(ctx, a.kind) }) { Text(stringResource(R.string.alert_action_remind_usual)) }
            else if (a.canRelax) OutlinedButton(onClick = { Alerts.relax(ctx, a.kind) }) { Text(stringResource(R.string.alert_action_remind_less)) }
        }
        if (a.canMute) {
            Text(stringResource(if (a.quiet == AlertEngine.QUIET_NOTIFICATION) R.string.quiet_change else R.string.quiet_mute_for),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in AlertSettings.MUTE_PRESETS) OutlinedButton(onClick = { Alerts.mute(ctx, a.kind, m) }) { Text(Durations.text(m)) }
            }
        }
    }
}

/**
 * The home card's pill tapped (or its slowed chip): the quiet alerts of [kind]'s side with their ways out. A follower
 * that mirrors the wearer and is quiet after a treatment can't lift it here (the wearer's phone decides, SPEC §2): it
 * says so and links to the Alerts page (Settings from Default / Custom run the rules on this phone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlertQuietSheet(kind: String, onDismiss: () -> Unit, onSettings: (() -> Unit)?) {
    val ctx = LocalContext.current
    val list by Alerts.active.collectAsState()
    val all by Alerts.allMuted.collectAsState()
    // the lines tick down on the minute boundary from each mute's end (round f31)
    val now = nowSeconds(countdowns = list.map { it.until } + all)
    var change by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val mine = list.filter { Kind.side(it.kind) == Kind.side(kind) }
    // opened with nothing to lift here: a mirrored treatment mute (the text below); otherwise the sheet closes once
    // its last state is left (Unmute, Remind as usual, Mute alerts' Unmute, the mute's end, back in range)
    val empty = mine.isEmpty() && all == 0L
    val mirroredTreatment = androidx.compose.runtime.remember { empty }
    androidx.compose.runtime.LaunchedEffect(empty) { if (empty && !mirroredTreatment) onDismiss() }
    if (change) MuteAllDialog(onClose = { change = false })
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (all != 0L) MuteAllState(all, onChange = { change = true })
            if (mirroredTreatment && empty) Text(ctx.getString(R.string.quiet_title_mirrored_treatment, AlertNotifier.label(ctx, kind)),
                style = MaterialTheme.typography.bodyLarge)
            mine.forEach { QuietRow(it, now) }
            onSettings?.let { TextButton(onClick = { onDismiss(); it() }) { Text(stringResource(R.string.quiet_settings)) } }
        }
    }
}
