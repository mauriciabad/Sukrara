// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.pen.PenEngine
import app.mauri.sukrara.pen.PenProfiles
import app.mauri.sukrara.pen.PenWords
import app.mauri.sukrara.pen.Pens
import app.mauri.sukrara.pen.Question
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * The pen's surfaces in Sukrara (investigation 29, spikes/novopen-android/PRODUCT.md §4): the read card (from any app),
 * a pen entry's sheet (see, correct, delete), the two question sheets, the home card and the undo snackbar. The visual
 * language: actions are pills (M3 buttons), red only for errors, no helper text in dialogs.
 */

/** The undo after a correction or a removal (PRODUCT §4.3), over whichever screen it happened on. */
object PenSnack {
    data class Snack(val message: String, val undo: () -> Unit)
    val pending = MutableStateFlow<Snack?>(null)
}

@Composable
fun PenUndoSnackbar(host: SnackbarHostState = remember { SnackbarHostState() }) {
    val s by PenSnack.pending.collectAsState()
    val undo = stringResource(R.string.pen_undo)
    LaunchedEffect(s) {
        val snack = s ?: return@LaunchedEffect
        if (host.showSnackbar(snack.message, actionLabel = undo, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) snack.undo()
        PenSnack.pending.value = null
    }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.BottomCenter) { SnackbarHost(host) }
}

// ============================================================================================ the read card

/**
 * The card a pen tap shows over any app (PRODUCT §4.1): 16 dp side margins, 28 dp corners, surface container high (error
 * container for a failed read, the only red), the title a polite live region. At most two pills.
 */
@Composable
fun PenReadCard(s: Pens.ReadState, onClose: () -> Unit, onOpen: (String, String) -> Unit, onInteract: () -> Unit = {}, onA11y: (Boolean) -> Unit = {}) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    Pens.ledger.collectAsState().value
    Treat.store.items.collectAsState().value
    val card = w.card(s)
    val r = s.report
    val entry = r?.let { (it.created + it.merged + it.updated).firstNotNullOfOrNull { id -> Treat.store.entry(id)?.second?.takeIf { e -> !e.del } } }
    val asked = r?.asked?.firstNotNullOfOrNull { id -> Pens.ledger.value.open().firstOrNull { it.id == id } }
    val cs = MaterialTheme.colorScheme
    Card(
        Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding().onFocusChanged { onA11y(it.hasFocus) },
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = if (card.error) cs.errorContainer else cs.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().then(if (entry != null && !s.undone) Modifier.clickable { onOpen("penEntry", entry.id) } else Modifier),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (s.stage == "reading") CircularProgressIndicator(Modifier.size(40.dp), strokeWidth = 3.dp)
                    Icon(painterResource(when { card.error -> R.drawable.ic_sym_warning; s.stage == "reading" -> R.drawable.ic_dia_pen
                        r != null && entry != null && !s.undone -> R.drawable.ic_sym_check; else -> R.drawable.ic_dia_pen }),
                        contentDescription = null, tint = if (card.error) cs.error else cs.onSurfaceVariant, modifier = Modifier.size(24.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(card.title, style = MaterialTheme.typography.titleMedium, color = if (card.error) cs.onErrorContainer else cs.onSurface,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    card.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (card.error) cs.onErrorContainer else cs.onSurfaceVariant) }
                }
            }
            val pills = pills(ctx, s, entry, asked, onOpen, onClose)
            if (pills.isNotEmpty()) Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pills.forEachIndexed { i, (label, act) ->
                    val click = { onInteract(); act() }
                    if (i == pills.lastIndex) Button(onClick = click) { Text(label) } else FilledTonalButton(onClick = click) { Text(label) }
                }
            }
        }
    }
}

/** The card's pills (label, action), at most two: PRODUCT §4.1's table. */
private fun pills(ctx: Context, s: Pens.ReadState, entry: Treatment?, asked: Question?, onOpen: (String, String) -> Unit, onClose: () -> Unit): List<Pair<String, () -> Unit>> {
    val w = PenWords(ctx)
    val r = s.report
    val serial = s.serial ?: return emptyList()
    val check = ctx.getString(R.string.pen_check) to { onOpen("penQuestion", asked?.id ?: "") }
    val undo = ctx.getString(R.string.pen_undo) to { s.undo?.let { Pens.undoRead(ctx, it) }; app.mauri.sukrara.pen.PenNotifier.cancelRead(ctx); Unit }
    return when {
        s.undone || s.stage == "reading" || s.stage == "failed" -> emptyList()
        s.stage == "notusable" -> listOf(ctx.getString(R.string.pen_open) to { onOpen("penPage", serial) })
        s.stage == "check" -> listOf(ctx.getString(R.string.pen_set_up) to { onOpen("penSetup", serial) })
        r == null -> emptyList()
        r.first && !r.returning && entry == null -> listOfNotNull(
            r.offer?.let { o -> ctx.getString(R.string.pen_log_amount, w.u(o.a)) to {
                val st = Pens.logDoses(ctx, serial, o.doses)
                Pens.readState.value = s.copy(report = st.report.copy(first = false), undo = st.undo)
            } },
            ctx.getString(R.string.pen_set_up) to { onOpen("penSetup", serial) })
        r.clockChanged && entry == null -> listOf(ctx.getString(R.string.pen_check) to { onOpen("penHistory", serial) })
        entry != null && asked != null -> listOf(undo, check)
        entry != null && r.bigSince != null -> listOf(ctx.getString(R.string.pen_review) to { onOpen("penReview", serial) }, undo)
        entry != null -> listOf(ctx.getString(R.string.pen_add_treatment) to {
            ctx.startActivity(PenWords.addTreatment(ctx, entry).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); onClose()
        }, undo)
        asked != null && asked.type == PenEngine.DOSES -> listOfNotNull(
            w.suggestion(asked)?.let { a -> ctx.getString(R.string.pen_log_amount, a) to {
                Pens.acceptSuggestion(ctx, asked.id); onClose() } }, check)
        asked != null -> listOf(check)
        else -> emptyList()
    }
}

// ============================================================================================ a pen entry's sheet

/** Who the sheet says a pen entry came from: "From your pen", or the pen's name when there are two. */
@Composable
private fun fromLine(tr: Treatment): String {
    val pens = Pens.ledger.collectAsState().value.pens
    val name = tr.pen?.let { pens[it]?.name }
    return if (pens.size > 1 && !name.isNullOrEmpty()) stringResource(R.string.pen_entry_from_named, name) else stringResource(R.string.pen_entry_from)
}

/**
 * A pen entry (PRODUCT §4.3): the total and time, where it came from, every dose with a *Priming* / *Injected*
 * segmented button (one TalkBack node per dose with *Mark as …* actions), the note, the merge line; *Delete*, *Add
 * treatment*, *Done* — or, once a dose changed, *Cancel* and *Save* (*Remove from log* when every dose is priming).
 * [readOnly]: a follower's view (the wearer's entries aren't theirs to change).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PenEntrySheet(tr: Treatment, readOnly: Boolean = false, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    val pd = tr.pd.orEmpty()
    val roles = remember(tr.id, tr.u) { mutableStateListOf(*pd.map { it.p }.toTypedArray()) }
    val changed = roles.toList() != pd.map { it.p }
    var deleting by remember { mutableStateOf(false) }
    val inj = pd.filterIndexed { i, _ -> !roles[i] }
    val a = PenEngine.r1(inj.sumOf { it.a }); val pr = PenEngine.r1(pd.filterIndexed { i, _ -> roles[i] }.sumOf { it.a })
    val t = inj.minOfOrNull { it.t } ?: tr.t
    val pen = tr.pen?.let { Pens.ledger.collectAsState().value.pens[it] }
    val untested = pen?.model?.let { PenProfiles.byId(it) }?.verified == false
    val doses = pen?.doses?.values.orEmpty()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (inj.isEmpty()) stringResource(R.string.pen_nothing_injected)
                    else ctx.getString(if (tr.k == "long") R.string.treatments_row_long else R.string.treatments_row_rapid, w.u(a)),
                    style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
                Text(Clock.time(t), style = MaterialTheme.typography.titleMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(listOfNotNull(fromLine(tr), pr.takeIf { it > 0 }?.let { stringResource(R.string.pen_primed, w.u(it)) }).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (untested) UntestedTag()
            }
            HorizontalDivider()
            pd.forEachIndexed { i, d ->
                val inc = doses.any { it.t == d.t && PenEngine.tenths(it.a) == PenEngine.tenths(d.a) && it.inc }
                DoseRow(d.t, d.a, roles[i], inc, readOnly) { roles[i] = it }
            }
            HorizontalDivider()
            if (tr.n.isNotEmpty()) Text(tr.n, style = MaterialTheme.typography.bodyMedium)
            pen?.replaced?.get(tr.id)?.let { Text(stringResource(R.string.pen_read_replaced, w.u(it.a), Clock.time(it.t)), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!readOnly) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (changed) {
                    TextButton(onClick = { roles.clear(); roles.addAll(pd.map { it.p }) }) { Text(stringResource(R.string.treatment_cancel)) }
                    Box(Modifier.weight(1f))
                    Button(onClick = {
                        val was = tr
                        if (inj.isEmpty()) {
                            Pens.remove(ctx, tr)
                            PenSnack.pending.value = PenSnack.Snack(ctx.getString(R.string.pen_removed)) { Pens.restore(ctx, was) }
                        } else {
                            Pens.correct(ctx, tr, roles.toList())
                            PenSnack.pending.value = PenSnack.Snack(ctx.getString(R.string.pen_entry_changed)) { Pens.restore(ctx, was) }
                        }
                        onDismiss()
                    }) { Text(stringResource(if (inj.isEmpty()) R.string.pen_remove else R.string.treatment_save)) }
                } else {
                    TextButton(onClick = { deleting = true }) { Text(stringResource(R.string.treatments_delete)) }
                    Box(Modifier.weight(1f))
                    FilledTonalButton(onClick = { ctx.startActivity(PenWords.addTreatment(ctx, tr)); onDismiss() }) { Text(stringResource(R.string.pen_add_treatment)) }
                    Button(onClick = onDismiss) { Text(stringResource(R.string.pen_done)) }
                }
            } else Button(onClick = onDismiss, Modifier.align(Alignment.End)) { Text(stringResource(R.string.pen_done)) }
        }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false },
        title = { Text(stringResource(R.string.treatments_delete_title)) },
        text = { Text(Clock.time(tr.t) + " · " + ctx.getString(if (tr.k == "long") R.string.treatments_row_long else R.string.treatments_row_rapid, w.u(tr.a))) },
        confirmButton = { Button(onClick = { Treat.store.delete(tr.id); Treat.uploader.kick(); deleting = false; onDismiss() }) { Text(stringResource(R.string.treatments_delete)) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text(stringResource(R.string.treatment_cancel)) } },
    )
}

/**
 * One dose: time, amount, the *Incomplete* tag, and *Priming* / *Injected* (an M3 segmented button: 2 exclusive
 * options). TalkBack reads the row as one node ("13:10, 1 unit, priming") with the custom action to flip it.
 */
@Composable
fun DoseRow(t: Long, a: Double, primed: Boolean, incomplete: Boolean, readOnly: Boolean, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    val spoken = ctx.resources.getQuantityString(R.plurals.pen_units_spoken, if (a == 1.0) 1 else 2, PenWords.number(a))
    val desc = ctx.getString(R.string.pen_dose_a11y, Clock.time(t), spoken, ctx.getString(if (primed) R.string.pen_role_priming else R.string.pen_role_injected))
    val flip = ctx.getString(if (primed) R.string.pen_mark_injected else R.string.pen_mark_priming)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = desc
        if (!readOnly) customActions = listOf(CustomAccessibilityAction(flip) { onChange(!primed); true })
    }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Clock.time(t), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(56.dp))
        Text(w.u(a), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.width(48.dp))
        if (incomplete) Text(stringResource(R.string.pen_incomplete), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f))
        if (readOnly) Text(stringResource(if (primed) R.string.pen_seg_priming else R.string.pen_seg_injected), style = MaterialTheme.typography.labelLarge)
        else SingleChoiceSegmentedButtonRow {
            SegmentedButton(primed, { onChange(true) }, SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.pen_seg_priming)) }
            SegmentedButton(!primed, { onChange(false) }, SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.pen_seg_injected)) }
        }
    }
}

// ============================================================================================ questions

/** *Priming or injected?* (PRODUCT §4.4): the doses pre-set to the suggestion, what Save logs, *Not now* / *Save*. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DosesQuestionSheet(q: Question, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    val doses = Pens.ledger.value.pens[q.serial]?.doses.orEmpty()
    val keys = q.keys.filter { it in doses }
    val roles = remember(q.id) { mutableStateListOf(*keys.map { doses.getValue(it).p }.toTypedArray()) }
    val inj = keys.filterIndexed { i, _ -> !roles[i] }.map { doses.getValue(it) }
    val primed = keys.filterIndexed { i, _ -> roles[i] }.map { doses.getValue(it) }
    val pr = w.u(PenEngine.r1(primed.sumOf { it.a }))
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pen_q_doses_title), style = MaterialTheme.typography.headlineSmall)
            HorizontalDivider()
            keys.forEachIndexed { i, k -> val d = doses.getValue(k); DoseRow(d.t, d.a, roles[i], d.inc, false) { roles[i] = it } }
            HorizontalDivider()
            Text(when {
                inj.isEmpty() -> stringResource(R.string.pen_q_nothing, stringResource(R.string.pen_primed, pr))
                primed.isEmpty() -> stringResource(R.string.pen_q_result_plain, w.u(PenEngine.r1(inj.sumOf { it.a })), Clock.time(inj.minOf { it.t }))
                else -> stringResource(R.string.pen_q_result, w.u(PenEngine.r1(inj.sumOf { it.a })), Clock.time(inj.minOf { it.t }), stringResource(R.string.pen_primed, pr))
            }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.pen_not_now)) }
                Button(onClick = { Pens.answerDoses(ctx, q.id, keys.filterIndexed { i, _ -> roles[i] }.toSet()); onDismiss() }) { Text(stringResource(R.string.treatment_save)) }
            }
        }
    }
}

/** Who typed a candidate entry: "Logged by you", "by Ana" (a writer), "on the Mac". */
private fun loggedBy(ctx: Context, w: PenWords, c: Treatment): String = when (c.o) {
    "phone" -> ctx.getString(R.string.pen_m_you, w.u(c.a), Clock.time(c.t))
    "mac" -> ctx.getString(R.string.pen_m_mac, w.u(c.a), Clock.time(c.t))
    else -> ctx.getString(R.string.pen_m_by, c.o, w.u(c.a), Clock.time(c.t))
}

/** *Same injection?* (PRODUCT §4.5): one candidate → two buttons; several → radio rows + *None of these*, *Save*. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchQuestionSheet(q: Question, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    val doses = Pens.ledger.value.pens[q.serial]?.doses.orEmpty()
    val inj = q.keys.mapNotNull { doses[it] }.filter { !it.p }
    val cands = q.cands.mapNotNull { Treat.store.entry(it)?.second }.filter { !it.del && it.pen == null }
    var pick by remember(q.id) { mutableStateOf<String?>(cands.firstOrNull()?.id) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.pen_q_same_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.pen_m_pen, w.u(PenEngine.r1(inj.sumOf { it.a })), Clock.time(inj.minOfOrNull { it.t } ?: 0)), style = MaterialTheme.typography.bodyLarge)
            if (cands.size <= 1) {
                cands.firstOrNull()?.let { Text(loggedBy(ctx, w, it), style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { Pens.answerMatch(ctx, q.id, null); onDismiss() }) { Text(stringResource(R.string.pen_m_different)) }
                    if (cands.isNotEmpty()) Button(onClick = { Pens.answerMatch(ctx, q.id, cands[0].id); onDismiss() }) { Text(stringResource(R.string.pen_m_same)) }
                }
            } else {
                Column(Modifier.selectableGroup()) {
                    (cands.map { it.id to loggedBy(ctx, w, it) } + (null to ctx.getString(R.string.pen_m_none))).forEach { (id, label) ->
                        Row(Modifier.fillMaxWidth().selectable(pick == id, role = Role.RadioButton) { pick = id }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(pick == id, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.pen_not_now)) }
                    Button(onClick = { Pens.answerMatch(ctx, q.id, pick); onDismiss() }) { Text(stringResource(R.string.treatment_save)) }
                }
            }
        }
    }
}

/** The sheet for question [id] (or the oldest open one), whichever kind it is; null = nothing to ask. */
@Composable
fun PenQuestionSheet(id: String?, onDismiss: () -> Unit) {
    val open = Pens.ledger.collectAsState().value.open()
    val q = open.firstOrNull { it.id == id } ?: open.minByOrNull { it.at }
    if (q == null) { LaunchedEffect(Unit) { onDismiss() }; return }
    if (q.type == PenEngine.DOSES) DosesQuestionSheet(q, onDismiss) else MatchQuestionSheet(q, onDismiss)
}

/**
 * The home card above the glucose card while a pen question waits (PRODUCT §4.4): like the backup card (surface
 * container, not red), the question's line and its pills; several: a count and *Check*.
 */
@Composable
fun PenQuestionCard() {
    val ctx = LocalContext.current
    val l by Pens.ledger.collectAsState()
    Treat.store.items.collectAsState().value
    val open = l.open().sortedBy { it.at }
    var sheet by remember { mutableStateOf<String?>(null) }
    RevealValue(open.firstOrNull()) { q ->
        val w = remember { PenWords(ctx) }
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val several = open.size > 1
                Text(when {
                    several -> ctx.resources.getQuantityString(R.plurals.pen_q_several, open.size, open.size)
                    q.type == PenEngine.DOSES -> stringResource(R.string.pen_q_doses_title)
                    else -> stringResource(R.string.pen_q_same_title)
                }, style = MaterialTheme.typography.titleMedium)
                if (!several) Text(if (q.type == PenEngine.DOSES) stringResource(R.string.pen_doses_at, w.doses(q), w.time(q)) else w.matchLine(q),
                    style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!several && q.type == PenEngine.DOSES) w.suggestion(q)?.let { a ->
                        FilledTonalButton(onClick = { Pens.acceptSuggestion(ctx, q.id) }) { Text(stringResource(R.string.pen_log_amount, a)) }
                    }
                    Button(onClick = { sheet = q.id }) { Text(stringResource(R.string.pen_check)) }
                }
            }
        }
    }
    sheet?.let { PenQuestionSheet(it) { sheet = null } }
}

/** A treatment from the pen (the home row and the follower list say "from the pen"). */
fun isPenEntry(tr: Treatment) = tr.pen != null && !tr.pd.isNullOrEmpty()

/** Debug and tests: the role of this install allows pen reading. */
fun penReadingHere(ctx: Context) = App.role(ctx) == "owner"
