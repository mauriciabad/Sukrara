// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.owner.Follower
import app.mauri.sukrara.owner.OwnerUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

interface OwnerActions {
    fun create()
    fun restoreFromPasswordManager()
    fun restorePasted(secret: String, password: String?)
    fun saveBackup()
    fun share()
    fun shareLink(link: String)
    fun copy(text: String)
    fun approve(f: String)
    fun deny(f: String)
    fun revoke(f: String)
    fun setSynthetic(on: Boolean)
    fun checkNow()
    suspend fun ncryptsec(password: String): String?
    fun nearbyPairing()
    fun setWriter(f: String, on: Boolean)
    fun open(screen: String)
}

private fun date(t: Long) = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(t * 1000))

@Composable
fun OwnerOnboarding(busy: String?, error: String?, a: OwnerActions) {
    var paste by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("I wear the sensor", style = MaterialTheme.typography.headlineSmall)
        Text("This phone reads your glucose and shares it, end-to-end encrypted, through public Nostr relays.")
        if (busy != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text(busy)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = a::create, enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Start fresh (new identity)") }
        OutlinedButton(onClick = a::restoreFromPasswordManager, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            Text("Restore from password manager")
        }
        Text("New phone? Restoring keeps all your followers — nobody has to scan again.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { paste = !paste }) { Text(if (paste) "Hide manual restore" else "Restore by pasting a key (nsec / ncryptsec)") }
        if (paste) {
            OutlinedTextField(secret, { secret = it }, label = { Text("nsec1… or ncryptsec1…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            if (secret.trim().startsWith("ncryptsec1")) {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { a.restorePasted(secret, pw) }, enabled = busy == null && secret.isNotBlank()) { Text("Restore") }
        }
    }
}

@Composable
fun OwnerHome(u: OwnerUi, relays: Map<String, RelayStats>, log: List<String>, a: OwnerActions) {
    var confirmRemove by remember { mutableStateOf<Follower?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Sukrara", style = MaterialTheme.typography.headlineSmall)
                Text("Wearer · ${u.iPub?.let { Proto.shortNpub(it) } ?: ""} · key epoch ${u.epoch}", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { a.open("settings") }) { Text("Settings") }
        }
        BigGlucose(u.last?.sgv, u.last?.rate, u.last?.t, 65, 185,
            subtitle = "source: ${u.source}${u.sourceError?.let { " — $it" } ?: ""} · ${u.liveSent} live / ${u.chunksSent} chunks sent")
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Synthetic source (debug build only)", Modifier.weight(1f))
                Switch(u.synthetic, a::setSynthetic)
            }
        }
        WearerSummary()
        Button(onClick = a::share, modifier = Modifier.fillMaxWidth()) { Text("Share with someone") }
        OutlinedButton(onClick = { a.open("treatments") }, modifier = Modifier.fillMaxWidth()) { Text("Treatments & LibreView") }

        SectionTitle("Nearby (Bluetooth)")
        val pairing = u.nearbyUntil > Proto.nowS()
        Text(if (!u.bleOn) "Bluetooth transport off (permission or Bluetooth disabled)"
             else "Advertising · ${u.bleCentrals} follower(s) connected over Bluetooth" +
                (if (pairing) " · pairing open until ${date(u.nearbyUntil)}" else ""), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = a::nearbyPairing, enabled = u.bleOn, modifier = Modifier.fillMaxWidth()) {
            Text(if (pairing) "Pairing nearby… (tap to extend 10 min)" else "Pair nearby (10 min)")
        }

        SectionTitle("Followers")
        val active = u.followers.filter { it.revoked == null }
        if (u.followers.isEmpty()) Text("Nobody follows you yet.", style = MaterialTheme.typography.bodyMedium)
        active.forEach { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, fontWeight = FontWeight.SemiBold)
                        Text("since ${date(f.since)} · ${f.f.take(8)}…", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Can add entries", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Switch(f.f in u.writers, { a.setWriter(f.f, it) })
                        }
                    }
                    OutlinedButton(onClick = { confirmRemove = f }) { Text("Remove") }
                }
            }
        }
        u.followers.filter { it.revoked != null }.forEach { f ->
            Text("${f.name} — removed ${date(f.revoked!!)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }

        if (u.treatments.isNotEmpty()) {
            SectionTitle("Entries from followers")
            u.treatments.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        SectionTitle("Backup")
        val saved = u.backupSavedAt > 0
        Card(colors = CardDefaults.cardColors(containerColor = if (saved) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer),
            modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (saved) "Identity saved to a password manager (${date(u.backupSavedAt)})"
                     else "Not backed up — if you lose this phone, every follower must be invited again.",
                    fontWeight = FontWeight.SemiBold)
                Text("Owner backup on relays: ${if (u.backupPublishedAt > 0) date(u.backupPublishedAt) else "not yet"} " +
                    "(followers, keys and invites, encrypted to your identity)", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = a::saveBackup) { Text(if (saved) "Save again" else "Save to password manager") }
                    TextButton(onClick = { showRecovery = true }) { Text("Recovery code") }
                }
            }
        }

        SectionTitle("Last 12 h")
        GlucoseChart(u.chart.map { it.t to it.sgv }, 65, 185)

        SectionTitle("Relays")
        Text(if (u.inboxLive) "Access requests: live subscription (app open)"
             else "Access requests: checked with every chunk publish (~5 min)", style = MaterialTheme.typography.bodySmall)
        RelayList(relays)
        Row {
            TextButton(onClick = a::checkNow) { Text("Check requests now") }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        }
        if (showLog) LogList(log)
        Spacer(Modifier.size(24.dp))
    }

    confirmRemove?.let { f ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${f.name}?") },
            text = { Text("They stop receiving new readings immediately: Sukrara rotates the read key and re-sends it to everybody else.") },
            confirmButton = { Button(onClick = { a.revoke(f.f); confirmRemove = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Cancel") } },
        )
    }
    if (showRecovery) RecoveryDialog(a) { showRecovery = false }
    u.busy?.let { BusyDialog(it) }
}

@Composable
private fun BusyDialog(text: String) {
    AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(24.dp)); Text(text)
        }
    })
}

/** Optional NIP-49 recovery code for people who want a printable sheet (encrypted with their password). */
@Composable
private fun RecoveryDialog(a: OwnerActions, onClose: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Recovery code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("An encrypted copy of your identity (NIP-49). Print it or keep it somewhere safe; you need the password to use it.")
                if (out == null) {
                    OutlinedTextField(pw, { pw = it }, label = { Text("Password (12+ characters)") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    if (working) CircularProgressIndicator(Modifier.size(20.dp))
                } else {
                    SelectionContainer { Text(out!!, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                }
            }
        },
        confirmButton = {
            if (out == null) Button(enabled = pw.length >= 12 && !working, onClick = {
                working = true
                scope.launch { out = a.ncryptsec(pw); working = false }
            }) { Text("Create") }
            else Button(onClick = { a.copy(out!!); onClose() }) { Text("Copy & close") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
fun ShareScreen(u: OwnerUi, a: OwnerActions, onBack: () -> Unit) {
    val link = u.invite
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Share your glucose", style = MaterialTheme.typography.headlineSmall)
        if (link == null) { CircularProgressIndicator(); return@Column }
        Text("Ask them to install Sukrara, choose \"I follow someone\" and scan this code — or send them the link.",
            style = MaterialTheme.typography.bodyMedium)
        val qr = remember(link) { qrBitmap(link) }
        Image(qr.asImageBitmap(), contentDescription = "invite QR code", modifier = Modifier.size(280.dp))
        Text("The code holds no key: it only lets them ask. You approve each request. Expires ${date(u.inviteExpiry)}.",
            style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.shareLink(link) }) { Text("Send link…") }
            OutlinedButton(onClick = { a.copy(link) }) { Text("Copy") }
        }
        // Sukrara for Mac reads both forms now; GlucoseBar builds before the gap audit (and CGM Share) need cgmshare:.
        TextButton(onClick = { Proto.legacyLink(link)?.let(a::copy) }) { Text("Copy cgmshare: link (older Mac builds)") }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Waiting for their request…", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = onBack) { Text("Done") }
    }
}

/** Shown wherever the owner is when a valid request arrives. */
@Composable
fun ApproveDialog(u: OwnerUi, a: OwnerActions) {
    val p = u.pending.firstOrNull() ?: return
    AlertDialog(
        onDismissRequest = {},
        title = { Text("${p.name} wants to see your glucose") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Check that their phone shows the same code:")
                Text(p.code, fontSize = 48.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
                Text("Only allow people you know. You can remove them later.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { a.approve(p.f) }) { Text("Allow") } },
        dismissButton = { TextButton(onClick = { a.deny(p.f) }) { Text("Deny") } },
    )
}
