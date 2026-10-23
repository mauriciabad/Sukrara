// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.pen.PenEngine
import app.mauri.sukrara.pen.PenTag
import app.mauri.sukrara.pen.PenWords
import app.mauri.sukrara.pen.Pens
import app.mauri.sukrara.treatments.NewEntry
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The pen in the treatment dialog (PRODUCT §4.6/§4.6b): the duplicate warning on Save, and reading the pen into the
 * dialog (reader mode while it is open, without the system sound; a small pen icon in the title row is the sign).
 */

/** §4.6: a pen entry of the same kind within 45 min of a typed rapid / long amount (the wearer's own log only). */
object PenDuplicate {
    fun find(ctx: Context, list: List<NewEntry>): Treatment? {
        if (App.role(ctx) != "owner") return null
        Treat.init(ctx)
        val entries = Treat.store.all().map(Pens::toEntry)
        return list.filter { it.k == "rapid" || it.k == "long" }
            .firstNotNullOfOrNull { e -> PenEngine.near(entries, e.k, e.at)?.let { Treat.store.entry(it.id)?.second } }
    }
}

/** What the dialog's pen icon shows: idle, reading (spins), or a 3 s word (*Nothing new* / red *Couldn't read the pen*). */
private object PenDialogRead {
    val state = MutableStateFlow("idle")
}

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

/** The dialog can read the pen: reading on, NFC on and allowed, the wearer's phone. */
@Composable
private fun penReadable(): Boolean {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    return f.penReading == "on" && Pens.available(ctx) && nfc?.isEnabled == true && NfcLaunch.allowed(nfc)
}

/** Holds reader mode for pens while the dialog is open; a pen read → [onFill] (on the main thread). */
@Composable
fun PenReaderEffect(onFill: (Pens.Fill) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val on = penReadable()
    DisposableEffect(on) {
        val act = ctx.activity()
        val nfc = NfcAdapter.getDefaultAdapter(ctx)
        if (on && act != null && nfc != null) nfc.enableReaderMode(act, { tag ->
            if (!PenTag.isPen(tag)) return@enableReaderMode
            PenDialogRead.state.value = "reading"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { PenTag.read(tag) { Pens.stopBefore(it) } }.getOrNull() }
                if (r == null) { app.mauri.sukrara.pen.PenHaptics.read(view, false); PenDialogRead.state.value = "failed"; return@launch }
                app.mauri.sukrara.pen.PenHaptics.read(view, true)
                val f = withContext(Dispatchers.IO) { Pens.readForDialog(ctx, r) }
                if (f == null) PenDialogRead.state.value = "nothing" else { PenDialogRead.state.value = "idle"; onFill(f) }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
        onDispose { if (on && act != null) runCatching { nfc?.disableReaderMode(act) } }
    }
}

/** The sign at the end of the title row: a 20 dp contactless pen, or the read's word for 3 s. */
@Composable
fun PenReaderSign() {
    if (!penReadable()) return
    val st by PenDialogRead.state.collectAsState()
    LaunchedEffect(st) { if (st == "nothing" || st == "failed") { delay(3000); PenDialogRead.state.value = "idle" } }
    val label = stringResource(R.string.pen_dialog_read_a11y)
    // the icon pulses once (1 → 1.15 → 1) as a read starts; reduced motion: no pulse
    val ctx = LocalContext.current
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(st) { if (st == "reading" && !app.mauri.sukrara.pen.PenHaptics.reducedMotion(ctx)) { pulse.animateTo(1.15f, androidx.compose.animation.core.tween(120)); pulse.animateTo(1f, androidx.compose.animation.core.spring(0.6f, 800f)) } }
    when (st) {
        "nothing" -> Text(stringResource(R.string.pen_read_nothing), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp))
        "failed" -> Text(stringResource(R.string.pen_read_failed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(end = 8.dp))
        else -> Box(Modifier.padding(end = 8.dp).size(28.dp).semantics { contentDescription = label }.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
            contentAlignment = Alignment.Center) {
            if (st == "reading") CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            Icon(painterResource(R.drawable.ic_dia_pen_nfc), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Under the fields: *From the pen · 1.5u primed* (unclear doses: *1u primed · tap to change*), *Also logged: …*. */
@Composable
fun PenFillLine(f: Pens.Fill, roles: SnapshotStateMap<String, Boolean>) {
    val ctx = LocalContext.current
    val w = remember { PenWords(ctx) }
    var edit by remember { mutableStateOf(false) }
    val doses = Pens.ledger.value.pens[f.serial]?.doses.orEmpty()
    val pr = PenEngine.r1(roles.filter { it.value }.keys.sumOf { k -> f.read.doses.firstOrNull { PenEngine.key(it.relTime, it.units) == k }?.units ?: doses[k]?.a ?: 0.0 })
    val primed = if (pr > 0) stringResource(R.string.pen_primed, w.u(pr)) else null
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(when {
            f.unclear -> stringResource(R.string.pen_dialog_tap_change, primed ?: stringResource(R.string.pen_dialog_from_plain))
            primed != null -> stringResource(R.string.pen_dialog_from, primed)
            else -> stringResource(R.string.pen_dialog_from_plain)
        }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = if (f.unclear) Modifier.clickable { edit = true } else Modifier)
        if (f.also > 0) Text(pluralStringResource(R.plurals.pen_dialog_also, f.also, f.also), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (edit) AlertDialog(onDismissRequest = { edit = false },
        title = { Text(stringResource(R.string.pen_q_doses_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            f.read.doses.filter { PenEngine.key(it.relTime, it.units) in roles }.sortedBy { it.time }.forEach { d ->
                val k = PenEngine.key(d.relTime, d.units)
                DoseRow(d.time, d.units, roles[k] == true, d.incomplete, false) { roles[k] = it }
            }
        } },
        confirmButton = { Button(onClick = { edit = false }) { Text(stringResource(R.string.pen_done)) } })
}
