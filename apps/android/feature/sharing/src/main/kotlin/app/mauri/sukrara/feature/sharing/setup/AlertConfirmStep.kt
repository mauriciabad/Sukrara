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
    StepHeader(stringResource(R.string.sh_setup_alerts_title), stringResource(R.string.sh_setup_alerts_body))
    SettingsGroup {
        for (field in ThresholdField.entries) {
            // the same row as Settings › Review alert thresholds: the kind's name, then "Below 54 mg/dL" / "Off (…)"
            val sentence = thresholdSentence(field, stringResource(R.string.sh_setup_alerts_value, field.spec.text(u.alerts.value(field)), unitText(field.spec)))
            val shown = if (u.alerts.isOn(field)) sentence else stringResource(R.string.sh_setup_alerts_off, sentence)
            ValueRow(title = thresholdTitle(field), value = shown, onClick = { editing = field })
        }
    }
    if (!u.alerts.ordered) NoteCard(stringResource(R.string.sh_setup_alerts_order), error = true)
    StepButtons(
        stringResource(R.string.sh_setup_alerts_confirm),
        actions.confirmAlerts,
        enabled = u.alerts.ordered && !u.busy,
        secondary = listOf(stringResource(R.string.sh_setup_later) to { actions.skip(SetupStep.Alerts) }),
    )
    val field = editing
    if (field != null) {
        val unit = unitText(field.spec)
        val res = LocalContext.current.resources
        NumberDialog(
            title = thresholdTitle(field),
            input = NumberInput(field.spec),
            selected = u.alerts.value(field),
            label = { res.getString(R.string.sh_setup_alerts_value, field.spec.text(it), unit) },
            onClose = { editing = null },
            onChoose = { actions.editThreshold(field, it) },
        )
    }
}

@Composable
private fun thresholdTitle(field: ThresholdField): String =
    stringResource(
        when (field) {
            ThresholdField.VeryLow -> R.string.sh_setup_alerts_very_low
            ThresholdField.Low -> R.string.sh_setup_alerts_low
            ThresholdField.High -> R.string.sh_setup_alerts_high
            ThresholdField.VeryHigh -> R.string.sh_setup_alerts_very_high
            ThresholdField.ExtremelyHigh -> R.string.sh_setup_alerts_extremely_high
            ThresholdField.NoData -> R.string.sh_setup_alerts_no_data
        },
    )

/** Settings' threshold sentence: "Below 54 mg/dL", "At or above 300 mg/dL", "No reading for 20 min". */
@Composable
private fun thresholdSentence(
    field: ThresholdField,
    value: String,
): String =
    when {
        field == ThresholdField.NoData -> stringResource(R.string.sh_setup_alerts_no_reading_for, value)
        field.kind.isBelowThreshold -> stringResource(R.string.sh_setup_alerts_below, value)
        else -> stringResource(R.string.sh_setup_alerts_at_or_above, value)
    }
