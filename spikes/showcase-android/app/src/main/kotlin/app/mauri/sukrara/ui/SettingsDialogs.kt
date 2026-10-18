// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import app.mauri.sukrara.R
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.dialog.SettingsDialog
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsDialogItem
import kotlin.math.roundToLong

/*
 * The settings dialogs, on SPA's: a choice is SPA's ListPreference dialog (SettingsDialog with radio rows, choosing
 * saves and closes), a number choice adds a "Custom" radio that reveals a number field, like the only custom option
 * in AOSP Settings (the keyboard key-delay dialog, res/layout/dialog_keyboard_a11y_input_setting_keys.xml: the last
 * radio is "Custom" and reveals its own control) with the field of SPA's SettingsOutlinedTextField, and the
 * expressive dialog buttons of SPA's SettingsAlertDialog (outlined dismiss, filled confirm).
 */

/** One option of a [ChoiceDialog]; [trailing] is drawn at the row's end (a ▶ to preview it). */
data class Choice<T>(val label: String, val value: T, val sub: String? = null, val enabled: Boolean = true,
                     val trailing: (@Composable () -> Unit)? = null)

/**
 * SPA ListPreference's dialog: radio buttons, choosing saves and closes. With [closeOnChoose] false (options can be
 * tried, e.g. sounds) choosing only selects and a Done button closes; [bottom] goes under the options.
 */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Choice<T>>, selected: T, onClose: () -> Unit, closeOnChoose: Boolean = true,
                     bottom: (@Composable () -> Unit)? = null, onChoose: (T) -> Unit) {
    SettingsDialog(title = title, onDismissRequest = onClose) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            for (o in options) Radio(o.label, o.sub, o.value == selected, o.enabled, o.trailing) {
                onChoose(o.value); if (closeOnChoose) onClose()
            }
            bottom?.invoke()
            if (!closeOnChoose) DialogButtons(confirm = stringResource(R.string.settings_dialog_done), onConfirm = onClose)
        }
    }
}

/**
 * SPA ListPreference's private radio row (same padding, 16 dp gap, SettingsDialogItem + one-line SettingsBody), plus
 * an optional trailing control.
 */
@Composable
private fun Radio(text: String, sub: String?, selected: Boolean, enabled: Boolean, trailing: (@Composable () -> Unit)?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
        .padding(SettingsDimension.dialogItemPadding), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(SettingsDimension.itemPaddingEnd))
        Column(Modifier.weight(1f).alphaForEnabled(enabled)) {
            SettingsDialogItem(text = text, enabled = enabled)
            if (!sub.isNullOrEmpty()) SettingsBody(body = sub)
        }
        trailing?.invoke()
    }
}

/** A check box row in a dialog (same padding as the radio rows): e.g. "Vibrate" under the sound previews. */
@Composable
fun DialogCheckbox(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = onChange)
        .padding(SettingsDimension.dialogItemPadding), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(SettingsDimension.itemPaddingEnd))
        SettingsDialogItem(text = text)
    }
}

/** The expressive buttons of SPA's SettingsAlertDialog: outlined dismiss, filled confirm, at the end. */
@Composable
private fun DialogButtons(confirm: String, confirmEnabled: Boolean = true, dismiss: String? = null, onDismiss: () -> Unit = {},
                          onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(SettingsDimension.dialogItemPadding),
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4, Alignment.End)) {
        dismiss?.let { OutlinedButton(onClick = onDismiss) { Text(it) } }
        Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm) }
    }
}

/**
 * The values a custom number may take: [min]..[max] in [unit], with [decimals] digits after the point. The value
 * typed times [scale] is what's stored (e.g. days typed, seconds stored).
 */
data class NumberRange(val min: Double, val max: Double, val unit: String, val decimals: Int = 0, val scale: Double = 1.0) {
    fun format(v: Double): String = if (decimals == 0) v.roundToLong().toString() else "%.${decimals}f".format(v)
    /** The text typed → the stored value, or null when it isn't a number in range. */
    fun parse(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it in min..max }?.let { it * scale }
    /** A stored value as the field shows it. */
    fun field(v: Double): String = format(v / scale)
}

/**
 * A list of numbers plus "Custom": the presets as radio rows (choosing one saves and closes); "Custom" (already
 * selected when the current value isn't a preset, with the value as its summary) reveals a number field checked
 * against [range], saved with the filled button. [label] names a value in the list and in the row's summary.
 */
@Composable
fun NumberChoiceDialog(title: String, presets: List<Double>, selected: Double, label: (Double) -> String, range: NumberRange,
                       onClose: () -> Unit, onChoose: (Double) -> Unit) {
    val isPreset = presets.any { it == selected }
    var custom by rememberSaveable { mutableStateOf(!isPreset) }
    var text by rememberSaveable { mutableStateOf(if (isPreset) "" else range.field(selected)) }
    val parsed = range.parse(text)
    SettingsDialog(title = title, onDismissRequest = onClose) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            for (p in presets) Radio(label(p), null, !custom && p == selected, true, null) { onChoose(p); onClose() }
            Radio(stringResource(R.string.settings_custom_value), if (!isPreset && !custom) label(selected) else null, custom, true, null) {
                custom = true
            }
            if (custom) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                // SPA's SettingsOutlinedTextField (label, error as supporting text), with a number keyboard and the unit
                val error = if (text.isNotEmpty() && parsed == null)
                    stringResource(R.string.settings_custom_value_range, range.format(range.min), range.format(range.max), range.unit) else null
                OutlinedTextField(value = text, onValueChange = { t ->
                        text = t.filter { it.isDigit() || (range.decimals > 0 && (it == '.' || it == ',')) }.take(6)
                    },
                    label = { Text(stringResource(R.string.settings_custom_value_label)) },
                    suffix = { Text(range.unit) }, singleLine = true, isError = error != null,
                    supportingText = { Text(error ?: stringResource(R.string.settings_custom_value_range,
                        range.format(range.min), range.format(range.max), range.unit)) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (range.decimals > 0) KeyboardType.Decimal else KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(SettingsDimension.textFieldPadding).focusRequester(focus))
                DialogButtons(confirm = stringResource(R.string.settings_dialog_ok), confirmEnabled = parsed != null,
                    dismiss = stringResource(R.string.settings_dialog_cancel), onDismiss = onClose) {
                    parsed?.let { onChoose(it); onClose() }
                }
            }
        }
    }
}

/** [NumberChoiceDialog] for whole numbers. */
@Composable
fun IntChoiceDialog(title: String, presets: List<Int>, selected: Int, label: (Int) -> String, range: NumberRange,
                    onClose: () -> Unit, onChoose: (Int) -> Unit) =
    NumberChoiceDialog(title, presets.map { it.toDouble() }, selected.toDouble(), { label(it.roundToLong().toInt()) }, range, onClose) {
        onChoose(it.roundToLong().toInt())
    }
