// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.designsystem.dialog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.designsystem.R
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.settings.NumberSpec

/** One option of a [ChoiceDialog]. */
data class Choice<T>(
    val label: String,
    val value: T,
    val sub: String? = null,
    val enabled: Boolean = true,
)

/** A single choice: radio rows; choosing saves and closes. Cancel (or back) leaves the value as it was. */
@Composable
fun <T> ChoiceDialog(
    title: String,
    options: List<Choice<T>>,
    selected: T,
    onClose: () -> Unit,
    onChoose: (T) -> Unit,
) {
    val haptics = rememberHaptics()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (o in options) {
                    RadioRow(o.label, o.sub, o.value == selected, o.enabled) {
                        haptics.confirm()
                        onChoose(o.value)
                        onClose()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.ds_cancel)) } },
    )
}

/** A radio row: the whole row selects (TalkBack: "radio button, selected"), at least 48 dp tall. */
@Composable
internal fun RadioRow(
    text: String,
    sub: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            if (!sub.isNullOrEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A number spec's unit as the field's suffix and the range text say it ("h", "días", "mg/dL"). */
@Composable
fun unitText(spec: NumberSpec): String =
    when (spec.unit) {
        "h" -> stringResource(R.string.ds_unit_h)
        "d" -> stringResource(R.string.ds_unit_d)
        "min" -> stringResource(R.string.ds_unit_min)
        "s" -> stringResource(R.string.ds_unit_s)
        else -> spec.unit
    }

/**
 * THE number input (the Mac's NumberSettingRow is the same pattern), driven by the setting's generated spec: special
 * values ("Off") and presets as radio rows (choosing one saves and closes), then — when the spec takes custom values —
 * "Custom", already selected when the current value isn't an option, revealing a number field with the unit as suffix,
 * the allowed range as supporting text and an error in its place while the text isn't valid; OK saves. Exact values
 * are typed, never dragged: glucose limits and minutes are precise. [label] names an option ("1 h", "Off").
 * [focusField] false keeps the keyboard closed (screenshot tests).
 */
@Composable
fun NumberDialog(
    title: String,
    input: NumberInput,
    selected: Double,
    label: (Double) -> String,
    onClose: () -> Unit,
    focusField: Boolean = true,
    sub: (Double) -> String? = { null },
    onChoose: (Double) -> Unit,
) {
    if (!input.hasCustom) {
        ChoiceDialog(title, input.options.map { Choice(label(it), it, sub(it)) }, selected, onClose, onChoose)
        return
    }
    val haptics = rememberHaptics()
    val isOption = input.isOption(selected)
    var custom by rememberSaveable { mutableStateOf(!isOption || input.options.isEmpty()) }
    var text by rememberSaveable { mutableStateOf(input.initialText(selected)) }
    val unit = unitText(input.spec)
    val rangeText = stringResource(R.string.ds_custom_range, input.shownMin, input.shownMax, unit)
    val check = input.check(text)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
                for (p in input.options) {
                    RadioRow(label(p), sub(p), !custom && input.same(p, selected), true) {
                        haptics.confirm()
                        onChoose(p)
                        onClose()
                    }
                }
                if (input.options.isNotEmpty()) {
                    RadioRow(stringResource(R.string.ds_custom), null, custom, true) { custom = true }
                }
                if (custom) {
                    val focus = remember { FocusRequester() }
                    if (focusField) LaunchedEffect(Unit) { focus.requestFocus() }
                    val error =
                        when (check) {
                            is NumberInput.Check.Rejected -> check.message
                            NumberInput.Check.OutOfRange -> rangeText
                            else -> null
                        }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = input.filter(it) },
                        label = { Text(stringResource(R.string.ds_custom_label)) },
                        suffix = { Text(unit) },
                        singleLine = true,
                        isError = error != null,
                        supportingText = { Text(error ?: rangeText) },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = if (input.spec.decimals > 0) KeyboardType.Decimal else KeyboardType.Number,
                            ),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(focus),
                    )
                }
            }
        },
        confirmButton = {
            if (custom) {
                TextButton(
                    enabled = check is NumberInput.Check.Ok,
                    onClick = {
                        (check as? NumberInput.Check.Ok)?.let {
                            haptics.confirm()
                            onChoose(it.value)
                            onClose()
                        }
                    },
                ) { Text(stringResource(R.string.ds_ok)) }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClose) { Text(stringResource(R.string.ds_cancel)) }
            }
        },
    )
}
