// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.text.format.DateFormat
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.widgets.RecentHistory
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.TreatmentMarks
import java.util.Calendar

/** One entry the dialog creates; [at] = epoch s. */
data class NewEntry(val k: String, val a: Double, val note: String, val at: Long)

private const val MAX_UNITS = 100.0
private const val MAX_GRAMS = 500.0
private const val NOTE_BYTES = 100

/** "4", "4.5" (insulin, 0.1u steps) or whole grams; null = empty; NaN = not a valid amount. */
private fun parse(s: String, grams: Boolean): Double? {
    val t = s.trim().replace(',', '.')
    if (t.isEmpty()) return null
    val v = t.toDoubleOrNull() ?: return Double.NaN
    val max = if (grams) MAX_GRAMS else MAX_UNITS
    return if (v <= 0 || v > max) Double.NaN else if (grams) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
}

/**
 * Add insulin, carbs and a note in one go: rapid, long-acting and carbs side by side, a note below, all optional;
 * each filled field becomes its own entry at the chosen time (default now). Shown by the home screen's *Add treatment*
 * and, over whatever app is in front, by a tap on a widget or the glucose notification ([QuickTreatmentActivity]).
 *
 * A form with several fields is a custom `Dialog` (developer.android.com/develop/ui/compose/components/dialog: "if you
 * want to create a more complex dialog, perhaps with forms and multiple buttons, you should use `Dialog` with custom
 * content"), laid out like an M3 basic dialog: 28 dp corners, surface-container-high, 24 dp padding, start-aligned
 * title, end-aligned actions with the confirming one last. The first field takes focus so the number pad is up at once
 * (carbs instead while the glucose is low: [AlertStatus.level] on the low side, where eating is what's needed); the
 * keyboard's Next walks rapid → long → carbs → note, and Done on the note saves. Units are lowercase with no space
 * ("4.5u", "30g").
 *
 * [onOpenApp] adds an *Open Sukrara* action (for the quick dialog, where tapping no longer opens the app).
 * [inWindow] draws the dialog surface in the caller's own window instead of a `Dialog` window: the quick activity
 * draws its own scrim (a `Dialog` window over a translucent activity got neither the dim nor the keyboard).
 */
@Composable
fun TreatmentDialog(onDismiss: () -> Unit, onSave: (List<NewEntry>) -> Unit, onOpenApp: (() -> Unit)? = null, inWindow: Boolean = false) {
    var rapid by rememberSaveable { mutableStateOf("") }
    var long by rememberSaveable { mutableStateOf("") }
    var carbs by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    /** null = now (resolved when saving); else epoch s. */
    var at by rememberSaveable { mutableStateOf<Long?>(null) }
    var pickTime by remember { mutableStateOf(false) }

    val r = parse(rapid, grams = false)
    val l = parse(long, grams = false)
    val c = parse(carbs, grams = true)
    val n = note.trim()
    val invalid = listOf(r, l, c).any { it != null && it.isNaN() }
    val entries = { t: Long ->
        buildList {
            r?.let { add(NewEntry("rapid", it, "", t)) }
            l?.let { add(NewEntry("long", it, "", t)) }
            c?.let { add(NewEntry("carbs", it, "", t)) }
            if (n.isNotEmpty()) add(NewEntry("note", 0.0, Treatment.clip(n, NOTE_BYTES), t))
        }
    }
    val canSave = !invalid && entries(0).isNotEmpty()
    val save = { if (canSave) { onSave(entries(at ?: (System.currentTimeMillis() / 1000))); onDismiss() } }

    val ctx = LocalContext.current
    val nowS = System.currentTimeMillis() / 1000
    val quiet = Alerts.quietNote(ctx, r?.isNaN() == false, c?.isNaN() == false, at ?: nowS, nowS)
    var shownQuiet by remember { mutableStateOf("") }   // keeps the text while the line fades out
    if (quiet != null) shownQuiet = quiet

    val first = remember { FocusRequester() }
    val carbsFocus = remember { FocusRequester() }
    // low now (the latest reading, the alert rules' low thresholds): the carbs field takes the focus
    val low = remember {
        AlertStatus.level(RecentHistory.readings(ctx).lastOrNull(), Alerts.settings.value, nowS) in Kind.LOW_SIDE
    }
    LaunchedEffect(Unit) { (if (low) carbsFocus else first).requestFocus() }
    val pal = TreatmentMarks.palette(isSystemInDarkTheme())

    val card: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(min = 280.dp, max = 560.dp).fillMaxWidth(),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.treatment_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    AssistChip(onClick = { pickTime = true }, label = { Swap(at) { Text(timeLabel(it)) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(stringResource(R.string.treatment_rapid), "u", rapid, r, Color(pal.rapid), "rapid", Modifier.weight(1f).focusRequester(first)) { rapid = it }
                    AmountField(stringResource(R.string.treatment_long), "u", long, l, Color(pal.long), "long", Modifier.weight(1f)) { long = it }
                    AmountField(stringResource(R.string.treatment_carbs), "g", carbs, c, Color(pal.carbs), "carbs", Modifier.weight(1f).focusRequester(carbsFocus)) { carbs = it }
                }
                OutlinedTextField(
                    // one line of text that wraps (a hardware Enter mustn't add a line break)
                    note, { v -> v.replace("\n", " ").let { if (it.toByteArray().size <= NOTE_BYTES) note = it } }, Modifier.fillMaxWidth(),
                    label = { Row(verticalAlignment = Alignment.CenterVertically) { KindMark("note", Color(pal.note)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.treatment_note)) } },
                    placeholder = { Text(stringResource(R.string.treatment_note_placeholder)) },
                    maxLines = 3,
                    supportingText = if (note.toByteArray().size > NOTE_BYTES - 20) ({ Text("${note.toByteArray().size} / $NOTE_BYTES") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                Reveal(quiet != null) {
                    Text(shownQuiet, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Reveal(invalid) {
                    Text(stringResource(R.string.treatment_limits, MAX_UNITS.toInt(), MAX_GRAMS.toInt()),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    onOpenApp?.let { TextButton(onClick = it) { Text(stringResource(R.string.treatment_open_app)) } }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.treatment_cancel)) }
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = save, enabled = canSave) { Text(stringResource(R.string.treatment_save)) }
                }
            }
        }
    }
    if (inWindow) card()
    else Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) { card() }
    if (pickTime) TimeDialog(at, onDismiss = { pickTime = false }) { at = it; pickTime = false }
}

@Composable
private fun AmountField(label: String, unit: String, value: String, parsed: Double?, color: Color, kind: String,
                        modifier: Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { v -> onChange(v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)) }, modifier,
        label = { Row(verticalAlignment = Alignment.CenterVertically) { KindMark(kind, color); Spacer(Modifier.size(6.dp)); Text(label) } },
        suffix = { Text(unit) },
        singleLine = true,
        isError = parsed?.isNaN() == true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = if (unit == "g") KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
}

/** The same marker the charts draw for this kind (TreatmentMarks): rapid ▼ filled, long ▽ outlined, carbs ●, note ◆. */
@Composable
fun KindMark(kind: String, color: Color) {
    Canvas(Modifier.size(10.dp)) {
        val w = size.width
        when (kind) {
            "rapid", "long" -> {
                val p = Path().apply { moveTo(0f, w * 0.1f); lineTo(w, w * 0.1f); lineTo(w / 2, w * 0.95f); close() }
                if (kind == "rapid") drawPath(p, color) else drawPath(p, color, style = Stroke(w * 0.16f))
            }
            "carbs" -> drawCircle(color, w / 2, Offset(w / 2, w / 2))
            "sensor" -> { drawCircle(color, w * 0.42f, Offset(w / 2, w / 2), style = Stroke(w * 0.16f)); drawCircle(color, w * 0.16f, Offset(w / 2, w / 2)) }
            else -> drawPath(Path().apply { moveTo(w / 2, 0f); lineTo(w, w / 2); lineTo(w / 2, w); lineTo(0f, w / 2); close() }, color)
        }
    }
}

@Composable
private fun timeLabel(at: Long?): String {
    val ctx = LocalContext.current
    if (at == null) return stringResource(R.string.treatment_time_now)
    val fmt = app.mauri.sukrara.graph.Clock.time(at, DateFormat.is24HourFormat(ctx))
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val day = Calendar.getInstance().apply { timeInMillis = at * 1000 }.get(Calendar.DAY_OF_YEAR)
    return stringResource(if (day == today) R.string.treatment_time_today else R.string.treatment_time_yesterday, fmt)
}

/** M3 time picker; a time later than now means yesterday (entries are logged after the fact, never ahead). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(at: Long?, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    val ctx = LocalContext.current
    val cal = Calendar.getInstance().apply { at?.let { timeInMillis = it * 1000 } }
    val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE), DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.treatment_time_title)) },
        text = { TimePicker(state) },
        confirmButton = {
            Button(onClick = {
                val now = Calendar.getInstance()
                val pick = (now.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, state.hour); set(Calendar.MINUTE, state.minute); set(Calendar.SECOND, 0)
                    if (after(now)) add(Calendar.DAY_OF_YEAR, -1)
                }
                // within the current minute = now
                onPick(if (now.timeInMillis - pick.timeInMillis < 60_000) null else pick.timeInMillis / 1000)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null) }) { Text(stringResource(R.string.treatment_time_now)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.treatment_cancel)) }
            }
        },
    )
}
