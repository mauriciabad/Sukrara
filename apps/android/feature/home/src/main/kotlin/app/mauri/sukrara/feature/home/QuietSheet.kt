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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics

/**
 * The ways out of a quiet the card's pill shows ([PillAction.Quiet] / [PillAction.MuteAll]): what is quiet and why,
 * *Alert me anyway* (after a treatment) or *Unmute* ([onEnd]), and *Alert settings* ([onSettings]). Back, a swipe down
 * or a tap outside close it with nothing changed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun QuietSheet(
    action: PillAction,
    onDismiss: () -> Unit,
    onEnd: () -> Unit,
    onSettings: () -> Unit,
) {
    val haptics = rememberHaptics()
    val quiet = action as? PillAction.Quiet
    val title =
        when {
            quiet == null -> R.string.home_quiet_all
            quiet.lowSide -> R.string.home_quiet_lows
            else -> R.string.home_quiet_highs
        }
    val text =
        when {
            quiet == null -> R.string.home_quiet_all_text
            quiet.taken -> R.string.home_quiet_taken
            else -> R.string.home_quiet_muted
        }
    val end = if (quiet?.taken == true) R.string.home_quiet_anyway else R.string.home_unmute
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("quiet-sheet")) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(
                onClick = {
                    haptics.confirm()
                    onEnd()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(end)) }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_quiet_settings)) }
        }
    }
}
