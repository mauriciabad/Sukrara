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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldLabelPosition
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.widgets.RecentHistory
import app.mauri.sukrara.treatments.NewEntry
import app.mauri.sukrara.treatments.TreatmentForm
import app.mauri.sukrara.treatments.TreatmentForm.MAX_GRAMS
import app.mauri.sukrara.treatments.TreatmentForm.MAX_UNITS
import app.mauri.sukrara.treatments.TreatmentForm.NOTE_BYTES
import app.mauri.sukrara.treatments.TreatmentMarks
import java.util.Calendar
import kotlinx.coroutines.launch

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
 * [focus] (`carbs` / `rapid`): the field the card's *Eat carbs* / *Take insulin* pill asks for ([PillAction]); null =
 * the rule above. Cancel, back and a tap outside close it with nothing saved, back where the user was.
 *
 * [onOpenApp] adds an *Open Sukrara* action (for the quick dialog, where tapping no longer opens the app).
 * [inWindow] draws the dialog surface in the caller's own window instead of a `Dialog` window: the quick activity
 * draws its own scrim (a `Dialog` window over a translucent activity got neither the dim nor the keyboard).
 */
@Composable
fun TreatmentDialog(onDismiss: () -> Unit, onSave: (List<NewEntry>) -> Unit, onOpenApp: (() -> Unit)? = null, inWindow: Boolean = false,
                    focus: String? = null, initialAt: Long? = null) {
    val rapidState = rememberTextFieldState()
    val longState = rememberTextFieldState()
    val carbsState = rememberTextFieldState()
    val rapid = rapidState.text.toString()
    val long = longState.text.toString()
    val carbs = carbsState.text.toString()
    var note by rememberSaveable { mutableStateOf("") }
    /** null = now (resolved when saving); else epoch s. */
    var at by rememberSaveable { mutableStateOf(initialAt) }
    /** Minutes before the moment of saving (the picker's minutes-ago mode), instead of [at]; null = not relative. */
    var ago by rememberSaveable { mutableStateOf<Int?>(null) }
    /** When the entries happen, for a save or the quiet line at [now] (epoch s). */
    val whenAt = { now: Long -> ago?.let { TreatmentForm.agoTime(now, it) } ?: at ?: now }
    var pickTime by remember { mutableStateOf(false) }

    val r = TreatmentForm.parse(rapid, grams = false)
    val l = TreatmentForm.parse(long, grams = false)
    val c = TreatmentForm.parse(carbs, grams = true)
    val invalid = TreatmentForm.invalid(rapid, long, carbs)
    val entries = { t: Long -> TreatmentForm.entries(rapid, long, carbs, note, t).orEmpty() }
    val canSave = !invalid && entries(0).isNotEmpty()
    // the pen read into the dialog (PRODUCT §4.6b): its amount, roles, and the text it put in the field
    var fill by remember { mutableStateOf<app.mauri.sukrara.pen.Pens.Fill?>(null) }
    var fillText by remember { mutableStateOf("") }
    val fillRoles = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val penField = fill?.let { if (it.kind == "long") longState else rapidState }
    val penActive = fill != null && penField?.text?.toString() == fillText
    var dup by remember { mutableStateOf<Pair<app.mauri.sukrara.treatments.Treatment, List<NewEntry>>?>(null) }
    val ctx0 = LocalContext.current
    val save = { if (canSave) {
        val list = entries(whenAt(System.currentTimeMillis() / 1000))
        val f = fill
        if (penActive && f != null) {
            // the pen's amount is logged as the pen entry, not as a typed one
            app.mauri.sukrara.pen.Pens.saveFill(ctx0, f, fillRoles.toMap())
            onSave(list.filter { it.k != f.kind }); onDismiss()
        } else {
            val hit = PenDuplicate.find(ctx0, list)
            if (hit == null) { onSave(list); onDismiss(); if (list.any { it.k == "rapid" }) PenSnack.typedRapid(ctx0) }
            else {
                // §4.6: only the insulin waits; carbs and the note are saved either way
                val (ins, rest) = list.partition { it.k == "rapid" || it.k == "long" }
                if (rest.isNotEmpty()) { onSave(rest); carbsState.clearText(); note = "" }
                dup = hit to ins
            }
        }
    } }

    val ctx = LocalContext.current
    val nowS = System.currentTimeMillis() / 1000
    val quiet = Alerts.quietNote(ctx, r?.isNaN() == false, c?.isNaN() == false, whenAt(nowS), nowS)
    var shownQuiet by remember { mutableStateOf("") }   // keeps the text while the line fades out
    if (quiet != null) shownQuiet = quiet

    val first = remember { FocusRequester() }
    val carbsFocus = remember { FocusRequester() }
    val longFocus = remember { FocusRequester() }
    // low now (the latest reading, the alert rules' low thresholds): the carbs field takes the focus
    val low = remember {
        AlertStatus.level(RecentHistory.readings(ctx).lastOrNull(), Alerts.settings.value, nowS) in Kind.LOW_SIDE
    }
    // [focus] from the card's pill (Eat carbs → carbs, Take insulin → rapid) wins over the low rule
    LaunchedEffect(Unit) { (if (focus == "carbs" || focus == null && low) carbsFocus else if (focus == "long") longFocus else first).requestFocus() }
    val fillScope = androidx.compose.runtime.rememberCoroutineScope()
    PenReaderEffect { f ->
        fillRoles.clear(); fillRoles.putAll(f.primed)
        val field = if (f.kind == "long") longState else rapidState
        val final = app.mauri.sukrara.pen.PenWords.number(f.a)
        at = f.t; ago = null
        fillScope.launch {
            // the amount types itself into the field in the pen dial's 0.5u steps (350 ms); reduced motion: at once
            if (!app.mauri.sukrara.pen.PenHaptics.reducedMotion(ctx0) && f.a >= 1) {
                val steps = (f.a / 0.5).toInt().coerceAtMost(30)
                for (k in 1..steps) { val v = k * 0.5; fillText = app.mauri.sukrara.pen.PenWords.number(v); field.setTextAndPlaceCursorAtEnd(fillText); kotlinx.coroutines.delay(350L / steps) }
            }
            fillText = final; field.setTextAndPlaceCursorAtEnd(final)
            fill = f
        }
    }
    val pal = TreatmentMarks.palette(isSystemInDarkTheme())

    val card: @Composable () -> Unit = {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.padding(horizontal = 16.dp).widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().testTag("treatment_card"),
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.treatment_title), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    PenReaderSign()
                    AssistChip(onClick = { pickTime = true }, label = { Swap(at to ago) { (a, g) -> Text(timeLabel(a, g)) } })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmountField(stringResource(R.string.treatment_rapid), "u", rapidState, r, Color(pal.rapid), "rapid", Modifier.weight(1f).focusRequester(first).testTag("treatment_rapid"))
                    AmountField(stringResource(R.string.treatment_long), "u", longState, l, Color(pal.long), "long", Modifier.weight(1f).focusRequester(longFocus).testTag("treatment_long"))
                    AmountField(stringResource(R.string.treatment_carbs), "g", carbsState, c, Color(pal.carbs), "carbs", Modifier.weight(1f).focusRequester(carbsFocus).testTag("treatment_carbs"))
                }
                OutlinedTextField(
                    // one line of text that wraps (a hardware Enter mustn't add a line break)
                    note, { v -> v.replace("\n", " ").let { if (it.toByteArray().size <= NOTE_BYTES) note = it } }, Modifier.fillMaxWidth().testTag("treatment_note"),
                    label = { Row(verticalAlignment = Alignment.CenterVertically) { KindMark("note", Color(pal.note)); Spacer(Modifier.size(6.dp)); Text(stringResource(R.string.treatment_note)) } },
                    placeholder = { Text(stringResource(R.string.treatment_note_placeholder)) },
                    maxLines = 3,
                    supportingText = if (note.toByteArray().size > NOTE_BYTES - 20) ({ Text("${note.toByteArray().size} / $NOTE_BYTES") }) else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                )
                // the pen's supporting line slides in (expandVertically)
                androidx.compose.animation.AnimatedVisibility(fill != null && penActive, enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()) { fill?.let { f -> PenFillLine(f, fillRoles) } }
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
    if (pickTime) TimeDialog(at, ago, onDismiss = { pickTime = false }) { a, g -> at = a; ago = g; pickTime = false }
    dup?.let { (hit, ins) ->
        val w = app.mauri.sukrara.pen.PenWords(ctx0)
        AlertDialog(
            onDismissRequest = { dup = null },
            title = { Text(stringResource(R.string.pen_dup_title)) },
            text = { Text(stringResource(R.string.pen_at, ctx0.getString(if (hit.k == "long") R.string.treatments_row_long else R.string.treatments_row_rapid, w.u(hit.a)),
                app.mauri.sukrara.graph.Clock.time(hit.t))) },
            confirmButton = { Button(onClick = { onSave(ins); dup = null; onDismiss() }) { Text(stringResource(R.string.pen_dup_log)) } },
            dismissButton = { TextButton(onClick = { dup = null }) { Text(stringResource(R.string.treatment_cancel)) } },
        )
    }
}

@Composable
private fun AmountField(label: String, unit: String, state: TextFieldState, parsed: Double?, color: Color, kind: String,
                        modifier: Modifier) {
    OutlinedTextField(
        state, modifier,
        inputTransformation = AmountInput,
        // The label always sits small on the outline (never inside the empty field) and keeps to one line: a third of the
        // dialog is too narrow for it at body size, so it wrapped there and the field was taller unfocused than
        // focused; every focus change resized the field and the dialog (user, 2026-10-21). The unit shows throughout.
        labelPosition = TextFieldLabelPosition.Attached(alwaysMinimize = true),
        label = { Row(verticalAlignment = Alignment.CenterVertically) { KindMark(kind, color); Spacer(Modifier.size(6.dp)); Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        suffix = { Text(unit) },
        lineLimits = TextFieldLineLimits.SingleLine,
        isError = parsed?.isNaN() == true,
        textStyle = MaterialTheme.typography.titleLarge,
        keyboardOptions = KeyboardOptions(keyboardType = if (unit == "g") KeyboardType.Number else KeyboardType.Decimal, imeAction = ImeAction.Next),
    )
}

/** Keeps what [TreatmentForm.filterAmount] keeps of an edit. */
private val AmountInput = InputTransformation {
    val typed = toString()
    val kept = TreatmentForm.filterAmount(typed)
    if (kept != typed) replace(0, length, kept)
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
private fun timeLabel(at: Long?, ago: Int?): String {
    if (ago != null) return stringResource(R.string.treatment_time_ago, app.mauri.sukrara.core.Durations.text(ago))
    if (at == null) return stringResource(R.string.treatment_time_now)
    val fmt = app.mauri.sukrara.graph.Clock.time(at)
    val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    val day = Calendar.getInstance().apply { timeInMillis = at * 1000 }.get(Calendar.DAY_OF_YEAR)
    return stringResource(if (day == today) R.string.treatment_time_today else R.string.treatment_time_yesterday, fmt)
}

/**
 * When it happened: an M3 segmented button switches between the time picker (a time later than now means yesterday,
 * [TreatmentForm.pickTime]) and minutes ago — a number field with one-tap 5 / 15 / 30 / 60 chips that fill it
 * ([TreatmentForm.agoMinutes], under a day like the picker; resolved when saving). M3 segmented buttons "help people
 * select options, switch views"; the chips are filter chips, the selected one checked.
 * [onPick] gets (time, null), (null, minutes ago) or (null, null) = now.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(at: Long?, ago: Int?, onDismiss: () -> Unit, onPick: (Long?, Int?) -> Unit) {
    val cal = Calendar.getInstance().apply { at?.let { timeInMillis = it * 1000 } }
    val state = rememberTimePickerState(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    var relative by rememberSaveable { mutableStateOf(ago != null) }
    var minutes by rememberSaveable { mutableStateOf(ago?.toString() ?: "") }
    val m = TreatmentForm.agoMinutes(minutes)
    val field = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.treatment_time_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(!relative, { relative = false }, SegmentedButtonDefaults.itemShape(0, 2), Modifier.testTag("when_time")) {
                        Text(stringResource(R.string.treatment_time_mode_time))
                    }
                    SegmentedButton(relative, { relative = true }, SegmentedButtonDefaults.itemShape(1, 2), Modifier.testTag("when_ago")) {
                        Text(stringResource(R.string.treatment_time_mode_ago))
                    }
                }
                if (!relative) TimePicker(state)
                else {
                    OutlinedTextField(
                        // a fixed width: fillMaxWidth here never settled inside AlertDialog on a 411 dp screen (TreatmentTimeTest)
                        minutes, { minutes = TreatmentForm.filterMinutes(it) }, Modifier.width(200.dp).focusRequester(field).testTag("when_minutes"),
                        suffix = { Text(stringResource(R.string.unit_min)) },
                        singleLine = true,
                        isError = minutes.isNotEmpty() && m == null,
                        textStyle = MaterialTheme.typography.headlineMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { m?.let { onPick(null, it.takeIf { it > 0 }) } }),
                    )
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        for (q in listOf(5, 15, 30, 60)) FilterChip(m == q, { minutes = "$q" }, { Text(app.mauri.sukrara.core.Durations.text(q), maxLines = 1) })
                    }
                    LaunchedEffect(Unit) { field.requestFocus() }
                }
            }
        },
        confirmButton = {
            Button(enabled = !relative || m != null, onClick = {
                if (relative) onPick(null, m?.takeIf { it > 0 })
                else onPick(TreatmentForm.pickTime(Calendar.getInstance(), state.hour, state.minute), null)
            }) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onPick(null, null) }) { Text(stringResource(R.string.treatment_time_now)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.treatment_cancel)) }
            }
        },
    )
}
