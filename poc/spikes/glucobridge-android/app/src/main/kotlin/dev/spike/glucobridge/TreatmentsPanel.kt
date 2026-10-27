// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.spike.glucobridge.treatments.Treatment
import java.text.DateFormat
import java.util.Date

private val LABEL = mapOf("rapid" to "Rapid", "long" to "Long", "carbs" to "Carbs", "note" to "Note")

/** Investigation 04: log insulin/carbs/notes (synced with the Mac) and upload them to LibreView. */
@Composable
fun TreatmentsPanel() {
    val store = Spike.store
    val uploader = Spike.uploader
    var kind by remember { mutableStateOf("rapid") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    HorizontalDivider()
    Text("Treatments", style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in Treatment.KINDS) FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(LABEL.getValue(k)) })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (kind != "note") OutlinedTextField(amount, { amount = it }, Modifier.width(110.dp), singleLine = true,
            label = { Text(if (kind == "carbs") "grams" else "units") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        OutlinedTextField(note, { note = it }, Modifier.weight(1f), singleLine = true, label = { Text("note") })
    }
    val value = amount.replace(',', '.').toDoubleOrNull()
    Button(enabled = if (kind == "note") note.isNotBlank() else (value != null && value > 0), onClick = {
        store.add(kind, value ?: 0.0, System.currentTimeMillis() / 1000, note)
        uploader.kick()
        amount = ""; note = ""
    }) { Text("Log ${LABEL.getValue(kind).lowercase()}") }

    val items by store.items.collectAsState()
    val since = System.currentTimeMillis() / 1000 - 24 * 3600
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (tr in items.filter { !it.del && it.t >= since }) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val what = when (tr.k) {
                "carbs" -> "${tr.a.toInt()} g carbs"
                "note" -> "note"
                else -> "${tr.a} U ${tr.k}"
            }
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tr.t * 1000)) + "  $what" +
                (if (tr.n.isNotEmpty()) " · ${tr.n}" else "") + "  ${if (tr.needsUpload) "⇡" else "✓"} ${tr.o}",
                Modifier.weight(1f))
            TextButton(onClick = { store.delete(tr.id); uploader.kick() }) { Text("Delete") }
        }
    }

    HorizontalDivider()
    Text("LibreView", style = MaterialTheme.typography.titleMedium)
    val s by uploader.settings.collectAsState()
    val status by uploader.status.collectAsState()
    var email by remember { mutableStateOf(s.email) }
    var password by remember { mutableStateOf("") }
    OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("email") })
    OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text(if (s.hasPassword) "password (saved)" else "password") },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
    Button(onClick = { uploader.configure(email = email, password = password.ifEmpty { null }); password = "" }) { Text("Save login") }
    Toggle("Upload treatments", s.upload) { uploader.configure(upload = it) }
    Toggle("Upload sensor glucose (from now on)", s.glucose) { uploader.configure(glucose = it) }
    Toggle("Take over account device (logs Juggluco out)", s.takeover) { uploader.configure(takeover = it) }
    Text("Status: $status")
    Text("Device id: ${uploader.deviceId.take(8)}…", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(checked, onChange)
        Text(label, Modifier.padding(start = 8.dp))
    }
