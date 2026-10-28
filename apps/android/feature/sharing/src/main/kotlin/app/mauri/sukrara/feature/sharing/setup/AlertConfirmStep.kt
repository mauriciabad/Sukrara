// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.feature.sharing.R

/**
 * P4: the alert thresholds the runtime will use, in Settings' own rows ([SetupActions.thresholdRows]: the same names,
 * sentences and number dialog as Settings › Review alert thresholds); "Use these thresholds" is the only way to
 * complete the step (Later → "Alerts not confirmed" on the Finish-setup card). Nothing is saved until the person
 * confirms.
 */
@Composable
internal fun ColumnScope.AlertConfirmStep(
    u: SetupUi,
    actions: SetupActions,
) {
    StepHeader(stringResource(R.string.sh_setup_alerts_title), stringResource(R.string.sh_setup_alerts_body))
    actions.thresholdRows(u.alerts.settings, actions.editThreshold)
    if (!u.alerts.ordered) NoteCard(stringResource(R.string.sh_setup_alerts_order), error = true)
    StepButtons(
        stringResource(R.string.sh_setup_alerts_confirm),
        actions.confirmAlerts,
        enabled = u.alerts.ordered && !u.busy,
        secondary = listOf(stringResource(R.string.sh_setup_later) to { actions.skip(SetupStep.Alerts) }),
    )
}
