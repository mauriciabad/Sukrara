// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

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
    LaunchedEffect(Unit) {
        if (ReceiverId.libreView(ctx) != null) return@LaunchedEffect
        val lv = ctx.getSharedPreferences("libreview", Context.MODE_PRIVATE)
        val email = lv.getString("email", null)
        val password = lv.getString("password", null)
        if (!email.isNullOrBlank() && !password.isNullOrEmpty())
            withContext(Dispatchers.IO) { runCatching { ReceiverId.fetchLibreView(ctx, email, password) } }
    }

    // Reader mode only while this screen is in front: a tap here goes to Sukrara, not to Juggluco or LibreLink.
    LifecycleResumeEffect(nfc, outcome is Outcome.NeedsAccount) {
        nfcOn = nfc?.isEnabled == true
        val listening = nfc != null && outcome !is Outcome.NeedsAccount
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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sensor", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = pairing
                if (p == null) {
                    Text("No sensor yet", style = MaterialTheme.typography.titleMedium)
                    Text("Scan your FreeStyle Libre 3 or 3 Plus to start reading it here.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("Libre 3 · ${p.serial}", style = MaterialTheme.typography.titleMedium)
                    Text("Started ${date(p.startEpoch)}", style = MaterialTheme.typography.bodyMedium)
                    Text(if (f.source != "direct") "Not in use: the glucose source is Juggluco"
                         else "Now: ${st.source.removePrefix("direct: ")}${st.sourceError?.let { " — $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall)
                    if (f.source != "direct") Button(onClick = { Features.update { it.copy(source = "direct") } }) {
                        Text("Read this sensor")
                    }
                }
            }
        }

        if (nfc != null) Text(if (busy) "Reading the sensor… keep the phone still" else "Hold the top back of your phone against the sensor",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("A new sensor starts and warms up for 60 minutes. A sensor that is already running moves to Sukrara: " +
            "only one app can read a Libre 3, so the app that had it (LibreLink, Juggluco) stops getting readings and " +
            "its alarms stop. To move it back, scan it with that app.", style = MaterialTheme.typography.bodySmall)
        if (f.source != "direct") Text("Juggluco is your glucose source now. If it is reading this sensor, stop it first: " +
            "long-press its icon → Pause app, then App info → Force stop (a paused Juggluco keeps its Bluetooth link to the " +
            "sensor and gets in the way). After the scan Sukrara switches to reading the sensor directly.",
            style = MaterialTheme.typography.bodySmall)

        when {
            nfc == null -> Text("This phone has no NFC, so it can't scan a sensor.", color = MaterialTheme.colorScheme.error)
            !nfcOn -> OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("Turn on NFC") }
        }

        when (val o = outcome) {
            is Outcome.Done -> Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium); Text(o.text, style = MaterialTheme.typography.bodyMedium)
            } }
            is Outcome.Problem -> Card(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(o.title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text(o.text, style = MaterialTheme.typography.bodyMedium)
            } }
            is Outcome.NeedsAccount -> LibreViewSignIn(o.serial, onCancel = { outcome = null }) { msg ->
                outcome = Outcome.Done("Signed in", msg)
            }
            null -> Unit
        }

        if (nfc != null) Toggle("Open Sukrara when you scan a sensor", launchOnScan,
            sub = "Holding the phone against a Libre sensor opens Sukrara when it isn't already open") {
            NfcLaunch.set(ctx, it); launchOnScan = it
        }

        if (pairing != null) TextButton(onClick = {
            SensorPairing.forget(ctx)
            pairing = null
            outcome = null
            if (f.source == "direct") scope.launch { App.owner(ctx).restartSource() }
        }) { Text("Forget this sensor") }
    }
}

/** A tap's result → saved pairing, source switch, and the message to show. */
private fun handle(ctx: Context, r: Libre3Nfc.Result): Outcome = when (r) {
    is Libre3Nfc.Result.Paired -> {
        r.pairing.save(ctx)
        if (Features.value.source != "direct") Features.update { it.copy(source = "direct") }
        else App.owner(ctx).restartSource()
        if (r.activated) Outcome.Done("Sensor ${r.pairing.serial} started",
            "Sukrara is pairing with it over Bluetooth. Readings start after the 60-minute warm-up.")
        else Outcome.Done("Sensor ${r.pairing.serial} is now Sukrara's",
            "Pairing over Bluetooth — the first reading usually arrives within a minute or two.")
    }
    is Libre3Nfc.Result.WrongAccount -> Outcome.NeedsAccount(r.serial)
    is Libre3Nfc.Result.Ended -> Outcome.Problem("Sensor ${r.serial} has ended", "Apply a new sensor and scan it.")
    is Libre3Nfc.Result.Unsupported -> Outcome.Problem("Not supported", r.why)
    is Libre3Nfc.Result.Failed -> Outcome.Problem("Scan didn't work", "${r.why}. Try again: hold the phone against the sensor until it vibrates.")
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
        Text("Sensor $serial belongs to another app", style = MaterialTheme.typography.titleMedium)
        Text("It was started by LibreLink or Juggluco with a LibreView account. Sign in once with that account so " +
            "Sukrara can take the sensor over. Sukrara only reads your account number: it uploads nothing, doesn't " +
            "keep your password, and the other app's LibreView uploads keep working.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(email, { email = it }, label = { Text("LibreView email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
                busy = true; error = null
                scope.launch {
                    val r = withContext(Dispatchers.IO) { runCatching { ReceiverId.fetchLibreView(ctx, email, password) } }
                    busy = false
                    r.onSuccess { password = ""; onDone("Now hold the phone against the sensor again.") }
                        .onFailure { error = it.message ?: it.javaClass.simpleName }
                }
            }) { Text(if (busy) "Signing in…" else "Sign in") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    } }
}

private fun date(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t * 1000))
