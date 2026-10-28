// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.alerts.Side
import app.mauri.sukrara.core.model.AlertQuietReason
import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.core.model.AlertUiState
import app.mauri.sukrara.designsystem.alerts.MuteAllState
import app.mauri.sukrara.designsystem.alerts.MuteText
import app.mauri.sukrara.designsystem.alerts.QuietExit
import app.mauri.sukrara.designsystem.alerts.QuietExits
import app.mauri.sukrara.designsystem.alerts.QuietRow
import app.mauri.sukrara.designsystem.alerts.rememberAlertWords
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import java.time.Instant

/**
 * What the quiet sheet is about: one [side]'s quiet states (null = Mute alerts only). [fallback] is the way out the
 * card's pill promised, shown when the alert state has no row for that side (so the pill never opens a sheet without
 * its exit).
 */
data class QuietTarget(
    val side: Side?,
    val fallback: QuietExit? = null,
) {
    companion object {
        /** The sheet a quiet pill opens. */
        fun of(action: PillAction): QuietTarget? =
            when (action) {
                is PillAction.Quiet -> {
                    QuietTarget(
                        if (action.lowSide) Side.LOW else Side.HIGH,
                        if (action.taken) QuietExit.AlertAnyway else QuietExit.Unmute,
                    )
                }

                PillAction.MuteAll -> {
                    QuietTarget(null)
                }

                else -> {
                    null
                }
            }

        /** [alerts]' quiet sides of [side] that have a way out (all of them for null). */
        fun rows(
            alerts: AlertUiState,
            side: Side?,
        ): List<AlertQuietSide> = QuietExits.active(alerts).filter { side == null || QuietExits.side(it) == side }

        /**
         * The quiet sides home lists as chips under the card: the ones the pill doesn't already stand for ([pill]'s side
         * muted or quiet after a treatment); a side that also reminds less stays (the pill doesn't say that).
         */
        fun chips(
            alerts: AlertUiState,
            pill: PillAction,
        ): List<AlertQuietSide> {
            val pillSide = (QuietTarget.of(pill)?.side)
            return QuietExits.active(alerts).filter { q -> QuietExits.side(q) != pillSide || q.relaxMin > 0 }
        }
    }
}

/**
 * The ways out of a quiet, from the card's pill or a chip: Mute alerts' state (*Unmute*, *Change time*) while it is on,
 * then each quiet state of the side with its exits (*Unmute* / *Alert me anyway*, *Remind as usual*, *Change to* a
 * length), and *Alert settings*. It closes once the last state shown is left; back, a swipe down or a tap outside close
 * it with nothing changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuietSheet(
    target: QuietTarget,
    alerts: AlertUiState,
    muted: Boolean,
    now: Instant,
    onDismiss: () -> Unit,
    onAlert: (HomeAlertAction) -> Unit,
    onChangeMute: () -> Unit,
    onSettings: () -> Unit,
) {
    val haptics = rememberHaptics()
    val words = rememberAlertWords()
    val rows = QuietTarget.rows(alerts, target.side)
    val empty = rows.isEmpty() && !muted
    // opened with nothing listed (the pill's state not published yet): its own exit shows; otherwise the sheet goes
    // once its last state is left (Unmute, the mute's end, back in range)
    val openedEmpty = remember { empty }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(empty) { if (empty && !openedEmpty) dismiss() }
    val title =
        when (val side = target.side) {
            null -> stringResource(R.string.home_quiet_all)
            else -> stringResource(R.string.home_quiet_side, MuteText.sideName(side, words))
        }
    val text =
        when {
            target.side == null -> R.string.home_quiet_all_text
            rows.any { it.reason == AlertQuietReason.AfterCarbs || it.reason == AlertQuietReason.AfterInsulin } -> R.string.home_quiet_taken
            rows.any { it.reason == AlertQuietReason.Muted } -> R.string.home_quiet_muted
            rows.isEmpty() && target.fallback == QuietExit.AlertAnyway -> R.string.home_quiet_taken
            rows.isEmpty() && target.fallback == QuietExit.Unmute -> R.string.home_quiet_muted
            else -> null
        }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("quiet-sheet")) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            text?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (muted) MuteAllState(alerts, now, onUnmute = { onAlert(HomeAlertAction.UnmuteAll) }, onChange = onChangeMute)
            rows.forEach { q ->
                QuietRow(
                    q,
                    now,
                    onExit = { onAlert(HomeAlertAction.exit(q, it)) },
                    onChangeTo = { onAlert(HomeAlertAction.MuteSide(q.kind, it)) },
                )
            }
            val side = target.side
            val fallback = target.fallback
            if (rows.isEmpty() && side != null && fallback != null) {
                Button(
                    onClick = {
                        haptics.confirm()
                        onAlert(HomeAlertAction.EndQuiet(side.wire))
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (fallback == QuietExit.AlertAnyway) R.string.home_quiet_anyway else R.string.home_unmute))
                }
            }
            TextButton(onClick = onSettings) { Text(stringResource(R.string.home_quiet_settings)) }
        }
    }
}
