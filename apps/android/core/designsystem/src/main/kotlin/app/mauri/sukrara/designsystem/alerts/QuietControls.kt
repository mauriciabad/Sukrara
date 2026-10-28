// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.alerts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertLimits
import app.mauri.sukrara.alerts.MuteLine
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.designsystem.R
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import java.time.Instant

/** A way out of a quiet side, in the order the controls show them. */
enum class QuietExit {
    /** The person muted it: *Unmute*. */
    Unmute,

    /** Quiet after a treatment or while falling back into range: *Alert me anyway*. */
    AlertAnyway,

    /** It reminds less often: *Remind as usual*. */
    RemindAsUsual,
}

/**
 * Which ways out a quiet side offers (the same on home's sheet and chips, Settings › Alerts › Active now and the card's
 * TalkBack actions), so every quiet state shown has its exit everywhere it shows.
 */
object QuietExits {
    fun of(q: AlertQuietSide): List<QuietExit> =
        buildList {
            when (q.reason) {
                AlertQuietReason.Muted -> add(QuietExit.Unmute)
                AlertQuietReason.AfterCarbs, AlertQuietReason.AfterInsulin, AlertQuietReason.FallingBack -> add(QuietExit.AlertAnyway)
                null -> Unit
            }
            if (q.relaxMin > 0) add(QuietExit.RemindAsUsual)
        }

    /** The lengths a side the person muted can change to (the notification's *Mute…* presets); none for other quiets. */
    fun changeTo(q: AlertQuietSide): List<Int> =
        if (q.reason == AlertQuietReason.Muted) AlertLimits.ACTION_MUTE.presetMinutes.filter { it > 0 } else emptyList()

    /** The side [q] belongs to (null: a kind this build doesn't know). */
    fun side(q: AlertQuietSide): Side? = AlertKind.fromWire(q.kind)?.side

    /** The quiet sides of [alerts] that have something to show (a line and at least one way out). */
    fun active(alerts: AlertUiState): List<AlertQuietSide> = alerts.quiet.filter { side(it) != null && of(it).isNotEmpty() }
}

@Composable
private fun exitName(e: QuietExit): String =
    stringResource(
        when (e) {
            QuietExit.Unmute -> R.string.ds_quiet_unmute
            QuietExit.AlertAnyway -> R.string.ds_quiet_anyway
            QuietExit.RemindAsUsual -> R.string.ds_quiet_as_usual
        },
    )

/**
 * One quiet side with its ways out: its line counting down ([MuteText]: "Low alerts muted · 12 min left"), the
 * reminder line when it also reminds less, *Unmute* / *Alert me anyway*, *Remind as usual*, and for a mute the lengths
 * it can *Change to*. [now] ticks with the caller ([rememberCountdownNow]); nothing shows once the quiet is over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuietRow(
    q: AlertQuietSide,
    now: Instant,
    onExit: (QuietExit) -> Unit,
    onChangeTo: (minutes: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = rememberAlertWords()
    val haptics = rememberHaptics()
    val line = MuteText.side(q, now, words) ?: return
    val side = QuietExits.side(q) ?: return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(line, style = MaterialTheme.typography.bodyLarge)
        if (q.reason != null && q.relaxMin > 0) {
            Text(
                MuteText.line(MuteLine.SideLess(side, q.relaxMin), words),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (e in QuietExits.of(q)) {
                FilledTonalButton(onClick = {
                    haptics.confirm()
                    onExit(e)
                }) { Text(exitName(e)) }
            }
        }
        val lengths = QuietExits.changeTo(q)
        if (lengths.isNotEmpty()) {
            Text(
                stringResource(R.string.ds_quiet_change_to),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (m in lengths) {
                    OutlinedButton(onClick = {
                        haptics.confirm()
                        onChangeTo(m)
                    }) { Text(Durations.text(m.toLong(), words.durations)) }
                }
            }
        }
    }
}

/**
 * Mute alerts is on: its line counting down ("Alerts muted · 1 h 12 min left"), *Unmute* (filled: the way out must be
 * easy to find) and *Change time* (opens the lengths), on a tonal surface in the neutral secondary colours, never red.
 * Nothing while it is off or over.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MuteAllState(
    alerts: AlertUiState,
    now: Instant,
    onUnmute: () -> Unit,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val words = rememberAlertWords()
    val haptics = rememberHaptics()
    val line = MuteText.muteAll(alerts, now, words) ?: return
    Surface(
        modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(R.drawable.ds_ic_notifications_off),
                    contentDescription = null,
                    modifier = Modifier.padding(end = 12.dp).size(20.dp),
                )
                Text(line, style = MaterialTheme.typography.titleSmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = {
                    haptics.confirm()
                    onUnmute()
                }) { Text(stringResource(R.string.ds_mute_all_unmute)) }
                TextButton(onClick = onChange) { Text(stringResource(R.string.ds_mute_all_change)) }
            }
        }
    }
}

/**
 * The card's TalkBack actions for the alert states (every way out reachable without the notification or the pill):
 * per quiet side "Low alerts: Unmute" / "…: Alert me anyway" / "…: Remind as usual"; then Mute alerts' *Unmute alerts*
 * and *Change mute time* while it is on, *Mute alerts…* (opens the lengths) while it is off.
 */
@Composable
fun alertA11yActions(
    alerts: AlertUiState,
    muted: Boolean,
    onExit: (AlertQuietSide, QuietExit) -> Unit,
    onUnmuteAll: () -> Unit,
    onMuteAll: () -> Unit,
): List<CustomAccessibilityAction> {
    val words = rememberAlertWords()
    val names = QuietExit.entries.associateWith { exitName(it) }
    val action = stringResource(R.string.ds_quiet_a11y_action)
    val unmuteAll = stringResource(R.string.ds_a11y_unmute_all)
    val change = stringResource(R.string.ds_a11y_change_mute)
    val mute = stringResource(R.string.ds_a11y_mute_all)
    val sides =
        QuietExits.active(alerts).flatMap { q ->
            val side = MuteText.sideName(QuietExits.side(q) ?: return@flatMap emptyList(), words)
            QuietExits.of(q).map { e -> CustomAccessibilityAction(action.format(side, names.getValue(e))) { onExit(q, e).let { true } } }
        }
    val all =
        if (muted) {
            listOf(
                CustomAccessibilityAction(unmuteAll) { onUnmuteAll().let { true } },
                CustomAccessibilityAction(change) { onMuteAll().let { true } },
            )
        } else {
            listOf(CustomAccessibilityAction(mute) { onMuteAll().let { true } })
        }
    return sides + all
}
