// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.graph.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * What the form holds while the person types: the three amounts and the note as typed (after the input filters)
 * and when the entries happen ([at] null = now, resolved when saving).
 */
@Stable
class TreatmentFormState(
    rapid: String = "",
    long: String = "",
    carbs: String = "",
    note: String = "",
    at: Instant? = null,
) {
    var rapid by mutableStateOf(rapid)
    var long by mutableStateOf(long)
    var carbs by mutableStateOf(carbs)
    var note by mutableStateOf(note)
    var at by mutableStateOf(at)

    /** null = an amount is invalid. */
    val entries: List<NewEntry>? get() = TreatmentForm.entries(rapid, long, carbs, note)
    val canSave: Boolean get() = entries?.isNotEmpty() == true
    val hasInput: Boolean get() = listOf(rapid, long, carbs, note).any { it.isNotBlank() }

    companion object {
        val Saver: Saver<TreatmentFormState, Any> =
            listSaver(
                save = { listOf(it.rapid, it.long, it.carbs, it.note, it.at?.toEpochMilli()) },
                restore = {
                    TreatmentFormState(
                        it[0] as String,
                        it[1] as String,
                        it[2] as String,
                        it[3] as String,
                        (it[4] as Long?)?.let(Instant::ofEpochMilli),
                    )
                },
            )
    }
}

@Composable
fun rememberTreatmentFormState(): TreatmentFormState = rememberSaveable(saver = TreatmentFormState.Saver) { TreatmentFormState() }

/** Seconds left before the quick dialog closes over the lock screen, and the way to keep it open. */
data class LockInfo(
    val seconds: Int,
    val onUnlock: () -> Unit,
)

/**
 * Add insulin, carbs and a note in one go: rapid, long-acting and carbs side by side, a note below, all optional;
 * each filled field becomes its own entry at the chosen time (default now). Laid out like an M3 basic dialog (28 dp
 * corners, surface-container-high, 24 dp padding, confirming action last). The first field is focused so the number
 * pad is up at once — Carbs instead when [focusCarbs] (a low is going on). Next walks the fields, Done saves.
 *
 * [onOpenApp] adds *Open Sukrara* (the quick dialog, where a tap no longer opens the app); [lock] shows the
 * lock-screen countdown with *Unlock*.
 */
@Composable
fun TreatmentDialogContent(
    form: TreatmentFormState,
    focusCarbs: Boolean,
    zone: ZoneId,
    now: () -> Instant,
    onSave: (List<NewEntry>, Instant?) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenApp: (() -> Unit)? = null,
    lock: LockInfo? = null,
) {
    val haptics = rememberHaptics()
    val first = remember { FocusRequester() }
    val carbsFocus = remember { FocusRequester() }
    var picking by remember { mutableStateOf(false) }
    val entries = form.entries
    val save = {
        val list = form.entries
        if (list.isNullOrEmpty()) {
            haptics.reject()
        } else {
            haptics.confirm()
            onSave(list, form.at)
        }
    }
    LaunchedEffect(Unit) { runCatching { (if (focusCarbs) carbsFocus else first).requestFocus() } }

    Surface(
        modifier = modifier.widthIn(min = 280.dp, max = 560.dp).testTag("treatment-dialog"),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.treatment_title), style = MaterialTheme.typography.headlineSmall)
            // side by side; stacked at large font sizes so labels and units never truncate
            val stacked = LocalDensity.current.fontScale > STACK_FONT_SCALE
            val fields: @Composable (Modifier) -> Unit = { fieldModifier ->
                AmountField(
                    form.rapid,
                    { form.rapid = it },
                    R.string.treatment_rapid,
                    R.string.treatment_unit_u,
                    fieldModifier.focusRequester(first),
                )
                AmountField(form.long, { form.long = it }, R.string.treatment_long, R.string.treatment_unit_u, fieldModifier)
                AmountField(form.carbs, {
                    form.carbs = it
                }, R.string.treatment_carbs, R.string.treatment_unit_g, fieldModifier.focusRequester(carbsFocus))
            }
            if (stacked) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { fields(Modifier.fillMaxWidth()) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { fields(Modifier.weight(1f)) }
            }
            OutlinedTextField(
                value = form.note,
                onValueChange = { form.note = TreatmentForm.clip(it, TreatmentForm.NOTE_BYTES) },
                label = { Text(stringResource(R.string.treatment_note)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (entries == null) {
                Text(
                    stringResource(R.string.treatment_limits, TreatmentForm.MAX_UNITS.toInt(), TreatmentForm.MAX_GRAMS.toInt()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("treatment-limits"),
                )
            }
            WhenRow(form, zone, onPick = { picking = true })
            if (lock != null) LockRow(lock)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
                if (onOpenApp != null) TextButton(onClick = onOpenApp) { Text(stringResource(R.string.treatment_open_app)) }
                TextButton(onClick = onCancel) { Text(stringResource(R.string.treatment_cancel)) }
                Button(onClick = save, enabled = form.canSave, modifier = Modifier.testTag("treatment-save")) {
                    Text(stringResource(R.string.treatment_save))
                }
            }
        }
    }
    if (picking) {
        TimePickDialog(
            initial = (form.at ?: now()).atZone(zone).toLocalTime(),
            onPick = { time ->
                form.at = TreatmentForm.pickTime(now(), zone, time)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun AmountField(
    value: String,
    onValue: (String) -> Unit,
    label: Int,
    unit: Int,
    modifier: Modifier = Modifier,
) {
    val invalid = TreatmentForm.parse(value, grams = unit == R.string.treatment_unit_g) == TreatmentForm.Amount.Invalid
    OutlinedTextField(
        value = value,
        onValueChange = { onValue(TreatmentForm.filterAmount(it)) },
        label = { Text(stringResource(label), maxLines = 1) },
        suffix = { Text(stringResource(unit)) },
        isError = invalid,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
        modifier = modifier,
    )
}

@Composable
private fun WhenRow(
    form: TreatmentFormState,
    zone: ZoneId,
    onPick: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = form.at == null,
            onClick = { form.at = null },
            label = { Text(stringResource(R.string.treatment_now)) },
        )
        val at = form.at
        FilterChip(
            selected = at != null,
            onClick = onPick,
            label = {
                Text(
                    if (at == null) {
                        stringResource(R.string.treatment_earlier)
                    } else {
                        stringResource(R.string.treatment_at, Clock.time(at.epochSecond, zone = zone))
                    },
                )
            },
        )
    }
}

@Composable
private fun LockRow(lock: LockInfo) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(
            stringResource(R.string.treatment_lock_closes, lock.seconds),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = lock.onUnlock) { Text(stringResource(R.string.treatment_unlock)) }
    }
}

@Composable
private fun TimePickDialog(
    initial: LocalTime,
    onPick: (LocalTime) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initial.hour, initial.minute, Clock.hour24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.treatment_pick_title)) },
        text = { TimePicker(state) },
        confirmButton = {
            TextButton(onClick = { onPick(LocalTime.of(state.hour, state.minute)) }) { Text(stringResource(R.string.treatment_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.treatment_cancel)) } },
        modifier = Modifier.testTag("treatment-time"),
    )
}

private const val STACK_FONT_SCALE = 1.3f
