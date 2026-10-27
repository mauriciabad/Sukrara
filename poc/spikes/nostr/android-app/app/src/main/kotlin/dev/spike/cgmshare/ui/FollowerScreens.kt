// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.cgmshare.ui

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.spike.cgmshare.BuildConfig
import dev.spike.cgmshare.core.Proto
import dev.spike.cgmshare.core.RelayStats
import dev.spike.cgmshare.follower.FollowerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface FollowerActions {
    fun join(link: String, name: String)
    fun leave()
    fun setAlarms(low: Int, high: Int, on: Boolean)
    fun setTestUnfiltered(on: Boolean)
    fun scanNearby(onFound: (dev.spike.cgmshare.core.NearbyOwner) -> Unit)
    fun joinNearby(address: String, name: String)
    fun addTreatment(k: String, a: Double, note: String)
}

@Composable
fun FollowerJoin(prefill: String?, busy: Boolean, error: String?, a: FollowerActions) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("${Build.MODEL}") }
    var link by remember(prefill) { mutableStateOf(prefill ?: "") }
    var scanning by remember { mutableStateOf(false) }
    var imgError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            // decoding a full-screen screenshot takes a while: never on the main thread
            val got = withContext(Dispatchers.Default) { decodeQrImage(ctx, uri) }
            if (got != null) { link = got; imgError = null } else imgError = "No CGM Share QR code found in that image"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("I follow someone", style = MaterialTheme.typography.headlineSmall)
        Text("Scan the code on their phone (Share with someone), or open the link they sent you.")
        OutlinedTextField(name, { name = it }, label = { Text("Name they will see") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (scanning) {
            QrScanner { link = it; scanning = false }
            TextButton(onClick = { scanning = false }) { Text("Stop camera") }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scanning = true }) { Text("Scan QR code") }
                OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("From image")
                }
            }
        }
        OutlinedTextField(link, { link = it.trim() }, label = { Text("…or paste the cgmshare:join link") },
            modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        (error ?: imgError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { a.join(link, name) }, enabled = !busy && link.startsWith("cgmshare:join?"), modifier = Modifier.fillMaxWidth()) {
            Text("Ask for access")
        }
        NearbyJoin(name, busy, a)
    }
}

@Composable
fun FollowerWaiting(u: FollowerUi, a: FollowerActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Waiting for approval", style = MaterialTheme.typography.headlineSmall)
        Text("Asked ${u.ownerPub?.let { Proto.shortNpub(it) }} as \"${u.name}\". They will see this code — it must match:",
            style = MaterialTheme.typography.bodyMedium)
        Text(u.code ?: "", fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        CircularProgressIndicator()
        Text("No glucose is shown until the owner allows this phone.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = a::leave) { Text("Cancel request") }
    }
}

@Composable
fun FollowerHome(u: FollowerUi, relays: Map<String, RelayStats>, log: List<String>, a: FollowerActions) {
    var showLog by remember { mutableStateOf(false) }
    var low by remember(u.low) { mutableStateOf(u.low.toString()) }
    var high by remember(u.high) { mutableStateOf(u.high.toString()) }
    val now = nowSeconds()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("CGM Share", style = MaterialTheme.typography.headlineSmall)
        Text("Following ${u.ownerPub?.let { Proto.shortNpub(it) }} · as \"${u.name}\"", style = MaterialTheme.typography.bodySmall)
        if (u.status == "revoked") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Access removed", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("The owner stopped sharing with this phone. No new readings will arrive; older ones stay on this phone.")
                }
            }
        }
        val l = u.last
        val noData = u.status == "active" && (l == null || now - l.t >= u.noDataMin * 60)
        BigGlucose(l?.sgv, l?.rate, l?.t, u.low, u.high,
            subtitle = l?.let { "via ${it.via.removePrefix("relay:")} · key epoch ${it.epoch}" })
        if (noData && l != null) Text("No data for ${(now - l.t) / 60} min", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        if (u.status == "active" && l == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Approved — fetching history…")
            }
        }
        SectionTitle("Last 12 h")
        GlucoseChart(u.chart.map { it.t to it.sgv }, u.low, u.high)
        Text("Bluetooth: ${u.ble}", style = MaterialTheme.typography.bodySmall)
        if (u.canWrite) AddEntry(a)

        SectionTitle("Alarms")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Low / high / no data for ${u.noDataMin} min", Modifier.weight(1f))
            Switch(u.alarmsOn, { a.setAlarms(low.toIntOrNull() ?: u.low, high.toIntOrNull() ?: u.high, it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(low, { low = it.filter(Char::isDigit) }, label = { Text("Low") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp))
            OutlinedTextField(high, { high = it.filter(Char::isDigit) }, label = { Text("High") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(100.dp))
            TextButton(onClick = { a.setAlarms(low.toIntOrNull() ?: u.low, high.toIntOrNull() ?: u.high, u.alarmsOn) }) { Text("Save") }
        }

        SectionTitle("Details")
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("stored readings ${u.count} (this phone's own database) · rejected events ${u.rejects}" +
            (u.lastReject?.let { " (last: $it)" } ?: ""), style = mono)
        Text("key epochs known: up to ${u.epoch} · trusted devices ${u.devices.map { it.take(8) }}", style = mono)
        Text("this phone: ${u.fPub?.let { Proto.npub(it) }}", style = mono)
        RelayList(relays)
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("TEST: subscribe without relay-side author filter (debug)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(u.testUnfiltered, a::setTestUnfiltered)
            }
        }
        Row {
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
            TextButton(onClick = a::leave) { Text("Stop following") }
        }
        if (showLog) LogList(log)
        Spacer(Modifier.size(24.dp))
    }
}

/** Investigation 07: list owners advertising the CGM Share service nearby; tap one to ask for access over BLE. */
@Composable
private fun NearbyJoin(name: String, busy: Boolean, a: FollowerActions) {
    var scanning by remember { mutableStateOf(false) }
    val found = remember { androidx.compose.runtime.mutableStateMapOf<String, dev.spike.cgmshare.core.NearbyOwner>() }
    SectionTitle("Nearby (Bluetooth, no internet needed)")
    if (!scanning) {
        OutlinedButton(onClick = { scanning = true; a.scanNearby { found[it.address] = it } }, modifier = Modifier.fillMaxWidth()) {
            Text("Find phones nearby")
        }
    } else if (found.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Looking for CGM Share owners… (they must tap \"Pair nearby\")", style = MaterialTheme.typography.bodySmall)
        }
    }
    found.values.sortedByDescending { it.rssi }.forEach { o ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, fontWeight = FontWeight.SemiBold)
                    Text("${o.address} · ${o.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { a.joinNearby(o.address, name) }, enabled = !busy) { Text("Ask") }
            }
        }
    }
}

/** Investigation 07: a follower the owner made a writer can add insulin / carbs / a note. */
@Composable
private fun AddEntry(a: FollowerActions) {
    var kind by remember { mutableStateOf("rapid") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    SectionTitle("Add entry")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("rapid" to "Insulin", "carbs" to "Carbs", "note" to "Note").forEach { (k, label) ->
            if (kind == k) Button(onClick = {}) { Text(label) } else OutlinedButton(onClick = { kind = k }) { Text(label) }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kind != "note") OutlinedTextField(amount, { amount = it.filter { c -> c.isDigit() || c == '.' } },
            label = { Text(if (kind == "carbs") "g" else "U") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.width(90.dp))
        OutlinedTextField(note, { note = it.take(100) }, label = { Text("Note") }, singleLine = true, modifier = Modifier.weight(1f))
    }
    Button(onClick = { a.addTreatment(kind, amount.toDoubleOrNull() ?: 0.0, note); amount = ""; note = "" },
        enabled = kind == "note" && note.isNotBlank() || (amount.toDoubleOrNull() ?: 0.0) > 0) { Text("Send to owner") }
}

@Composable
fun RoleChoice(onOwner: () -> Unit, onFollower: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("CGM Share", style = MaterialTheme.typography.headlineMedium)
        Text("Share glucose readings end-to-end encrypted over Nostr — no account, no server to run.")
        Button(onClick = onOwner, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("I wear the sensor") }
        OutlinedButton(onClick = onFollower, modifier = Modifier.fillMaxWidth()) { Text("I follow someone") }
        Text("Each phone has one role. A follower never sees an owner's local data — only what arrives through the relays.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FollowerRefresh(refresh: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { refresh(); kotlinx.coroutines.delay(30_000) } }
}
