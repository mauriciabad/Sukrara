// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.motion.Reveal
import app.mauri.sukrara.graph.CardWords
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
    ago: Int? = null,
) {
    var rapid by mutableStateOf(rapid)
    var long by mutableStateOf(long)
    var carbs by mutableStateOf(carbs)
    var note by mutableStateOf(note)
    var at by mutableStateOf(at)

    /** Minutes before the moment of saving (the *Minutes ago* mode) instead of [at]; null = not relative. */
    var ago by mutableStateOf(ago)

    /** When the entries happen if saved at [now]: minutes ago, the picked time, or null = now. */
    fun whenAt(now: Instant): Instant? = ago?.let { TreatmentForm.agoTime(now, it) } ?: at

    /** null = an amount is invalid. */
    val entries: List<NewEntry>? get() = TreatmentForm.entries(rapid, long, carbs, note)
    val canSave: Boolean get() = entries?.isNotEmpty() == true
    val hasInput: Boolean get() = listOf(rapid, long, carbs, note).any { it.isNotBlank() }

    companion object {
        val Saver: Saver<TreatmentFormState, Any> =
            listSaver(
                save = { listOf(it.rapid, it.long, it.carbs, it.note, it.at?.toEpochMilli(), it.ago) },
                restore = {
                    TreatmentFormState(
                        it[0] as String,
                        it[1] as String,
                        it[2] as String,
                        it[3] as String,
                        (it[4] as Long?)?.let(Instant::ofEpochMilli),
                        it[5] as Int?,
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
    quiet: QuietRules = QuietRules.NONE,
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
            onSave(list, form.whenAt(now()))
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
            QuietLine(form, quiet, now)
            WhenRow(form, zone, now, onPick = { picking = true })
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
        WhenDialog(
            form = form,
            zone = zone,
            now = now,
            onDone = { picking = false },
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
    now: () -> Instant,
    onPick: () -> Unit,
) {
    val words = CardWords.of(LocalContext.current.resources)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val relative = form.at != null || form.ago != null
        FilterChip(
            selected = !relative,
            onClick = {
                form.at = null
                form.ago = null
            },
            label = { Text(stringResource(R.string.treatment_now)) },
        )
        FilterChip(
            selected = relative,
            onClick = onPick,
            label = { Text(whenLabel(form, zone, now(), words)) },
            modifier = Modifier.testTag("treatment-when"),
        )
    }
}

/** The *When* chip's words: "Earlier…", "16 min ago", "At 14:05" (today) or "Yesterday 23:40". */
@Composable
private fun whenLabel(
    form: TreatmentFormState,
    zone: ZoneId,
    now: Instant,
    words: CardWords,
): String {
    val ago = form.ago
    val at = form.at
    return when {
        ago != null -> {
            stringResource(R.string.treatment_ago, Durations.text(ago.toLong(), words.durations))
        }

        at == null -> {
            stringResource(R.string.treatment_earlier)
        }

        at.atZone(zone).toLocalDate() == now.atZone(zone).toLocalDate() -> {
            stringResource(R.string.treatment_at, Clock.time(at.epochSecond, zone = zone))
        }

        else -> {
            stringResource(R.string.treatment_yesterday, Clock.time(at.epochSecond, zone = zone))
        }
    }
}

/**
 * What saving these entries quiets on this phone ("Highs quiet for 45 min · lows quiet for 15 min"), from the filled
 * fields and the chosen time; slides in and out as the fields change.
 */
@Composable
private fun QuietLine(
    form: TreatmentFormState,
    rules: QuietRules,
    now: () -> Instant,
) {
    val words = CardWords.of(LocalContext.current.resources)
    val instant = now()
    val note =
        QuietNote.of(
            rules,
            rapid = TreatmentForm.parse(form.rapid, grams = false) is TreatmentForm.Amount.Valid,
            carbs = TreatmentForm.parse(form.carbs, grams = true) is TreatmentForm.Amount.Valid,
            at = form.whenAt(instant) ?: instant,
            now = instant,
        )
    // keeps the last text while the line slides out
    var shown by remember { mutableStateOf("") }
    if (note != null) shown = quietText(note, words)
    Reveal(note != null) {
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("treatment-quiet"),
        )
    }
}

@Composable
private fun quietText(
    note: QuietNote,
    words: CardWords,
): String {
    val highs = note.highsMin?.let { Durations.text(it, words.durations) }
    val lows = note.lowsMin?.let { Durations.text(it, words.durations) }
    return when {
        highs != null && lows != null -> stringResource(R.string.treatment_quiet_both, highs, lows)
        highs != null -> stringResource(R.string.treatment_quiet_highs, highs)
        else -> stringResource(R.string.treatment_quiet_lows, lows.orEmpty())
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

/**
 * When it happened: a segmented button switches between the time picker (a time later than now means yesterday,
 * [TreatmentForm.pickTime]) and *Minutes ago*: a number field with one-tap 5 / 15 / 30 / 60 chips
 * ([TreatmentForm.agoMinutes], under a day; resolved when saving). *Now* resets it; Cancel and back keep what was set.
 */
@Composable
private fun WhenDialog(
    form: TreatmentFormState,
    zone: ZoneId,
    now: () -> Instant,
    onDone: () -> Unit,
) {
    val initial = (form.at ?: now()).atZone(zone).toLocalTime()
    val state = rememberTimePickerState(initial.hour, initial.minute, Clock.hour24)
    var relative by rememberSaveable { mutableStateOf(form.ago != null) }
    var minutes by rememberSaveable { mutableStateOf(form.ago?.toString().orEmpty()) }
    val parsed = TreatmentForm.agoMinutes(minutes)
    val field = remember { FocusRequester() }
    val words = CardWords.of(LocalContext.current.resources)

    fun pickAgo(m: Int) {
        form.ago = m.takeIf { it > 0 }
        form.at = null
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.treatment_pick_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !relative,
                        onClick = { relative = false },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        modifier = Modifier.testTag("when-time"),
                    ) { Text(stringResource(R.string.treatment_mode_time)) }
                    SegmentedButton(
                        selected = relative,
                        onClick = { relative = true },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        modifier = Modifier.testTag("when-ago"),
                    ) { Text(stringResource(R.string.treatment_mode_ago)) }
                }
                if (!relative) {
                    TimePicker(state)
                } else {
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = TreatmentForm.filterMinutes(it) },
                        modifier = Modifier.width(200.dp).focusRequester(field).testTag("when-minutes"),
                        suffix = { Text(stringResource(R.string.treatment_unit_min)) },
                        singleLine = true,
                        isError = minutes.isNotEmpty() && parsed == null,
                        textStyle = MaterialTheme.typography.headlineMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { parsed?.let(::pickAgo) }),
                    )
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        for (q in AGO_CHIPS) {
                            FilterChip(
                                selected = parsed == q,
                                onClick = { minutes = "$q" },
                                label = { Text(Durations.text(q.toLong(), words.durations), maxLines = 1) },
                            )
                        }
                    }
                    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !relative || parsed != null,
                onClick = {
                    if (relative) {
                        parsed?.let(::pickAgo)
                    } else {
                        form.at = TreatmentForm.pickTime(now(), zone, LocalTime.of(state.hour, state.minute))
                        form.ago = null
                        onDone()
                    }
                },
            ) { Text(stringResource(R.string.treatment_ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    form.at = null
                    form.ago = null
                    onDone()
                }) { Text(stringResource(R.string.treatment_now)) }
                TextButton(onClick = onDone) { Text(stringResource(R.string.treatment_cancel)) }
            }
        },
        modifier = Modifier.testTag("treatment-time"),
    )
}

/** The *Minutes ago* one-tap lengths. */
private val AGO_CHIPS = listOf(5, 15, 30, 60)

private const val STACK_FONT_SCALE = 1.3f
