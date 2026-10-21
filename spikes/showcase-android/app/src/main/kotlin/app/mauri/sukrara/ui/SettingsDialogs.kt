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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.android.settingslib.spa.framework.theme.divider
import app.mauri.sukrara.R
import app.mauri.sukrara.settings.NumberSpec
import com.android.settingslib.spa.framework.theme.SettingsDimension
import com.android.settingslib.spa.framework.theme.SettingsOpacity.alphaForEnabled
import com.android.settingslib.spa.framework.theme.SettingsSpace
import com.android.settingslib.spa.widget.dialog.SettingsDialog
import com.android.settingslib.spa.widget.ui.SettingsBody
import com.android.settingslib.spa.widget.ui.SettingsDialogItem
import kotlin.math.roundToLong

/*
 * The settings dialogs, on SPA's: a choice is SPA's ListPreference dialog (SettingsDialog with radio rows, choosing
 * saves and closes), a number is [NumberDialog] (the one number input, from the setting's schema spec) adding a
 * "Custom" radio that reveals a number field, like the only custom option in AOSP Settings (the keyboard key-delay
 * dialog, res/layout/dialog_keyboard_a11y_input_setting_keys.xml: the last radio is "Custom" and reveals its own
 * control) with the field of SPA's SettingsOutlinedTextField, and the expressive dialog buttons of SPA's
 * SettingsAlertDialog (outlined dismiss, filled confirm).
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
            bottom?.let {
                HorizontalDivider(Modifier.padding(horizontal = SettingsDimension.dialogItemPaddingHorizontal, vertical = SettingsSpace.extraSmall4),
                    color = MaterialTheme.colorScheme.divider)
                it()
            }
            if (!closeOnChoose) DialogButtons(confirm = stringResource(R.string.settings_dialog_done), onConfirm = onClose)
        }
    }
}

/**
 * SPA ListPreference's private radio row (same padding, 16 dp gap, SettingsDialogItem + one-line SettingsBody), plus
 * an optional trailing control.
 */
@Composable
internal fun Radio(text: String, sub: String?, selected: Boolean, enabled: Boolean, trailing: (@Composable () -> Unit)?, onClick: () -> Unit) {
    // a trailing control (48 dp) replaces the row's 12 dp vertical padding, so rows with and without one match
    Row(Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, onClick = onClick, role = Role.RadioButton)
        .padding(horizontal = SettingsDimension.dialogItemPaddingHorizontal,
            vertical = if (trailing != null) 0.dp else SettingsDimension.buttonPaddingVertical),
        verticalAlignment = Alignment.CenterVertically) {
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
internal fun DialogButtons(confirm: String, confirmEnabled: Boolean = true, dismiss: String? = null, onDismiss: () -> Unit = {},
                          onConfirm: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(SettingsDimension.dialogItemPadding),
        horizontalArrangement = Arrangement.spacedBy(SettingsSpace.extraSmall4, Alignment.End)) {
        dismiss?.let { OutlinedButton(onClick = onDismiss) { Text(it) } }
        Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirm) }
    }
}

/** A number spec's unit as the field's suffix and the range text say it ("h", "días", "mg/dL"). */
@Composable
fun unitText(spec: NumberSpec): String = when (spec.unit) {
    "h" -> stringResource(R.string.unit_h)
    "d" -> stringResource(R.string.unit_d)
    "min" -> stringResource(R.string.unit_min)
    else -> spec.unit
}

/**
 * THE number input (round f28; the Mac's NumberSettingRow is the same pattern), driven by the setting's schema spec
 * (spikes/settings/settings.json → [SettingsSpec]): the special values (e.g. "Off", "Once") and the presets as radio rows
 * (choosing one saves and closes, SPA's ListPreference dialog), then — when the spec takes custom values — "Custom",
 * already selected when the current value isn't a preset, which reveals a number field: the unit as its suffix, the
 * allowed range as supporting text, an error in place of it while the text isn't a value in range on the spec's step
 * (M3 text fields: "use supporting text to show the format or the allowed values", "show an error until it is
 * fixed"), saved with the filled button. A spec without custom values is the plain choice dialog. Like AOSP Settings'
 * only custom option, the keyboard key-delay dialog (the last radio is "Custom" and reveals its own control). Exact
 * values are typed, never dragged: glucose limits and minutes are precise, and sliders are for "settings that use a
 * range of values, such as volume and brightness" (developer.android.com/develop/ui/compose/components/slider).
 * [presets] (default: the spec's) can be narrowed by the caller (Slow down), [customMin] raises the lowest custom
 * value, and [reject] returns a message for a value that is valid but not allowed here (a line already drawn).
 * [focusField] false keeps the keyboard closed (the JVM pictures: a blinking cursor never lets Compose idle).
 * [sub] gives an option a second line (Mute all's Never: "Until I turn them back on"). A spec whose unit is minutes
 * labels its presets with Durations.text ("1 h", "2 h", not "60 min") through the caller's [label].
 */
@Composable
fun NumberDialog(title: String, spec: NumberSpec, selected: Double, label: (Double) -> String, onClose: () -> Unit,
                 presets: List<Double> = spec.presets, customMin: Double = spec.min, reject: (Double) -> String? = { null },
                 focusField: Boolean = true, sub: (Double) -> String? = { null }, onChoose: (Double) -> Unit) {
    val options = (spec.special.keys.filter { k -> presets.none { it == k } }.sorted() + presets).distinct()
    if (!spec.custom) {
        ChoiceDialog(title, options.map { Choice(label(it), it, sub(it)) }, selected, onClose) { onChoose(it) }
        return
    }
    val isPreset = options.any { it == selected }
    var custom by rememberSaveable { mutableStateOf(!isPreset || options.isEmpty()) }
    var text by rememberSaveable { mutableStateOf(if (isPreset || options.isEmpty()) "" else spec.text(selected)) }
    val parsed = spec.parse(text)?.takeIf { it >= customMin - 1e-9 }
    val unit = unitText(spec)
    val rangeText = stringResource(R.string.settings_custom_value_range, spec.text(maxOf(customMin, spec.min)), spec.text(spec.max), unit)
    val rejected = parsed?.let(reject)
    SettingsDialog(title = title, onDismissRequest = onClose) {
        Column(Modifier.selectableGroup().verticalScroll(rememberScrollState())) {
            for (p in options) Radio(label(p), sub(p), !custom && p == selected, true, null) { onChoose(p); onClose() }
            if (options.isNotEmpty()) Radio(stringResource(R.string.settings_custom_value), if (!isPreset && !custom) label(selected) else null, custom, true, null) {
                custom = true
            }
            if (custom) {
                val focus = remember { FocusRequester() }
                if (focusField) LaunchedEffect(Unit) { focus.requestFocus() }
                // SPA's SettingsOutlinedTextField (label, error as supporting text), with a number keyboard and the unit
                val error = rejected ?: if (text.isNotEmpty() && parsed == null) rangeText else null
                OutlinedTextField(value = text, onValueChange = { t ->
                        text = t.filter { it.isDigit() || (spec.decimals > 0 && (it == '.' || it == ',')) }.take(6)
                    },
                    label = { Text(stringResource(R.string.settings_custom_value_label)) },
                    suffix = { Text(unit) }, singleLine = true, isError = error != null,
                    supportingText = { Text(error ?: rangeText) },
                    keyboardOptions = KeyboardOptions(keyboardType = if (spec.decimals > 0) KeyboardType.Decimal else KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(SettingsDimension.textFieldPadding).focusRequester(focus))
                DialogButtons(confirm = stringResource(R.string.settings_dialog_ok), confirmEnabled = parsed != null && rejected == null,
                    dismiss = stringResource(R.string.settings_dialog_cancel), onDismiss = onClose) {
                    parsed?.let { onChoose(it); onClose() }
                }
            }
        }
    }
}

/** [NumberDialog] for a setting stored as a whole number. */
@Composable
fun IntNumberDialog(title: String, spec: NumberSpec, selected: Int, label: (Int) -> String, onClose: () -> Unit,
                    presets: List<Int>? = null, customMin: Int? = null, reject: (Int) -> String? = { null },
                    sub: (Int) -> String? = { null }, onChoose: (Int) -> Unit) =
    NumberDialog(title, spec, selected.toDouble(), { label(it.roundToLong().toInt()) }, onClose,
        presets = presets?.map { it.toDouble() } ?: spec.presets, customMin = customMin?.toDouble() ?: spec.min,
        reject = { reject(it.roundToLong().toInt()) }, sub = { sub(it.roundToLong().toInt()) }) { onChoose(it.roundToLong().toInt()) }
