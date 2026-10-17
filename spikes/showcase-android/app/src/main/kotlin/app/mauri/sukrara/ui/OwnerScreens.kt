// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
    fun create(profile: app.mauri.sukrara.core.Profile)
    fun saveProfile(profile: app.mauri.sukrara.core.Profile)
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
    var profile by remember { mutableStateOf(app.mauri.sukrara.core.Profile("", app.mauri.sukrara.core.Avatar.random())) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("I wear the sensor", style = MaterialTheme.typography.headlineSmall)
        Text("This phone reads your glucose and shares it, end-to-end encrypted, through public Nostr relays.")
        ProfileEditor(profile, { profile = it }, "Your name")
        RevealValue(busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text(it)
            }
        }
        RevealValue(error) { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { a.create(profile) }, enabled = busy == null && profile.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Start fresh (new identity)")
        }
        OutlinedButton(onClick = a::restoreFromPasswordManager, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            Text("Restore from password manager")
        }
        Text("New phone? Restoring keeps all your followers, your name and your picture — nobody has to scan again.",
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { paste = !paste }) { Text(if (paste) "Hide manual restore" else "Restore by pasting a key (nsec / ncryptsec)") }
        Reveal(paste) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(secret, { secret = it }, label = { Text("nsec1… or ncryptsec1…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Reveal(secret.trim().startsWith("ncryptsec1")) {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { a.restorePasted(secret, pw) }, enabled = busy == null && secret.isNotBlank()) { Text("Restore") }
        } }
    }
}

@Composable
fun OwnerHome(u: OwnerUi, relays: Map<String, RelayStats>, log: List<String>, a: OwnerActions) {
    var confirmRemove by remember { mutableStateOf<Follower?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var showRecovery by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the wearer's own profile: what followers see; tap to edit
            Row(Modifier.weight(1f).clickable { a.open("profile") }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarImage(u.profile?.avatar, u.profile?.name ?: "", 48.dp)
                Column {
                    Text(u.profile?.name?.ifBlank { null } ?: "Add your name", style = MaterialTheme.typography.headlineSmall,
                        color = if (u.profile?.name.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text("Wearer · ${u.iPub?.let { Proto.shortNpub(it) } ?: ""} · key epoch ${u.epoch}", style = MaterialTheme.typography.bodySmall)
                }
            }
            TextButton(onClick = { a.open("settings") }) { Text("Settings") }
        }
        GlucosePanel(u.last?.sgv, u.last?.rate, u.last?.t,
            subtitle = "source: ${u.source}${u.sourceError?.let { " — $it" } ?: ""} · ${u.liveSent} live / ${u.chunksSent} chunks sent")
        // The glucose panel (value, rings, chart) is the screen's main content: above the fold, everything else below.
        if (u.source == "direct: no sensor")
            Button(onClick = { a.open("sensor") }, modifier = Modifier.fillMaxWidth()) { Text("Scan your sensor") }
        GlucoseReport(u.last?.t)
        TreatmentsSection()
        Button(onClick = a::share, modifier = Modifier.fillMaxWidth()) { Text("Share with someone") }
        Reveal(u.treatments.isNotEmpty()) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("Entries from followers")
            AnimatedItems(u.treatments, { it }) { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
        WearerSummary()
        if (BuildConfig.DEBUG) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Synthetic source (debug build only)", Modifier.weight(1f))
                Switch(u.synthetic, a::setSynthetic)
            }
        }

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
        Reveal(u.followers.isEmpty()) { Text("Nobody follows you yet.", style = MaterialTheme.typography.bodyMedium) }
        // a newly approved follower's card expands in; a removed one shrinks out and reappears as a "removed" line
        AnimatedItems(active, { it.f }) { f ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AvatarImage(f.avatar, f.name, 40.dp)
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
        AnimatedItems(u.followers.filter { it.revoked != null }, { it.f }) { f ->
            Text("${f.name} — removed ${date(f.revoked!!)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
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

        SectionTitle("Relays")
        Text(if (u.inboxLive) "Access requests: live subscription (app open)"
             else "Access requests: checked with every chunk publish (~5 min)", style = MaterialTheme.typography.bodySmall)
        RelayList(relays)
        Row {
            TextButton(onClick = a::checkNow) { Text("Check requests now") }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide log" else "Show log") }
        }
        Reveal(showLog) { LogList(log) }
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
                Swap(out) { code ->
                    if (code == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(pw, { pw = it }, label = { Text("Password (12+ characters)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation())
                        Reveal(working) { CircularProgressIndicator(Modifier.size(20.dp)) }
                    } else {
                        SelectionContainer { Text(code, fontFamily = FontFamily.Monospace, fontSize = 11.sp) }
                    }
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
        Text("The code holds no key to your glucose: it shows them your name and picture and lets them ask. You approve " +
            "each request. Expires ${date(u.inviteExpiry)}.", style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("They'll see", style = MaterialTheme.typography.bodySmall)
            AvatarImage(u.profile?.avatar, u.profile?.name ?: "", 24.dp)
            Text(u.profile?.name?.ifBlank { null } ?: "no name", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { a.open("profile") }) { Text("Edit") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.shareLink(link) }) { Text("Send link…") }
            OutlinedButton(onClick = { a.copy(link) }) { Text("Copy") }
        }
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
                AvatarImage(p.avatar, p.name, 64.dp, Modifier.align(Alignment.CenterHorizontally))
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
