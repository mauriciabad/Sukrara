// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.mauri.sukrara.core.model.AlertReadiness
import app.mauri.sukrara.core.model.AlertRequirement
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.nameRes
import app.mauri.sukrara.designsystem.rows.whyRes

/**
 * Alert reliability: the same checklist as home's row and the setup step, every item with its state
 * and, while missing, a Fix button that opens the system screen for it. The page refreshes when it comes back.
 */
@Composable
internal fun ReliabilityContent(
    readiness: AlertReadiness,
    onFix: (AlertRequirement) -> Unit,
    onBack: () -> Unit,
) {
    SettingsPage(stringResource(R.string.st_reliability_title), onBack) {
        if (readiness.ready) {
            StateStrip(stringResource(R.string.st_reliability_ready), emptyList())
        }
        SettingsGroup {
            for (requirement in AlertRequirement.entries) {
                val missing = requirement in readiness.missing
                val state = stringResource(if (missing) R.string.st_req_missing else R.string.st_req_ok)
                val titleText = stringResource(requirement.nameRes())
                CardRow {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(titleText, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            state,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (missing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        stringResource(requirement.whyRes()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (missing) {
                        val fixLabel = stringResource(R.string.st_fix_item, titleText)
                        FilledTonalButton(
                            onClick = { onFix(requirement) },
                            modifier = Modifier.semantics { contentDescription = fixLabel },
                        ) {
                            Text(stringResource(R.string.st_fix))
                        }
                    }
                }
            }
        }
        FooterText(stringResource(R.string.st_reliability_footer))
    }
}
