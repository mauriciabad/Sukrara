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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
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
 * dialog (reader mode while it is open, without the system sound and without an icon: the fill is the sign, and
 * *Nothing new* / *Couldn't read the pen* shows under the fields).
 */

/** §4.6: a pen entry of the same kind within 45 min of a typed rapid / long amount (the wearer's own log only). */
object PenDuplicate {
    fun find(ctx: Context, list: List<NewEntry>): Treatment? {
        if (App.role(ctx) != "owner") return null
        Treat.init(ctx)
        val entries = Treat.store.all().map(Pens::toEntry)
        return list.filter { it.k == "rapid" || it.k == "long" }
            .firstNotNullOfOrNull { e -> PenEngine.near(entries, e.k, e.at, window = Pens.rules().match)?.let { Treat.store.entry(it.id)?.second } }
    }
}

/** The last read's 3 s word under the dialog's fields: null = none, false = *Nothing new*, true = *Couldn't read the pen*. */
private object PenDialogRead {
    val state = MutableStateFlow<Boolean?>(null)
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
            scope.launch {
                // a card or passport near the phone isn't a pen: nothing shows (only an answered selection reads)
                var selected = false
                val res = withContext(Dispatchers.IO) { runCatching { PenTag.read(tag, onSelected = { selected = true }) { Pens.stopBefore(it) } } }
                val r = res.getOrNull()
                if (PenTag.outcome(selected, res.exceptionOrNull() ?: if (r == null) app.mauri.sukrara.pen.opennov.NotAPenException() else null) == PenTag.Outcome.NOT_PEN) return@launch
                if (r == null) { app.mauri.sukrara.pen.PenHaptics.read(view, false); PenDialogRead.state.value = true; return@launch }
                app.mauri.sukrara.pen.PenHaptics.read(view, true)
                val f = withContext(Dispatchers.IO) { Pens.readForDialog(ctx, r) }
                if (f == null) PenDialogRead.state.value = false else { PenDialogRead.state.value = null; onFill(f) }
            }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS, null)
        onDispose { if (on && act != null) runCatching { nfc?.disableReaderMode(act) } }
    }
}

/**
 * The read's word under the fields for 3 s (round 52: the title-row pen icon is gone): *Nothing new*, or red
 * *Couldn't read the pen*, so a REJECT buzz isn't a mystery. A polite live region says it to TalkBack.
 */
@Composable
fun PenReadWord() {
    val st by PenDialogRead.state.collectAsState()
    LaunchedEffect(st) { if (st != null) { delay(3000); PenDialogRead.state.value = null } }
    RevealValue(st) { failed ->
        Text(stringResource(if (failed) R.string.pen_read_failed else R.string.pen_read_nothing), style = MaterialTheme.typography.bodyMedium,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
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
