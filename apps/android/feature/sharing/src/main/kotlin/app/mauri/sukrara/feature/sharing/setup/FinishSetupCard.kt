// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.feature.sharing.R

/**
 * The home "Finish setup" card (chunk 16 places it below the glucose card, never above it): one row per skipped step
 * that still matters ([SetupMachine.finishItems]); a row reopens its step. Shows nothing when [items] is empty.
 */
@Composable
fun FinishSetupCard(
    items: List<FinishItem>,
    onOpen: (SetupStep) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp)) {
            Text(
                stringResource(R.string.finish_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
            )
            for (item in items) NavigationRow(title = finishText(item), onClick = { onOpen(item.step) })
        }
    }
}

@Composable
private fun finishText(item: FinishItem): String =
    stringResource(
        when (item) {
            FinishItem.AllowAlerts -> R.string.finish_allow
            FinishItem.TurnOffShowcase -> R.string.finish_showcase
            FinishItem.ConnectSensor -> R.string.finish_sensor
            FinishItem.AlertsNotConfirmed -> R.string.finish_alerts
            FinishItem.RestoreFollowers -> R.string.finish_identity
        },
    )
