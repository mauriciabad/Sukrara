// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings.pens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.model.PenCheck
import app.mauri.sukrara.core.model.PenDoseInfo
import app.mauri.sukrara.core.model.PenEntryInfo
import app.mauri.sukrara.core.model.PenInfo
import app.mauri.sukrara.core.model.PenNearEntry
import app.mauri.sukrara.core.model.PenSessionInfo
import app.mauri.sukrara.core.model.PenSessionStatus
import app.mauri.sukrara.core.model.PensPort
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.designsystem.dialog.Choice
import app.mauri.sukrara.designsystem.dialog.ChoiceDialog
import app.mauri.sukrara.designsystem.haptics.rememberHaptics
import app.mauri.sukrara.designsystem.rows.CardRow
import app.mauri.sukrara.designsystem.rows.FooterText
import app.mauri.sukrara.designsystem.rows.InfoRow
import app.mauri.sukrara.designsystem.rows.NavigationRow
import app.mauri.sukrara.designsystem.rows.SettingsGroup
import app.mauri.sukrara.designsystem.rows.ValueRow
import app.mauri.sukrara.feature.settings.R
import app.mauri.sukrara.feature.settings.SettingsPage
import app.mauri.sukrara.graph.Clock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import app.mauri.sukrara.core.designsystem.R as DsR

/** `8.5u`: dot, lowercase u, no space (the pen's own way of writing doses). */
internal fun units(a: Double) = (if (a % 1.0 == 0.0) "${a.toLong()}" else String.format(Locale.ROOT, "%.1f", a)) + "u"

/** A held read older than this asks for a new tap: the pen's display counts hours from now, not from the read. */
private const val CHECK_FRESH_S = 600L

private val Offer = SchemaSettings.number("pens.offer")

/**
 * Android blocks the pen taps (reading Ask or On): a tonal card with the warning in the error colour (nothing failed,
 * the user has to act), *Allow* opens Android's screen. Home shows the same card with *Turn off*.
 */
@Composable
internal fun TapsBlockedCard(onAllow: () -> Unit) {
    val haptics = rememberHaptics()
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.st_pens_blocked),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.st_pens_blocked_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = {
                haptics.confirm()
                onAllow()
            }, Modifier.align(Alignment.End)) { Text(stringResource(R.string.st_pens_allow)) }
        }
    }
}

/** What a pen's row says under its name: *Rapid · read 13:14*, *Review its settings*, or its display check. */
@Composable
internal fun penSummary(p: PenInfo): String =
    when {
        p.untested && p.check == PenCheck.Failed -> {
            stringResource(R.string.st_pen_not_usable)
        }

        p.untested && p.check == PenCheck.None -> {
            stringResource(R.string.st_pen_check_to_start)
        }

        else -> {
            val kind = stringResource(if (p.kind == "long") R.string.st_pen_kind_long else R.string.st_pen_kind_rapid)
            val line = stringResource(R.string.st_pen_row, kind, Clock.time(p.lastScan))
            if (p.opened) line else line + " · " + stringResource(R.string.st_pen_review_hint)
        }
    }

/** One pen's page and its *Doses read*. */
class PenViewModel(
    private val port: PensPort,
    val serial: String,
    settings: SettingsRepository,
) : ViewModel() {
    val pen: StateFlow<PenInfo?> =
        port.pens.map { l -> l.firstOrNull { it.serial == serial } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The pen isn't known (any more): its page closes. */
    val gone: StateFlow<Boolean> =
        port.pens.map { l -> l.none { it.serial == serial } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val sessions: StateFlow<List<PenSessionInfo>?> =
        port.sessions(serial).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** A new pen's newest injection before Sukrara, under the offer window: *Log it*. */
    val offer: StateFlow<PenSessionInfo?> =
        combine(sessions, settings.flow(Offer)) { s, h ->
            val now = System.currentTimeMillis() / 1000
            s
                ?.filter { it.status == PenSessionStatus.Before && it.units > 0 && now - it.t < (h * 3600).toLong() }
                ?.maxByOrNull { it.t }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** What the page's parts do (the sheets and cards get this, never the ViewModel). */
    val actions = PenActions(port, serial, viewModelScope)

    fun opened() = actions.opened()

    fun forget() = actions.forget()
}

/** The page's one-line results (the snackbar): a message and, for a change, its *Undo*. */
data class PenSnack(
    val message: Int,
    val undo: PenEntryInfo? = null,
)

/** One pen's actions over the port, in the page's scope; results go to [snack]. */
class PenActions(
    private val port: PensPort,
    private val serial: String,
    private val scope: kotlinx.coroutines.CoroutineScope,
) {
    val snack = kotlinx.coroutines.flow.MutableStateFlow<PenSnack?>(null)

    fun opened() = launch { port.opened(serial) }

    fun rename(name: String) = launch { port.rename(serial, name) }

    fun setKind(kind: String) = launch { port.setKind(serial, kind) }

    fun forget() = launch { port.forget(serial) }

    fun checkPassed(readAt: Long) =
        launch {
            if (!port.checkPassed(
                    serial,
                    readAt,
                )
            ) {
                snack.value = PenSnack(R.string.st_pen_check_tap_again)
            }
        }

    fun checkFailed() = launch { port.checkFailed(serial) }

    fun checkAgain() = launch { port.checkAgain(serial) }

    suspend fun match(
        t: Long,
        a: Double,
    ): PenNearEntry? = port.match(serial, t, a)

    suspend fun sessionEntry(t: Long): PenNearEntry? = port.sessionEntry(serial, t)

    suspend fun entry(id: String): PenEntryInfo? = port.entry(id)

    fun logDoses(doses: Map<String, Boolean>) =
        launch { snack.value = PenSnack(if (port.logDoses(serial, doses)) R.string.st_pen_logged else R.string.st_pen_changed_since) }

    fun join(
        doses: Map<String, Boolean>,
        id: String,
    ) = launch { snack.value = PenSnack(if (port.joinEntry(serial, doses, id)) R.string.st_pen_logged else R.string.st_pen_changed_since) }

    fun correct(
        e: PenEntryInfo,
        primed: List<Boolean>,
    ) = launch {
        val was = port.correct(e, primed)
        snack.value =
            when {
                was == null -> PenSnack(R.string.st_pen_changed_since)
                primed.all { it } -> PenSnack(R.string.st_pen_removed, was)
                else -> PenSnack(R.string.st_pen_entry_changed, was)
            }
    }

    fun restore(was: PenEntryInfo) = launch { if (!port.restore(was)) snack.value = PenSnack(R.string.st_pen_undo_failed) }

    fun accept(
        q: String,
        keys: List<String>,
    ) = launch { if (!port.acceptSuggestion(q, keys)) snack.value = PenSnack(R.string.st_pen_changed_since) }

    fun allPriming(
        q: String,
        keys: List<String>,
    ) = launch { if (!port.allPriming(q, keys)) snack.value = PenSnack(R.string.st_pen_changed_since) }

    fun same(
        q: String,
        keys: List<String>,
        same: Boolean,
    ) = launch { if (!port.answerMatch(q, keys, same)) snack.value = PenSnack(R.string.st_pen_changed_since) }

    /** An entry the page listed is gone (deleted elsewhere): the list follows, the snackbar says why nothing opened. */
    fun gone() {
        snack.value = PenSnack(R.string.st_pen_changed_since_open)
    }

    private fun launch(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }
}

/** Shows the page's results; *Undo* puts a corrected or removed entry back. */
@Composable
private fun PenSnackbar(
    vm: PenActions,
    host: SnackbarHostState,
) {
    val s by vm.snack.collectAsStateWithLifecycle()
    val message = s?.let { stringResource(it.message) }
    val undo = stringResource(R.string.st_pen_undo)
    LaunchedEffect(s) {
        val snack = s ?: return@LaunchedEffect
        val r =
            host.showSnackbar(
                message!!,
                actionLabel = if (snack.undo != null) undo else null,
                duration = if (snack.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
        // cleared only once shown (clearing first would restart this effect and cancel the snackbar)
        vm.snack.value = null
        if (r == SnackbarResult.ActionPerformed) snack.undo?.let { vm.restore(it) }
    }
}

/**
 * One pen: the facts card, the untested model's display check (or *Not usable*), a new pen's last injection to log,
 * *Name*, *Insulin*, *Doses read*, *Check again* (untested) and *Forget this pen*. Opening it is reviewing the pen.
 */
@Composable
fun PenPage(
    vm: PenViewModel,
    onBack: () -> Unit,
    openDoses: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p by vm.pen.collectAsStateWithLifecycle()
    val offer by vm.offer.collectAsStateWithLifecycle()
    var renaming by rememberSaveable { mutableStateOf(false) }
    var choosingKind by rememberSaveable { mutableStateOf(false) }
    var forgetting by rememberSaveable { mutableStateOf(false) }
    // an untested pen opened before its check: the red confirmation first (Cancel = back, it stays unchecked)
    var confirmed by rememberSaveable { mutableStateOf(false) }
    val host = remember { SnackbarHostState() }
    LaunchedEffect(Unit) { vm.opened() }
    val gone by vm.gone.collectAsStateWithLifecycle()
    // forgotten (here or elsewhere): nothing left to show
    val back by rememberUpdatedState(onBack)
    if (gone) LaunchedEffect(Unit) { back() }
    val pen = p ?: return
    val kindLabel = stringResource(if (pen.kind == "long") R.string.st_pen_kind_long else R.string.st_pen_kind_rapid)
    PenSnackbar(vm.actions, host)
    SettingsPage(pen.name, onBack, modifier, snackbar = host) {
        SettingsGroup {
            CardRow {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (pen.untested) Text(stringResource(R.string.st_pen_untested_tag), color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.st_pen_model, pen.model), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.st_pen_serial, pen.serial), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.st_pen_started, Clock.dayTime(pen.since)), style = MaterialTheme.typography.bodyMedium)
                    if (pen.lastScan > 0) {
                        Text(
                            stringResource(R.string.st_pen_last_read, Clock.dayTime(pen.lastScan)),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    val back = pen.returningSince
                    if (back != null) {
                        Text(stringResource(R.string.st_pen_returning, Clock.dayTime(back)), style = MaterialTheme.typography.bodyMedium)
                    } else if (pen.older > 0) {
                        Text(
                            pluralStringResource(R.plurals.st_pen_older, pen.older, pen.older),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        if (pen.untested && pen.check == PenCheck.None) {
            SettingsGroup(title = stringResource(R.string.st_pen_check_title)) {
                CardRow { DisplayCheck(pen, confirmed, { confirmed = true }, vm.actions) }
            }
        }
        if (pen.untested && pen.check == PenCheck.Failed) {
            SettingsGroup {
                InfoRow(
                    stringResource(R.string.st_pen_not_usable),
                    summary = stringResource(R.string.st_pen_not_usable_text),
                    icon = DsR.drawable.ds_ic_warning,
                )
            }
        }
        val o = offer
        if (o != null && (!pen.untested || pen.check == PenCheck.Ok)) OfferCard(o, vm.actions)
        SettingsGroup {
            ValueRow(stringResource(R.string.st_pen_name), pen.name, { renaming = true }, icon = DsR.drawable.ds_ic_edit_note)
            ValueRow(
                stringResource(R.string.st_pen_insulin),
                kindLabel,
                { choosingKind = true },
                icon = DsR.drawable.ds_ic_dia_insulin_rapid,
            )
            NavigationRow(stringResource(R.string.st_pen_doses_read), openDoses, icon = DsR.drawable.ds_ic_history)
            if (pen.untested && pen.check != PenCheck.None) {
                NavigationRow(
                    stringResource(R.string.st_pen_check_again),
                    vm.actions::checkAgain,
                    summary = stringResource(R.string.st_pen_check_again_summary),
                    icon = DsR.drawable.ds_ic_science,
                )
            }
            NavigationRow(stringResource(R.string.st_pen_forget), { forgetting = true }, icon = DsR.drawable.ds_ic_delete)
        }
        FooterText(stringResource(R.string.st_pen_footer))
    }
    if (renaming) {
        var name by rememberSaveable { mutableStateOf(pen.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.st_pen_rename)) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                Button(enabled = name.isNotBlank(), onClick = {
                    vm.actions.rename(name)
                    renaming = false
                }) { Text(stringResource(R.string.st_pen_save)) }
            },
            dismissButton = { TextButton({ renaming = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
    if (choosingKind) {
        ChoiceDialog(
            stringResource(R.string.st_pen_insulin),
            listOf(
                Choice(stringResource(R.string.st_pen_kind_rapid), "rapid"),
                Choice(stringResource(R.string.st_pen_kind_long), "long"),
            ),
            pen.kind,
            { choosingKind = false },
            vm.actions::setKind,
        )
    }
    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            title = { Text(stringResource(R.string.st_pen_forget_title)) },
            text = { Text(stringResource(R.string.st_pen_forget_text)) },
            confirmButton = {
                Button(onClick = {
                    forgetting = false
                    // the page closes when the pen is gone (one Back, not two)
                    vm.forget()
                }) { Text(stringResource(R.string.st_pen_forget_confirm)) }
            },
            dismissButton = { TextButton({ forgetting = false }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }
}

/**
 * The display check of an untested model: the red confirmation first (*Continue anyway* after 10 s), then the newest
 * dose and the whole hours since it, as the pen's display shows them now. *It matches* applies that read; *It doesn't*
 * marks the pen not usable. No fresh read (a restart, over 10 min): *Tap your pen to check it*.
 */
@Composable
private fun DisplayCheck(
    pen: PenInfo,
    confirmed: Boolean,
    onConfirm: () -> Unit,
    vm: PenActions,
) {
    val haptics = rememberHaptics()
    var left by remember { mutableIntStateOf(10) }
    LaunchedEffect(confirmed) {
        while (!confirmed && left > 0) {
            kotlinx.coroutines.delay(1000)
            left--
        }
    }
    // the display counts hours from now: the line and the 10-minute limit follow the clock while the page is open
    val now by produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val held = pen.held?.takeIf { now - it.readAt <= CHECK_FRESH_S }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!confirmed) {
            Text(
                stringResource(R.string.st_pen_untested_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Text(stringResource(R.string.st_pen_untested_text, pen.model), style = MaterialTheme.typography.bodyMedium)
            TextButton(
                onClick = onConfirm,
                enabled = left == 0,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.align(Alignment.End),
            ) {
                Text(
                    if (left >
                        0
                    ) {
                        stringResource(R.string.st_pen_untested_wait, left)
                    } else {
                        stringResource(R.string.st_pen_untested_continue)
                    },
                )
            }
        } else if (held == null) {
            Text(
                stringResource(R.string.st_pen_check_tap),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        } else {
            val hours = ((now - held.t) / 3600).toInt()
            Text(
                stringResource(R.string.st_pen_check_q, units(held.units), pluralStringResource(R.plurals.st_pen_hours, hours, hours)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                FilledTonalButton(onClick = vm::checkFailed) { Text(stringResource(R.string.st_pen_check_no)) }
                Button(onClick = {
                    haptics.confirm()
                    vm.checkPassed(held.readAt)
                }) { Text(stringResource(R.string.st_pen_check_yes)) }
            }
        }
    }
}

/** A new pen's newest injection before Sukrara (under the offer window): *Log it*, or *Replace your entry*. */
@Composable
private fun OfferCard(
    o: PenSessionInfo,
    vm: PenActions,
) {
    var match by remember(o.t) { mutableStateOf<PenNearEntry?>(null) }
    LaunchedEffect(o.t, o.units) { match = vm.match(o.t, o.units) }
    SettingsGroup {
        CardRow {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.st_pen_offer, units(o.units), Clock.time(o.t)), style = MaterialTheme.typography.bodyLarge)
                match?.let {
                    Text(
                        stringResource(R.string.st_pen_matches, units(it.units), Clock.time(it.t)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(
                    onClick = { vm.logDoses(o.doses.associate { it.key to it.primed }) },
                    modifier = Modifier.align(Alignment.End),
                ) { Text(stringResource(if (match != null) R.string.st_pen_replace_entry else R.string.st_pen_log_it)) }
            }
        }
    }
}

/**
 * *Doses read*: every session the pen holds, newest first, grouped by day, with its status; the doses from before
 * Sukrara folded behind a count. A tap opens the entry's sheet (correct each dose), the question's answers, or what
 * logging the session would do.
 */
@Composable
fun DosesReadPage(
    vm: PenViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val pen by vm.pen.collectAsStateWithLifecycle()
    var unfold by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<PenSessionInfo?>(null) }
    val host = remember { SnackbarHostState() }
    PenSnackbar(vm.actions, host)
    val all = sessions.orEmpty()
    val (before, rest) = all.partition { it.status == PenSessionStatus.Before }
    SettingsPage(stringResource(R.string.st_pen_doses_read), onBack, modifier, snackbar = host) {
        val since = System.currentTimeMillis() / 1000 - 30 * 86400L
        val recent = all.filter { it.t >= since }
        val logged = recent.filter { it.entry != null && it.status != PenSessionStatus.NotLogged }.sumOf { it.units }
        val primed = recent.filter { it.status != PenSessionStatus.Before && it.status != PenSessionStatus.Ignored }.sumOf { it.primed }
        FooterText(stringResource(R.string.st_pen_totals, units(round1(logged)), units(round1(primed))))
        if (sessions != null && all.isEmpty()) SettingsGroup { InfoRow(stringResource(R.string.st_pen_no_doses)) }
        rest.groupBy { dayOf(it.t) }.forEach { (day, list) ->
            SettingsGroup(title = dayTitle(day)) {
                for (s in list) {
                    SessionRow(
                        s,
                        enabled = s.status != PenSessionStatus.Ignored && pen?.let { usable(it) } == true,
                    ) { open = s }
                }
            }
        }
        if (before.isNotEmpty()) {
            val n = before.sumOf { it.doses.size }
            SettingsGroup(title = stringResource(R.string.st_pen_before_title)) {
                NavigationRow(
                    if (unfold) stringResource(R.string.st_pen_see_less) else pluralStringResource(R.plurals.st_pen_earlier, n, n),
                    { unfold = !unfold },
                )
                if (unfold) for (s in before) SessionRow(s, enabled = pen?.let { usable(it) } == true) { open = s }
            }
        }
    }
    open?.let { s ->
        val close = { open = null }
        when {
            s.entry != null && s.status != PenSessionStatus.NotLogged -> EntrySheet(s.entry!!, vm.actions, close)
            s.status == PenSessionStatus.Waiting && s.question != null -> QuestionSheet(s, vm.actions, close)
            else -> LogSheet(s, vm.actions, close)
        }
    }
}

private fun usable(p: PenInfo) = !p.untested || p.check == PenCheck.Ok

private fun round1(x: Double) = Math.round(x * 10) / 10.0

/** Days since today (0 = today, 1 = yesterday), or the session's day label. */
private fun dayOf(t: Long): Long {
    val zone = java.time.ZoneId.systemDefault()
    val d =
        java.time.Instant
            .ofEpochSecond(t)
            .atZone(zone)
            .toLocalDate()
    return java.time.temporal.ChronoUnit.DAYS
        .between(d, java.time.LocalDate.now(zone))
}

@Composable
private fun dayTitle(daysAgo: Long): String =
    when (daysAgo) {
        0L -> stringResource(R.string.st_pen_today)
        1L -> stringResource(R.string.st_pen_yesterday)
        else -> Clock.dayLabel(System.currentTimeMillis() / 1000 - daysAgo * 86400)
    }

@Composable
private fun SessionRow(
    s: PenSessionInfo,
    enabled: Boolean,
    onOpen: () -> Unit,
) {
    val summary =
        when (s.status) {
            PenSessionStatus.Logged -> {
                if (s.primed >
                    0
                ) {
                    stringResource(R.string.st_pen_h_logged_primed, units(s.primed))
                } else {
                    stringResource(R.string.st_pen_h_logged)
                }
            }

            PenSessionStatus.Merged -> {
                s.mergedFrom?.let { stringResource(R.string.st_pen_h_merged, units(it)) } ?: stringResource(R.string.st_pen_h_logged)
            }

            PenSessionStatus.Corrected -> {
                stringResource(R.string.st_pen_h_corrected)
            }

            PenSessionStatus.Priming -> {
                stringResource(R.string.st_pen_h_priming)
            }

            PenSessionStatus.Waiting -> {
                stringResource(
                    if (s.sameQuestion) {
                        R.string.st_pen_h_same
                    } else if (s.question !=
                        null
                    ) {
                        R.string.st_pen_h_question
                    } else {
                        R.string.st_pen_h_waiting
                    },
                )
            }

            PenSessionStatus.Review -> {
                stringResource(R.string.st_pen_h_review)
            }

            PenSessionStatus.Before -> {
                stringResource(R.string.st_pen_h_before)
            }

            PenSessionStatus.Ignored -> {
                stringResource(R.string.st_pen_h_ignored)
            }

            PenSessionStatus.NotLogged -> {
                stringResource(R.string.st_pen_h_not_logged)
            }
        }.let { if (s.incomplete) stringResource(R.string.st_pen_h_incomplete, it) else it }
    val amount = units(if (s.status == PenSessionStatus.Priming) s.primed else s.units)
    NavigationRow("${Clock.time(s.t)}  ·  $amount", onOpen, summary = summary, enabled = enabled)
}

/**
 * One dose: time, amount, *Incomplete*, and *Priming* / *Injected* (an M3 segmented button). TalkBack reads the row as
 * one node ("13:10, 1 unit, priming") with a custom action that flips it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoseRow(
    d: PenDoseInfo,
    primed: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val haptics = rememberHaptics()
    val set = { p: Boolean ->
        if (p != primed) {
            haptics.tick()
            onChange(p)
        }
    }
    val role = stringResource(if (primed) R.string.st_pen_seg_priming else R.string.st_pen_seg_injected)
    val spoken = pluralStringResource(R.plurals.st_pen_units_spoken, if (d.units == 1.0) 1 else 2, units(d.units).dropLast(1))
    val desc = stringResource(R.string.st_pen_dose_a11y, Clock.time(d.t), spoken, role)
    val flip = stringResource(if (primed) R.string.st_pen_mark_injected else R.string.st_pen_mark_priming)
    Row(
        Modifier.fillMaxWidth().clearAndSetSemantics {
            contentDescription = desc
            customActions = listOf(CustomAccessibilityAction(flip) { set(!primed).let { true } })
        },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            Clock.time(d.t),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 56.dp),
        )
        Column(Modifier.widthIn(min = 48.dp)) {
            Text(units(d.units), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            if (d.incomplete) {
                Text(stringResource(R.string.st_pen_incomplete), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        Box(Modifier.weight(1f))
        SingleChoiceSegmentedButtonRow {
            SegmentedButton(primed, {
                set(true)
            }, SegmentedButtonDefaults.itemShape(0, 2), icon = {}, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.st_pen_seg_priming),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            SegmentedButton(!primed, {
                set(false)
            }, SegmentedButtonDefaults.itemShape(1, 2), icon = {}, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    stringResource(R.string.st_pen_seg_injected),
                    maxLines = 1,
                    softWrap = false,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

/**
 * A pen entry's sheet: the amount and time, *From the pen · 1.5u primed*, each dose with *Priming* / *Injected*, the
 * note and the entry it replaced. Once a dose changed: *Cancel* / *Save* (*Remove from log* when every dose is
 * priming); the snackbar's *Undo* puts it back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntrySheet(
    id: String,
    vm: PenActions,
    onDismiss: () -> Unit,
) {
    var e by remember { mutableStateOf<PenEntryInfo?>(null) }
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(id) {
        e = vm.entry(id)
        if (e == null) {
            vm.gone()
            dismiss()
        }
    }
    val entry = e ?: return
    val roles = remember(entry) { mutableStateListOf(*entry.doses.map { it.primed }.toTypedArray()) }
    val changed = roles.toList() != entry.doses.map { it.primed }
    val inj = entry.doses.filterIndexed { i, _ -> !roles[i] }
    val a = round1(inj.sumOf { it.units })
    val pr = round1(entry.doses.filterIndexed { i, _ -> roles[i] }.sumOf { it.units })
    val t = inj.minOfOrNull { it.t } ?: entry.t
    val kind = stringResource(if (entry.kind == "long") R.string.st_pen_kind_long else R.string.st_pen_kind_rapid)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (inj.isEmpty()) stringResource(R.string.st_pen_nothing_injected) else "${units(a)} $kind",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
                )
                Text(Clock.time(t), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                listOfNotNull(
                    stringResource(R.string.st_pen_from_pen),
                    pr.takeIf { it > 0 }?.let { stringResource(R.string.st_pen_primed, units(it)) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            entry.doses.forEachIndexed { i, d -> DoseRow(d, roles[i]) { roles[i] = it } }
            HorizontalDivider()
            if (entry.note.isNotEmpty()) Text(entry.note, style = MaterialTheme.typography.bodyMedium)
            entry.replaced?.let { (ra, rt) ->
                Text(
                    stringResource(R.string.st_pen_replaced, units(ra), Clock.time(rt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (changed) {
                    TextButton(onClick = {
                        roles.clear()
                        roles.addAll(entry.doses.map { it.primed })
                    }) { Text(stringResource(android.R.string.cancel)) }
                    Button(onClick = {
                        vm.correct(entry, roles.toList())
                        onDismiss()
                    }) { Text(stringResource(if (inj.isEmpty()) R.string.st_pen_remove else R.string.st_pen_save)) }
                } else {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.st_pen_done)) }
                }
            }
        }
    }
}

/**
 * A session that isn't an entry (before Sukrara, not logged, only priming, after a pen clock reset): its doses with
 * *Priming* / *Injected*, what logging it would do (*Matches your 8u entry at 13:10*), *Log this injection* (*Log as
 * injection* for priming), or *Add to the 13:10 entry* for a dose that belongs to an entry's session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(
    s: PenSessionInfo,
    vm: PenActions,
    onDismiss: () -> Unit,
) {
    val roles =
        remember(s) {
            // only priming: the last dose is offered as the injection
            val pre = s.doses.mapIndexed { i, d -> if (s.status == PenSessionStatus.Priming) i < s.doses.lastIndex else d.primed }
            mutableStateListOf(*pre.toTypedArray())
        }
    val inj = s.doses.filterIndexed { i, _ -> !roles[i] }
    val a = round1(inj.sumOf { it.units })
    val t = inj.minOfOrNull { it.t }
    var match by remember { mutableStateOf<PenNearEntry?>(null) }
    var joins by remember { mutableStateOf<PenNearEntry?>(null) }
    LaunchedEffect(t, a) {
        match = t?.let { vm.match(it, a) }
        joins =
            if ((s.status == PenSessionStatus.Review || s.status == PenSessionStatus.NotLogged) && t != null) vm.sessionEntry(t) else null
    }
    val doses = { s.doses.mapIndexed { i, d -> d.key to roles[i] }.toMap() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                if (inj.isEmpty()) stringResource(R.string.st_pen_nothing_injected) else units(a),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            HorizontalDivider()
            s.doses.forEachIndexed { i, d -> DoseRow(d, roles[i]) { roles[i] = it } }
            HorizontalDivider()
            match?.let {
                Text(
                    stringResource(R.string.st_pen_matches, units(it.units), Clock.time(it.t)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
                joins?.let { j ->
                    FilledTonalButton(onClick = {
                        vm.join(doses(), j.id)
                        onDismiss()
                    }) { Text(stringResource(R.string.st_pen_add_to_entry, Clock.time(j.t))) }
                }
                Button(enabled = inj.isNotEmpty(), onClick = {
                    vm.logDoses(doses())
                    onDismiss()
                }) {
                    Text(
                        stringResource(
                            when {
                                match != null -> R.string.st_pen_replace_entry
                                s.status == PenSessionStatus.Priming -> R.string.st_pen_log_as_injection
                                else -> R.string.st_pen_log_injection
                            },
                        ),
                    )
                }
            }
        }
    }
}

/** A session waiting on a question: its doses as they are and the question's answers (the read card's). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuestionSheet(
    s: PenSessionInfo,
    vm: PenActions,
    onDismiss: () -> Unit,
) {
    val q = s.question ?: return
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (s.sameQuestion) R.string.st_pen_h_same else R.string.st_pen_h_question),
                style = MaterialTheme.typography.headlineSmall,
            )
            s.doses.forEach { d ->
                Text(
                    "${Clock.time(d.t)}  ·  ${units(d.units)}" + if (d.primed) " · " + stringResource(R.string.st_pen_seg_priming) else "",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            val keys = s.doses.map { it.key }
            if (s.sameQuestion && s.candidates > 1) {
                // several typed entries nearby: which one is answered on the read card or the notification
                Text(stringResource(R.string.st_pen_q_several), style = MaterialTheme.typography.bodyMedium)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                if (s.sameQuestion && s.candidates > 1) {
                    Button(onClick = onDismiss) { Text(stringResource(R.string.st_pen_done)) }
                } else if (s.sameQuestion) {
                    FilledTonalButton(onClick = {
                        vm.same(q, keys, false)
                        onDismiss()
                    }) { Text(stringResource(R.string.st_pen_different)) }
                    Button(onClick = {
                        vm.same(q, keys, true)
                        onDismiss()
                    }) { Text(stringResource(R.string.st_pen_same)) }
                } else {
                    FilledTonalButton(onClick = {
                        vm.allPriming(q, keys)
                        onDismiss()
                    }) { Text(stringResource(R.string.st_pen_all_priming)) }
                    Button(onClick = {
                        vm.accept(q, keys)
                        onDismiss()
                    }) { Text(stringResource(R.string.st_pen_log_units, units(s.units))) }
                }
            }
        }
    }
}
