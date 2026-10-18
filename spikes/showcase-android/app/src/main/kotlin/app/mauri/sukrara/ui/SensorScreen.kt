// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.App
import app.mauri.sukrara.direct.Libre3Nfc
import app.mauri.sukrara.direct.ReceiverId
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.wearer.Live
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the screen shows after a tap. */
private sealed interface Outcome {
    data class Done(val title: String, val text: String) : Outcome
    data class Problem(val title: String, val text: String) : Outcome
    data class NeedsAccount(val serial: String) : Outcome
    /** A Libre 1 / 2 / 2 Plus was tapped: not supported, point to Juggluco. */
    data object Libre2 : Outcome
}

/**
 * Settings → Glucose source → Sensor: start a new Libre 3 or take over a running one with an NFC tap, then Sukrara
 * pairs with it over Bluetooth by itself (Libre3Nfc → SensorPairing → Libre3Client's fresh pairing).
 */
@Composable
fun SensorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val scope = rememberCoroutineScope()
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var pairing by remember { mutableStateOf(runCatching { SensorPairing.load(ctx) }.getOrNull()) }
    var busy by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    var nfcOn by remember { mutableStateOf(nfc?.isEnabled == true) }
    var launchOnScan by remember { mutableStateOf(NfcLaunch.isOn(ctx)) }

    // Sukrara's own LibreView setup (Treatments & LibreView) already knows the account: learn its receiver ID up
    // front, so a sensor started by LibreLink or Juggluco moves over with a single tap.
    val idx = indexing   // composed for the search index: no network, no NFC reader mode
    LaunchedEffect(Unit) {
        if (idx || ReceiverId.libreView(ctx) != null) return@LaunchedEffect
        val lv = ctx.getSharedPreferences("libreview", Context.MODE_PRIVATE)
        val email = lv.getString("email", null)
        val password = lv.getString("password", null)
        if (!email.isNullOrBlank() && !password.isNullOrEmpty())
            withContext(Dispatchers.IO) { runCatching { ReceiverId.fetchLibreView(ctx, email, password) } }
    }

    // Reader mode only while this screen is in front: a tap here goes to Sukrara, not to Juggluco or LibreLink.
    LifecycleResumeEffect(nfc, outcome is Outcome.NeedsAccount) {
        nfcOn = nfc?.isEnabled == true
        val listening = !idx && nfc != null && outcome !is Outcome.NeedsAccount
        if (listening) nfc.enableReaderMode(activity, { tag ->
            activity.runOnUiThread { busy = true; outcome = null }
            val r = Libre3Nfc.scan(tag, ReceiverId.takeoverCandidates(ctx), ReceiverId.forActivation(ctx))
            activity.runOnUiThread {
                busy = false
                outcome = handle(ctx, r)
                if (r is Libre3Nfc.Result.Paired) pairing = r.pairing
            }
        }, NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        onPauseOrDispose { if (listening) nfc.disableReaderMode(activity) }
    }

    SettingsPage(stringResource(R.string.sensor_title), onBack) {
        SettingsHeader { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = pairing
                if (p == null) {
                    Text(stringResource(R.string.sensor_none), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.sensor_none_text), style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Libre 3 · ${p.serial}", style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.sensor_started, date(p.startEpoch)), style = MaterialTheme.typography.bodyMedium)
                    Text(if (f.source != "direct") stringResource(R.string.sensor_not_in_use)
                         else stringResource(R.string.sensor_now, st.source.removePrefix("direct: ") + (st.sourceError?.let { " — $it" } ?: "")),
                        style = MaterialTheme.typography.bodySmall)
                    if (f.source != "direct") Button(onClick = { Features.update { it.copy(source = "direct") } }) {
                        Text(stringResource(R.string.sensor_use))
                    }
                }
            }
        }

        if (nfc != null) Text(stringResource(if (busy) R.string.sensor_reading else R.string.sensor_hold),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.sensor_takeover_note), style = MaterialTheme.typography.bodySmall)
        if (f.source != "direct") Text(stringResource(R.string.sensor_juggluco_note),
            style = MaterialTheme.typography.bodySmall)

        when {
            nfc == null -> Text(stringResource(R.string.sensor_no_nfc), color = MaterialTheme.colorScheme.error)
            !nfcOn -> OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text(stringResource(R.string.sensor_turn_on_nfc)) }
        }

        when (val o = outcome) {
            is Outcome.Done -> Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium); Text(o.text, style = MaterialTheme.typography.bodyMedium)
            } }
            is Outcome.Problem -> Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(o.text, style = MaterialTheme.typography.bodyMedium)
            } }
            is Outcome.Libre2 -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.sensor_libre2_title), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    Text(stringResource(R.string.sensor_libre2_text), style = MaterialTheme.typography.bodyMedium)
                    val nav = LocalNav.current
                    if (nav != null) OutlinedButton(onClick = { nav.open("source") }) {
                        Text(stringResource(R.string.sensor_libre2_source))
                    }
                }
            }
            is Outcome.NeedsAccount -> LibreViewSignIn(o.serial, onCancel = { outcome = null }) { msg ->
                outcome = Outcome.Done(ctx.getString(R.string.sensor_signed_in), msg)
            }
            null -> Unit
        }
        } }

        val launchTitle = stringResource(R.string.sensor_launch_on_scan)
        val launchSummary = stringResource(R.string.sensor_launch_on_scan_summary)
        val forget = stringResource(R.string.sensor_forget)
        SettingsGroup {
            if (nfc != null) switch(launchTitle, launchOnScan, icon = app.mauri.sukrara.R.drawable.ic_sym_nfc,
                summary = launchSummary) { NfcLaunch.set(ctx, it); launchOnScan = it }
            if (pairing != null) action(forget, icon = app.mauri.sukrara.R.drawable.ic_sym_person_remove) {
                SensorPairing.forget(ctx)
                pairing = null
                outcome = null
                if (f.source == "direct") scope.launch { App.owner(ctx).restartSource() }
            }
        }
    }
}

/** A tap's result → saved pairing, source switch, and the message to show. */
private fun handle(ctx: Context, r: Libre3Nfc.Result): Outcome = when (r) {
    is Libre3Nfc.Result.Paired -> {
        r.pairing.save(ctx)
        if (Features.value.source != "direct") Features.update { it.copy(source = "direct") }
        else App.owner(ctx).restartSource()
        if (r.activated) Outcome.Done(ctx.getString(R.string.sensor_started_title, r.pairing.serial),
            ctx.getString(R.string.sensor_started_text))
        else Outcome.Done(ctx.getString(R.string.sensor_taken_over_title, r.pairing.serial),
            ctx.getString(R.string.sensor_taken_over_text))
    }
    is Libre3Nfc.Result.WrongAccount -> Outcome.NeedsAccount(r.serial)
    is Libre3Nfc.Result.Libre2 -> Outcome.Libre2
    is Libre3Nfc.Result.Ended -> Outcome.Problem(ctx.getString(R.string.sensor_ended_title, r.serial), ctx.getString(R.string.sensor_ended_text))
    is Libre3Nfc.Result.Unsupported -> Outcome.Problem(ctx.getString(R.string.sensor_unsupported), r.why)
    is Libre3Nfc.Result.Failed -> Outcome.Problem(ctx.getString(R.string.sensor_scan_failed), ctx.getString(R.string.sensor_scan_failed_text, r.why))
}

/**
 * The sensor was started by another app under a LibreView account: learn that account's receiver ID once. Sukrara
 * keeps only the derived number, never the password; nothing is uploaded and the other app's LibreView link stays.
 */
@Composable
private fun LibreViewSignIn(serial: String, onCancel: () -> Unit, onDone: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf(ctx.getSharedPreferences("libreview", Context.MODE_PRIVATE).getString("email", "")!!) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.sensor_other_app_title, serial), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.sensor_other_app_text), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(email, { email = it }, label = { Text(stringResource(R.string.sensor_libreview_email)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.onboarding_password)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { ReceiverId.fetchLibreView(ctx, email, password) } }
                    busy = false
                    r.onSuccess { password = ""; onDone(ctx.getString(R.string.sensor_scan_again)) }
                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            }) { Text(stringResource(if (busy) R.string.sensor_signing_in else R.string.sensor_sign_in)) }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.treatment_cancel)) }
        }
    } }
}

private fun date(t: Long) = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMHHmm"),
    Locale.getDefault()).format(Date(t * 1000))
