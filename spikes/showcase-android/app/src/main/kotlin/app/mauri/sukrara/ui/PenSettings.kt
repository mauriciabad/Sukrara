// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Intent
import android.net.Uri
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.R
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.graph.Clock
import app.mauri.sukrara.pen.Pen
import app.mauri.sukrara.pen.PenEngine
import app.mauri.sukrara.pen.PenProfiles
import app.mauri.sukrara.pen.PenWords
import app.mauri.sukrara.pen.Pens
import app.mauri.sukrara.pen.Session
import app.mauri.sukrara.settings.SettingsSpec
import app.mauri.sukrara.treatments.Treat

/*
 * Settings → Insulin pens (PRODUCT §4.8/§4.9): the page, one page per pen (AOSP device-details shape, like Glucose
 * source), its *Doses read* history (every session with its status; earlier doses logged one at a time), and the
 * pen's setup (§4.7, from the new-pen card's *Set up*).
 */

const val PEN_ROUTE = "pen:"
const val PEN_DOSES_ROUTE = "pendoses:"
const val PEN_REVIEW_ROUTE = "penreview:"
const val PEN_DETECTION_ROUTE = "pendetection"

private const val JUGGLUCO = "tk.glucodata"

private fun installed(ctx: android.content.Context, pkg: String) =
    runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

/** The overview row's summary: *Off*, *NovoPen Echo Plus · read 13:14*, *2 pens · read 13:14*, *Blocked in Android settings*. */
@Composable
fun pensSummary(): String {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val pens = Pens.ledger.collectAsState().value.pens.values
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    val last = pens.maxOfOrNull { it.lastScan }?.takeIf { it > 0 }?.let { Clock.time(it) }
    return when {
        f.penReading == "ask" -> stringResource(R.string.pens_summary_ask)
        f.penReading == "off" -> stringResource(R.string.settings_off)
        !NfcLaunch.allowed(nfc) -> stringResource(R.string.sensor_launch_on_scan_blocked)
        pens.isEmpty() || last == null -> stringResource(R.string.pens_empty)
        pens.size == 1 -> stringResource(R.string.pens_summary_one, Pens.displayName(pens.first().serial), last)
        else -> pluralStringResource(R.plurals.pens_summary_many, pens.size, pens.size, last)
    }
}

/** What a pen's row says under its name: *Rapid · read 13:14*, or that it waits for its display check. */
@Composable
private fun penRowSummary(p: Pen): String {
    val untested = PenProfiles.byId(p.model)?.verified == false
    return when {
        untested && p.check == "failed" -> stringResource(R.string.pen_not_used)
        untested && p.check == null -> stringResource(R.string.pen_check_to_start)
        else -> stringResource(R.string.pens_summary_one, PenWords(LocalContext.current).kind(p.kind), Clock.time(p.lastScan)) +
            // not opened yet: a quiet hint (the row itself opens the pen's page, AOSP paired-device style)
            (if (!Pens.isSetUp(LocalContext.current, p.serial)) " · " + stringResource(R.string.pen_review_hint) else "")
    }
}

@Composable
fun PensSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val l by Pens.ledger.collectAsState()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var nfcOn by remember { mutableStateOf(nfc?.isEnabled == true) }
    val allowed = rememberTagIntentsAllowed()
    var askedAllow by remember { mutableStateOf(false) }
    LifecycleResumeEffect(allowed) {
        nfcOn = nfc?.isEnabled == true
        // back from Android's allow screen after switching reading on: say how it went
        if (askedAllow) { askedAllow = false; android.widget.Toast.makeText(ctx, if (allowed) R.string.tags_allowed_toast else R.string.tags_still_blocked_toast, android.widget.Toast.LENGTH_SHORT).show() }
        onPauseOrDispose { }
    }
    val nav = LocalNav.current
    SettingsPage(stringResource(R.string.pens_title), onBack) {
        SettingsIntro(stringResource(R.string.pens_intro))
        // where this phone's NFC antenna is (NfcAntennaInfo, as on the sensor scan): hold the pen there
        SettingsHeader { Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ScanArt(remember { antennaSpot(nfc) }, stringResource(R.string.pens_intro)) } }
        // blocked by Android (pen reading Ask or On): the same card as on home, at the top
        if (f.penReading != "off" && !allowed && (nfc != null || NfcLaunch.fakeBlocked)) SettingsHeader { TagBlockedCard(NfcLaunch.Blocked.PEN, null) }
        SettingsMainSwitch(stringResource(R.string.pens_switch), f.penReading == "on") {
            Pens.setReading(ctx, it)
            // switched on while Android blocks the taps: straight to Android's allow screen, once
            if (it && !allowed) { askedAllow = true; openTagIntentSettings(ctx) }
        }
        if (f.penReading == "ask") SettingsFooter(stringResource(R.string.pens_summary_ask))
        if (f.penReading != "off") {
            SettingsGroup {
                if (!allowed) tapFeatureRow(stringResource(R.string.pens_blocked), "", on = true, allowed = false) { }
                if (!nfcOn) action(stringResource(R.string.pens_nfc_off), stringResource(R.string.pens_nfc_on), icon = R.drawable.ic_sym_nfc) {
                    ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
                }
                if (installed(ctx, JUGGLUCO)) link(stringResource(R.string.pens_juggluco), stringResource(R.string.pens_juggluco_text),
                    leading = { ServiceIcon(Service.JUGGLUCO) }, external = true) {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$JUGGLUCO")))
                }
            }
            SettingsGroup(stringResource(R.string.pens_group)) {
                val pens = l.pens.values.sortedBy { it.first }
                if (pens.isEmpty()) info(stringResource(R.string.pens_empty))
                for (p in pens) {
                row(Pens.displayName(p.serial, l.pens), null, to = PEN_ROUTE + p.serial) {
                    val summary = penRowSummary(p)
                    Row(Modifier.fillMaxWidth().clickable { nav?.open(PEN_ROUTE + p.serial) }.padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Icon(painterResource(R.drawable.ic_dia_pen), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(Pens.displayName(p.serial, l.pens), style = MaterialTheme.typography.titleMedium)
                            Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (PenProfiles.byId(p.model)?.verified == false) UntestedTag()
                    }
                }
                }
            }
            SettingsGroup {
                link(stringResource(R.string.pens_detection), stringResource(R.string.pens_detection_summary, PenWords.number(f.penPrimeBelow) + "u",
                    minutes(f.penClusterMin)), icon = R.drawable.ic_sym_tune, to = PEN_DETECTION_ROUTE)
            }
        }
        // what works, before any pen is added (like the sensor families' brand step): every PenProfile, untested tagged
        supportedPens()
        SettingsFooter(stringResource(R.string.pens_supported_footer))
    }
}

/**
 * *Supported pens*: one row per [PenProfiles.ALL] (the one source: a new profile shows here, in search and in the
 * tests), the red *Untested* tag on the unverified ones (the Glucose source / brand step pattern). Not tappable.
 */
@Composable
internal fun supportedPens() {
    val untested = stringResource(R.string.newsensor_untested_tag)
    SettingsGroup(stringResource(R.string.pens_supported_group)) {
        for (p in PenProfiles.ALL) row(p.name, if (p.verified) null else untested) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }.padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(painterResource(R.drawable.ic_dia_pen), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (!p.verified) UntestedTag()
            }
        }
    }
}

@Composable
private fun minutes(m: Int) = "$m " + stringResource(R.string.unit_min)

/**
 * Settings → Insulin pens → *Automatic detection*: the dose rules ([app.mauri.sukrara.pen.PenRules], PRODUCT §3) the
 * user can tune, each with the one number input; each summary states the rule with its value. Fixed guards (future
 * doses, clock reset, 20 doses per entry, the 72 h big read) aren't here. A change applies to doses read afterwards.
 */
@Composable
fun PenDetectionSettings(onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val h = stringResource(R.string.unit_h)
    val min = stringResource(R.string.unit_min)
    fun u(v: Double) = PenWords.number(v) + "u"
    fun minutes(v: Int) = "$v $min"
    fun mins(v: Double) = minutes(v.toInt())
    fun pct(v: Double) = ctx.getString(R.string.pens_percent, v.toInt())
    SettingsPage(stringResource(R.string.pens_detection), onBack) {
        SettingsIntro(stringResource(R.string.pens_detection_intro))
        SettingsGroup(stringResource(R.string.pens_priming_group)) {
            value(stringResource(R.string.pens_prime_below), stringResource(R.string.pens_prime_below_summary, u(f.penPrimeBelow)),
                icon = R.drawable.ic_sym_water_drop) { editing = "primeBelow" }
            value(stringResource(R.string.pens_prime_before), stringResource(R.string.pens_prime_before_summary, minutes(f.penPrimeBeforeMin)),
                icon = R.drawable.ic_sym_schedule) { editing = "primeBefore" }
        }
        SettingsFooter(stringResource(R.string.pens_prime_footer))
        SettingsGroup(stringResource(R.string.pens_injections_group)) {
            value(stringResource(R.string.pens_cluster), stringResource(R.string.pens_cluster_summary, minutes(f.penClusterMin)),
                icon = R.drawable.ic_sym_timer) { editing = "cluster" }
            value(stringResource(R.string.pens_pending), stringResource(R.string.pens_pending_summary, minutes(f.penPendingMin)),
                icon = R.drawable.ic_sym_hourglass_top) { editing = "pending" }
            value(stringResource(R.string.pens_offer), stringResource(R.string.pens_offer_summary, "${f.penOfferH} $h"),
                icon = R.drawable.ic_sym_history) { editing = "offer" }
        }
        SettingsGroup(stringResource(R.string.pens_typed_group)) {
            value(stringResource(R.string.pens_match), stringResource(R.string.pens_match_summary, minutes(f.penMatchMin)),
                icon = R.drawable.ic_sym_schedule) { editing = "match" }
            value(stringResource(R.string.pens_match_units), stringResource(R.string.pens_match_units_summary, u(f.penMatchUnits)),
                icon = R.drawable.ic_sym_edit_note) { editing = "matchUnits" }
            value(stringResource(R.string.pens_match_share), stringResource(R.string.pens_match_share_summary, pct(f.penMatchPct.toDouble())),
                icon = R.drawable.ic_sym_edit_note) { editing = "matchShare" }
        }
        SettingsGroup {
            action(stringResource(R.string.pens_detection_reset), icon = R.drawable.ic_sym_restart_alt, enabled = !f.penDetectionIsDefault) { resetting = true }
        }
    }
    val S = SettingsSpec
    val close = { editing = null }
    when (editing) {
        "primeBelow" -> NumberDialog(stringResource(R.string.pens_prime_below), S.PEN_PRIME_BELOW, f.penPrimeBelow, ::u, close) { v -> Features.update { it.copy(penPrimeBelow = v) } }
        "primeBefore" -> NumberDialog(stringResource(R.string.pens_prime_before), S.PEN_PRIME_BEFORE, f.penPrimeBeforeMin.toDouble(), ::mins, close) { v -> Features.update { it.copy(penPrimeBeforeMin = v.toInt()) } }
        "cluster" -> NumberDialog(stringResource(R.string.pens_cluster), S.PEN_CLUSTER, f.penClusterMin.toDouble(), ::mins, close) { v -> Features.update { it.copy(penClusterMin = v.toInt()) } }
        "pending" -> NumberDialog(stringResource(R.string.pens_pending), S.PEN_PENDING, f.penPendingMin.toDouble(), ::mins, close) { v -> Features.update { it.copy(penPendingMin = v.toInt()) } }
        "offer" -> NumberDialog(stringResource(R.string.pens_offer), S.PEN_OFFER, f.penOfferH.toDouble(), { "${it.toInt()} $h" }, close) { v -> Features.update { it.copy(penOfferH = v.toInt()) } }
        "match" -> NumberDialog(stringResource(R.string.pens_match), S.PEN_MATCH, f.penMatchMin.toDouble(), ::mins, close) { v -> Features.update { it.copy(penMatchMin = v.toInt()) } }
        "matchUnits" -> NumberDialog(stringResource(R.string.pens_match_units), S.PEN_MATCH_UNITS, f.penMatchUnits, ::u, close) { v -> Features.update { it.copy(penMatchUnits = v) } }
        "matchShare" -> NumberDialog(stringResource(R.string.pens_match_share), S.PEN_MATCH_SHARE, f.penMatchPct.toDouble(), ::pct, close) { v -> Features.update { it.copy(penMatchPct = v.toInt()) } }
    }
    if (resetting) AlertDialog(onDismissRequest = { resetting = false },
        title = { Text(stringResource(R.string.pens_detection_reset_title)) },
        text = { Text(stringResource(R.string.pens_detection_reset_text)) },
        confirmButton = { Button(onClick = { Features.update { it.penDetectionDefaults() }; resetting = false }) { Text(stringResource(R.string.pens_detection_reset_confirm)) } },
        dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.treatment_cancel)) } })
}

/** One pen (PRODUCT §4.8): header card, *Name*, *Insulin*, *Doses read*, untested: *Send feedback* / *Check again*, *Forget*. */
@Composable
fun PenPageSettings(serial: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val l by Pens.ledger.collectAsState()
    val p = l.pens[serial]
    if (p == null) { LaunchedEffect(Unit) { onBack() }; return }
    val w = remember { PenWords(ctx) }
    val profile = PenProfiles.byId(p.model)
    var renaming by remember { mutableStateOf(false) }
    var kind by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    val untested = profile?.verified == false
    // an untested pen opened before its check: the red confirmation first (Cancel = back, it stays unchecked)
    var confirmed by remember { mutableStateOf(!untested || p.check != null) }
    // opening the page is reviewing the pen: the list's "Review its settings" hint goes away
    LaunchedEffect(serial) { Pens.markSetUp(ctx, serial) }
    if (!confirmed) PenUntestedDialog(profile?.name ?: Pens.displayName(serial, l.pens), onCancel = onBack) { confirmed = true }
    SettingsPage(Pens.displayName(serial, l.pens), onBack) {
        SettingsHeader {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // the name is the page title; the card holds the facts (QA review 12)
                    if (profile?.verified == false) UntestedTag()
                    Text(stringResource(R.string.pen_serial, serial), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.pen_started, Clock.dayLabel(p.since)), style = MaterialTheme.typography.bodyMedium)
                    if (p.lastScan > 0) Text(stringResource(R.string.pen_last_read, Clock.dayTime(p.lastScan)), style = MaterialTheme.typography.bodyMedium)
                    val known = remember(l) { Treat.store.all().filter { it.pen == serial && !it.pd.isNullOrEmpty() }.flatMap { it.pd!! }.maxOfOrNull { it.t } }
                    if (p.returning && known != null) Text(stringResource(R.string.pen_new_returning, Clock.dayTime(known)), style = MaterialTheme.typography.bodyMedium)
                    else if (p.older > 0) Text(pluralStringResource(R.plurals.pen_new_older, p.older, p.older), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        // the pen's page is its setup (0.11.49): the untested display check, the newest injection to log, then the rows
        if (untested && p.check == null) SettingsHeader { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (confirmed) DisplayCheck(serial, w) else Text(stringResource(R.string.pen_check_to_start), style = MaterialTheme.typography.bodyLarge)
        } } }
        if (untested && p.check == "failed") SettingsHeader { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.pen_read_notusable), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.pen_read_notusable_text), style = MaterialTheme.typography.bodyMedium)
        } } }
        if (!untested || p.check == "ok") OfferCard(serial, w)
        SettingsGroup {
            value(stringResource(R.string.pen_name), Pens.displayName(serial, l.pens), icon = R.drawable.ic_sym_edit_note) { renaming = true }
            value(stringResource(R.string.pen_insulin), w.kind(p.kind), icon = if (p.kind == "long") R.drawable.ic_dia_insulin_long else R.drawable.ic_dia_insulin_rapid) { kind = true }
            link(stringResource(R.string.pen_doses_read), icon = R.drawable.ic_sym_history, to = PEN_DOSES_ROUTE + serial)
            if (profile?.verified == false) {
                if (p.check == "failed") action(stringResource(R.string.pen_check_again), icon = R.drawable.ic_sym_science) { Pens.update(serial) { it.copy(check = null) } }
                link(stringResource(R.string.pen_send_feedback), icon = R.drawable.ic_sym_open_in_new, external = true) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(penFeedbackUrl(profile.name)))) }
                }
            }
            action(stringResource(R.string.pen_forget), icon = R.drawable.ic_sym_link_off) { forgetting = true }
        }
    }
    if (renaming) {
        var name by remember { mutableStateOf(Pens.displayName(serial, l.pens)) }
        AlertDialog(onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.pen_rename)) },
            text = { OutlinedTextField(name, { name = it.take(40) }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { Button(enabled = name.isNotBlank(), onClick = { Pens.update(serial) { it.copy(name = name.trim()) }; renaming = false }) { Text(stringResource(R.string.treatment_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.treatment_cancel)) } })
    }
    if (kind) ChoiceDialog(stringResource(R.string.pen_insulin), listOf(Choice(w.kind("rapid"), "rapid"), Choice(w.kind("long"), "long")), p.kind,
        { kind = false }) { k -> Pens.update(serial) { it.copy(kind = k) } }
    if (forgetting) AlertDialog(onDismissRequest = { forgetting = false },
        title = { Text(stringResource(R.string.pen_forget_title)) },
        text = { Text(stringResource(R.string.pen_forget_text)) },
        confirmButton = { Button(onClick = { forgetting = false; Pens.forget(serial); onBack() }) { Text(stringResource(R.string.pen_forget_confirm)) } },
        dismissButton = { TextButton(onClick = { forgetting = false }) { Text(stringResource(R.string.treatment_cancel)) } })
}

/** The pre-filled issue for an untested pen (`.github/ISSUE_TEMPLATE/pen-feedback.yml`): model and versions, no doses, no serial. */
fun penFeedbackUrl(model: String): String {
    val d = SensorFeedback.Device.current()
    fun enc(s: String) = java.net.URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
    return "https://github.com/mauriciabad/cgm-reading-mac-spike/issues/new?template=pen-feedback.yml&labels=pen-feedback" +
        "&title=${enc("Pen feedback: $model")}&pen=${enc(model)}&app-version=${enc(d.appVersion)}&android=${enc(d.android)}&phone=${enc(d.phone)}"
}

/**
 * *Doses read* (PRODUCT §4.9): every session the pen holds, newest first, grouped by day, with its status; the
 * earlier doses (before Sukrara) folded behind a count pill. [review]: only the last read's entries (*Review* on a big
 * read), with *Undo this read*.
 */
@Composable
fun DosesReadSettings(serial: String, review: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    Pens.ledger.collectAsState().value
    Treat.store.items.collectAsState().value
    val read by Pens.readState.collectAsState()
    val all = Pens.history(serial)
    val readIds = read?.report?.let { (it.created + it.merged).toSet() }.orEmpty()
    val sessions = if (review) all.filter { it.entry in readIds } else all
    val (before, rest) = sessions.partition { it.status == Session.BEFORE }
    var unfold by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Session?>(null) }
    val pen = Pens.ledger.value.pens[serial]
    SettingsPage(stringResource(R.string.pen_doses_read), onBack) {
        if (!review) {
            val since = System.currentTimeMillis() / 1000 - 30 * 86400L
            val logged = Treat.store.all().filter { !it.del && it.pen == serial && it.t >= since }
            val primedOnly = all.filter { it.status == Session.PRIMING && it.t >= since }.sumOf { it.pr }
            SettingsIntro(stringResource(R.string.pen_h_totals, w.u(PenEngine.r1(logged.sumOf { it.a })), w.u(PenEngine.r1(logged.sumOf { it.pr ?: 0.0 } + primedOnly))))
        }
        if (review && read?.undo != null && !read!!.undone) SettingsHeader {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.pen_review_line, ctx.resources.getQuantityString(R.plurals.pen_read_several, sessions.size, sessions.size),
                    Clock.dayLabel(read!!.at)), style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { read?.undo?.let { Pens.undoRead(ctx, it) } }) { Text(stringResource(R.string.pen_undo_read)) }
            }
        }
        val today = stringResource(R.string.pen_today); val yesterday = stringResource(R.string.pen_yesterday)
        rest.groupBy { day(it.t) }.forEach { (d, list) ->
            SettingsGroup(when (d) { TODAY -> today; YESTERDAY -> yesterday; else -> d }) {
                for (s in list) sessionRow(s, w, review, pen) { open = it }
            }
        }
        if (before.isNotEmpty()) SettingsGroup(stringResource(R.string.pen_h_before)) {
            row(stringResource(R.string.pen_h_before)) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                    SettingsNumberButton(if (unfold) 0 else before.sumOf { it.keys.size }, if (unfold) stringResource(R.string.pen_h_see_less)
                        else pluralStringResource(R.plurals.pen_h_earlier, before.sumOf { it.keys.size }, before.sumOf { it.keys.size })) { unfold = !unfold }
                }
            }
            if (unfold) for (s in before) sessionRow(s, w, review, pen) { open = it }
        }
    }
    open?.let { s ->
        val e = s.entry?.let { Treat.store.entry(it)?.second }
        when {
            e != null && !e.del && isPenEntry(e) -> PenEntrySheet(e) { open = null }
            s.status == Session.WAITING && s.question != null -> PenQuestionSheet(s.question) { open = null }
            else -> LogSessionSheet(s) { open = null }
        }
    }
}

private const val TODAY = "\u0000today"
private const val YESTERDAY = "\u0000yesterday"

private fun day(t: Long): String {
    val c = java.util.Calendar.getInstance()
    val today = c.apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0) }.timeInMillis / 1000
    return when {
        t >= today -> TODAY
        t >= today - 86400 -> YESTERDAY
        else -> Clock.dayLabel(t)
    }
}

private fun SettingsGroupScope.sessionRow(s: Session, w: PenWords, review: Boolean, pen: Pen?, onOpen: (Session) -> Unit) {
    val e = s.entry?.let { Treat.store.entry(it)?.second }
    val summary = when {
        review -> pen?.replaced?.get(s.entry)?.let { w.s(R.string.pen_h_merged, w.u(it.a)) } ?: w.s(R.string.pen_h_new)
        s.status == Session.LOGGED -> if (s.pr > 0) w.s(R.string.pen_h_logged, w.s(R.string.pen_primed, w.u(s.pr))) else w.s(R.string.pen_h_logged_plain)
        s.status == Session.MERGED -> pen?.replaced?.get(s.entry)?.let { w.s(R.string.pen_h_merged, w.u(it.a)) } ?: w.s(R.string.pen_h_logged_plain)
        s.status == Session.CORRECTED -> w.s(R.string.pen_h_corrected)
        s.status == Session.PRIMING -> w.s(R.string.pen_h_priming, w.u(s.pr))
        s.status == Session.WAITING -> Pens.ledger.value.questions.firstOrNull { it.id == s.question }
            ?.let { w.s(if (it.type == PenEngine.MATCH_Q) R.string.pen_q_same_title else R.string.pen_q_doses_title) } ?: w.s(R.string.pen_read_pending)
        s.status == Session.BEFORE -> w.s(R.string.pen_h_before)
        s.status == Session.IGNORED -> w.s(R.string.pen_h_ignored)
        else -> w.s(R.string.pen_h_not_logged)
    }.let { if (s.incomplete) w.s(R.string.pen_h_incomplete, it) else it }
    val amount = w.u(if (s.status == Session.PRIMING) s.pr else (e?.takeIf { !it.del }?.a ?: s.a))
    // an SPA preference row, so a day's sessions sit in one rounded group card (QA review 11)
    link("${w.time(s.t)}  ·  $amount", summary, enabled = true) { if (s.status != Session.IGNORED) onOpen(s) }
}

/**
 * A session that isn't an entry (before Sukrara, not logged, only priming): its doses with *Priming* / *Injected*, what
 * logging it would do (*Matches your 8u entry at 13:10*), *Log this injection* (or *Log as injection* for priming).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogSessionSheet(s: Session, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    val doses = Pens.ledger.value.pens[s.serial]?.doses.orEmpty()
    val keys = s.keys.filter { it in doses }.sortedBy { doses.getValue(it).t }
    val roles = remember(s) { mutableStateListOf(*keys.map { it in s.primed && s.status != Session.PRIMING || (s.status == Session.PRIMING && keys.indexOf(it) < keys.lastIndex) }.toTypedArray()) }
    val inj = keys.filterIndexed { i, _ -> !roles[i] }.map { doses.getValue(it) }
    val a = PenEngine.r1(inj.sumOf { it.a }); val t = inj.minOfOrNull { it.t }
    val match = t?.let { Pens.match(s.serial, it, a) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (inj.isEmpty()) stringResource(R.string.pen_nothing_injected) else w.u(a), style = MaterialTheme.typography.headlineSmall)
            HorizontalDivider()
            keys.forEachIndexed { i, k -> val d = doses.getValue(k); DoseRow(d.t, d.a, roles[i], d.inc, false) { roles[i] = it } }
            HorizontalDivider()
            match?.let { Text(stringResource(R.string.pen_new_matches, w.u(it.a), Clock.time(it.t)), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            // a dose held after a pen clock reset that belongs to an entry's session: add it to that entry
            val joins = if (s.status == Session.REVIEW || s.status == Session.NOT_LOGGED) t?.let { Pens.sessionEntry(s.serial, it) } else null
            Column(Modifier.align(Alignment.End), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.treatment_cancel)) }
                if (joins != null) FilledTonalButton(onClick = { Pens.joinEntry(ctx, s.serial, keys.mapIndexed { i, k -> k to roles[i] }.toMap(), joins.id); onDismiss() }) {
                    Text(stringResource(R.string.pen_add_to_entry, Clock.time(joins.t)))
                }
                Button(enabled = inj.isNotEmpty(), onClick = { Pens.logDoses(ctx, s.serial, keys.mapIndexed { i, k -> k to roles[i] }.toMap()); onDismiss() }) {
                    Text(stringResource(when { match != null -> R.string.pen_replace_entry; s.status == Session.PRIMING -> R.string.pen_log_as_injection
                        else -> R.string.pen_log_injection }))
                }
            }
        }
    }
}

/** The pen's newest injection under 24 h old that isn't logged (a new pen's one offer, PRODUCT §3.9), with *Log it*. */
@Composable
private fun OfferCard(serial: String, w: PenWords) {
    val ctx = LocalContext.current
    Treat.store.items.collectAsState().value
    val now = System.currentTimeMillis() / 1000
    val offer = Pens.history(serial).filter { it.status == Session.BEFORE && it.a > 0 && now - it.t < 24 * 3600 }.maxByOrNull { it.t } ?: return
    var loggedIt by remember(offer.t) { mutableStateOf(false) }
    SettingsHeader { Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.pen_new_last, w.u(offer.a), Clock.time(offer.t), app.mauri.sukrara.treatments.TreatmentMarks.duration(now - offer.t)),
                style = MaterialTheme.typography.bodyLarge)
            // a matching hand-typed entry: logging replaces it (pen time and amount), so the button says so
            val match = Pens.match(serial, offer.t, offer.a)
            match?.let { Text(stringResource(R.string.pen_new_matches, w.u(it.a), Clock.time(it.t)), style = MaterialTheme.typography.bodyMedium) }
            if (loggedIt) Row(Modifier.align(Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sym_check), contentDescription = null, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.pen_logged), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge)
            } else Button(onClick = { Pens.logDoses(ctx, serial, offer.keys.associateWith { it in offer.primed }); loggedIt = true },
                modifier = Modifier.align(Alignment.End)) { Text(stringResource(if (match != null) R.string.pen_replace_entry else R.string.pen_log_it)) }
        }
    } }
}

/** The display check (PRODUCT §6): the newest dose and the whole hours since, as the pen's display shows them. */
@Composable
private fun DisplayCheck(serial: String, w: PenWords) {
    val ctx = LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    val r = Pens.held?.takeIf { it.serial == serial }
    val newest = r?.doses?.maxByOrNull { it.time }
    if (newest == null) { Text(stringResource(R.string.pens_empty), style = MaterialTheme.typography.bodyLarge); return }
    val hours = ((r.scanTime - newest.time) / 3600).toInt()
    Text(stringResource(R.string.pen_check_q, w.u(newest.units), pluralStringResource(R.plurals.pen_hours, hours, hours)), style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        FilledTonalButton(onClick = { Pens.checkFailed(serial) }) { Text(stringResource(R.string.pen_check_no)) }
        Button(onClick = { Pens.checkPassed(ctx, serial); app.mauri.sukrara.pen.PenHaptics.play(view, app.mauri.sukrara.pen.PenHaptics.Moment.SUCCESS) }) {
            Text(stringResource(R.string.pen_check_yes))
        }
    }
}

/** The untested-model confirmation: the sensor flow's red pattern, *Continue anyway* after 10 s, *Cancel* tonal. */
@Composable
private fun PenUntestedDialog(model: String, onCancel: () -> Unit, onContinue: () -> Unit) {
    val opened = remember { System.currentTimeMillis() }
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (!UntestedConfirm.enabled(elapsed)) { kotlinx.coroutines.delay(200); elapsed = System.currentTimeMillis() - opened } }
    val left = UntestedConfirm.secondsLeft(elapsed)
    AlertDialog(
        onDismissRequest = onCancel,
        iconContentColor = MaterialTheme.colorScheme.error, titleContentColor = MaterialTheme.colorScheme.error,
        icon = { Icon(painterResource(R.drawable.ic_sym_warning), contentDescription = null) },
        title = { Text(stringResource(R.string.pen_untested_title)) },
        text = { Text(stringResource(R.string.pen_untested_text, model)) },
        confirmButton = {
            TextButton(onClick = onContinue, enabled = left == 0,
                colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text(if (left > 0) stringResource(R.string.untested_confirm_wait, left) else stringResource(R.string.untested_confirm_accept))
            }
        },
        dismissButton = { FilledTonalButton(onClick = onCancel) { Text(stringResource(R.string.untested_confirm_cancel)) } },
    )
}
