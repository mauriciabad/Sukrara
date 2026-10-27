// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.designsystem.dialog.NumberDialog
import app.mauri.sukrara.designsystem.dialog.NumberInput
import app.mauri.sukrara.designsystem.dialog.unitText
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.sharing.R

/**
 * P4: the alert thresholds the runtime will use, shown and editable with THE number dialog; "Confirm these alerts" is
 * the only way to complete the step (Later → "Alerts not confirmed" on the Finish-setup card). Nothing is saved until
 * the person confirms.
 */
@Composable
internal fun ColumnScope.AlertConfirmStep(
    u: SetupUi,
    actions: SetupActions,
) {
    var editing by rememberSaveable { mutableStateOf<ThresholdField?>(null) }
    StepHeader(stringResource(R.string.setup_alerts_title), stringResource(R.string.setup_alerts_body))
    SettingsGroup {
        for (field in ThresholdField.entries) {
            val value = u.alerts.value(field)
            val unit = unitText(field.spec)
            val shown =
                if (u.alerts.isOn(field)) {
                    stringResource(R.string.setup_alerts_value, field.spec.text(value), unit)
                } else {
                    stringResource(R.string.setup_alerts_value_off, field.spec.text(value), unit)
                }
            ValueRow(title = thresholdTitle(field), value = shown, onClick = { editing = field })
        }
    }
    if (!u.alerts.ordered) NoteCard(stringResource(R.string.setup_alerts_order), error = true)
    StepButtons(
        stringResource(R.string.setup_alerts_confirm),
        actions.confirmAlerts,
        enabled = u.alerts.ordered && !u.busy,
        secondary = listOf(stringResource(R.string.setup_later) to { actions.skip(SetupStep.Alerts) }),
    )
    val field = editing
    if (field != null) {
        val unit = unitText(field.spec)
        val res = LocalContext.current.resources
        NumberDialog(
            title = thresholdTitle(field),
            input = NumberInput(field.spec),
            selected = u.alerts.value(field),
            label = { res.getString(R.string.setup_alerts_value, field.spec.text(it), unit) },
            onClose = { editing = null },
            onChoose = { actions.editThreshold(field, it) },
        )
    }
}

@Composable
private fun thresholdTitle(field: ThresholdField): String =
    stringResource(
        when (field) {
            ThresholdField.VeryLow -> R.string.setup_alerts_very_low
            ThresholdField.Low -> R.string.setup_alerts_low
            ThresholdField.High -> R.string.setup_alerts_high
            ThresholdField.VeryHigh -> R.string.setup_alerts_very_high
            ThresholdField.NoData -> R.string.setup_alerts_no_data
        },
    )
