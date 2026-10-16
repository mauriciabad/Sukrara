// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.collectAsState
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
import app.mauri.sukrara.BuildConfig
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.follower.FollowerUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface FollowerActions {
    suspend fun resolve(link: String): Result<app.mauri.sukrara.core.Invite>
    fun join(inv: app.mauri.sukrara.core.Invite, me: app.mauri.sukrara.core.Profile)
    fun editProfile()
    fun leave()
    fun openAlerts()
    fun openGraph()
    fun setTestUnfiltered(on: Boolean)
    fun scanNearby(onFound: (app.mauri.sukrara.core.NearbyOwner) -> Unit)
    fun joinNearby(address: String, me: app.mauri.sukrara.core.Profile)
    fun addTreatment(k: String, a: Double, note: String, at: Long)
}

@Composable
fun FollowerJoin(prefill: String?, busy: Boolean, error: String?, a: FollowerActions) {
    val ctx = LocalContext.current
    var me by remember { mutableStateOf(app.mauri.sukrara.core.Profile("", app.mauri.sukrara.core.Avatar.random())) }
    var link by remember(prefill) { mutableStateOf(prefill ?: "") }
    // a pasted / scanned / opened link is looked up at once, so they see who is inviting before they ask
    var invite by remember { mutableStateOf<app.mauri.sukrara.core.Invite?>(null) }
    var lookup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(link) {
        invite = null
        lookup = null
        if (!Proto.isInvite(link)) return@LaunchedEffect
        lookup = "Looking up the invite…"
        a.resolve(link).fold({ invite = it; lookup = null }, { lookup = "Can't open this invite: ${it.message}" })
    }
    var scanning by remember { mutableStateOf(false) }
    var imgError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            // decoding a full-screen screenshot takes a while: never on the main thread
            val got = withContext(Dispatchers.Default) { decodeQrImage(ctx, uri) }
            if (got != null) { link = got; imgError = null } else imgError = "No Sukrara QR code found in that image"
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("I follow someone", style = MaterialTheme.typography.headlineSmall)
        Text("Scan the code on their phone (Share with someone), or open the link they sent you.")
        // the camera preview expands in place of the buttons; back closes it (instead of leaving the app)
        BackHandler(enabled = scanning) { scanning = false }
        Swap(scanning) { on ->
            if (on) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        }
        OutlinedTextField(link, { link = it.trim() }, label = { Text("…or paste the invite link") },
            modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        RevealValue(lookup) { Text(it, style = MaterialTheme.typography.bodySmall,
            color = if (it.startsWith("Can't")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        RevealValue(invite) { inv ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    ProfileRow(inv.profile, "Someone", 56.dp) {
                        Text(inv.profile?.name?.ifBlank { null } ?: "Someone", style = MaterialTheme.typography.titleMedium)
                        Text("wants to share their glucose with you", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        SectionTitle("How they will see you")
        ProfileEditor(me, { me = it }, "Your name")
        RevealValue(error ?: imgError) { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { invite?.let { a.join(it, me) } }, enabled = !busy && invite != null && me.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) {
            Text("Ask for access")
        }
        NearbyJoin(me, busy, a)
    }
}

@Composable
fun FollowerWaiting(u: FollowerUi, a: FollowerActions) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Waiting for approval", style = MaterialTheme.typography.headlineSmall)
        AvatarImage(u.wearer?.avatar, u.wearer?.name ?: "", 72.dp)
        Text("Asked ${u.wearer?.name?.ifBlank { null } ?: u.ownerPub?.let { Proto.shortNpub(it) }} as \"${u.name}\". " +
            "They will see this code — it must match:", style = MaterialTheme.typography.bodyMedium)
        Text(u.code ?: "", fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        CircularProgressIndicator()
        Text("No glucose is shown until the owner allows this phone.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = a::leave) { Text("Cancel request") }
    }
}

@Composable
fun FollowerHome(u: FollowerUi, relays: Map<String, RelayStats>, log: List<String>, a: FollowerActions) {
    var showLog by remember { mutableStateOf(false) }
    val alerts by app.mauri.sukrara.alerts.Alerts.settings.collectAsState()
    val now = nowSeconds()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ProfileRow(u.wearer, "Sukrara", 48.dp) {
            Text(u.wearer?.name?.ifBlank { null } ?: "Sukrara", style = MaterialTheme.typography.headlineSmall)
            Text("Following ${u.ownerPub?.let { Proto.shortNpub(it) }}", style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.clickable { a.editProfile() }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("You appear as", style = MaterialTheme.typography.bodySmall)
            AvatarImage(u.me?.avatar, u.name ?: "", 24.dp)
            Text(u.name ?: "", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
            Text("Edit", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        Reveal(u.status == "revoked") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Access removed", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("The owner stopped sharing with this phone. No new readings will arrive; older ones stay on this phone.")
                }
            }
        }
        val l = u.last
        val noData = u.status == "active" && (l == null || now - l.t >= alerts.cfg(app.mauri.sukrara.alerts.Kind.NO_DATA).value * 60)
        GlucosePanel(l?.sgv, l?.rate, l?.t,
            subtitle = l?.let { "via ${it.via.removePrefix("relay:")} · key epoch ${it.epoch}" })
        RevealValue(if (noData && l != null) (now - l.t) / 60 else null) {
            Text("No data for $it min", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Reveal(u.status == "active" && l == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("Approved — fetching history…")
            }
        }
        // The glucose panel (value, rings, chart) is the screen's main content: above the fold, everything else below.
        Reveal(u.status == "active") { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FollowerEntries(u)
            // item 1: only a roster writer may add entries; follows the newest roster live (cross-fades when it changes)
            Swap(u.canWrite) { can ->
                if (can) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { AddEntry(a) }
                else Text("The wearer hasn't allowed you to add entries", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }

        SectionTitle("Alerts")
        AlertsRow(alerts, follower = true, onClick = a::openAlerts)
        GraphRow(a::openGraph)

        // Widgets and the status-bar chip follow this person too (FollowerExtras); local servers serve their values.
        LocalServersSection()

        SectionTitle("Details")
        val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text("stored readings ${u.count} (this phone's own database) · rejected events ${u.rejects}" +
            (u.lastReject?.let { " (last: $it)" } ?: ""), style = mono)
        Text("key epochs known: up to ${u.epoch} · trusted devices ${u.devices.map { it.take(8) }}", style = mono)
        Text("this phone: ${u.fPub?.let { Proto.npub(it) }}", style = mono)
        Text("Bluetooth: ${u.ble}", style = MaterialTheme.typography.bodySmall)
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
        Reveal(showLog) { LogList(log) }
        Spacer(Modifier.size(24.dp))
    }
}

/** Investigation 07: list owners advertising the Sukrara service nearby; tap one to ask for access over BLE. */
@Composable
private fun NearbyJoin(me: app.mauri.sukrara.core.Profile, busy: Boolean, a: FollowerActions) {
    var scanning by remember { mutableStateOf(false) }
    val found = remember { androidx.compose.runtime.mutableStateMapOf<String, app.mauri.sukrara.core.NearbyOwner>() }
    SectionTitle("Nearby (Bluetooth, no internet needed)")
    Swap(if (!scanning) 0 else if (found.isEmpty()) 1 else 2) { step ->
        if (step == 0) OutlinedButton(onClick = { scanning = true; a.scanNearby { found[it.address] = it } }, modifier = Modifier.fillMaxWidth()) {
            Text("Find phones nearby")
        } else if (step == 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Looking for Sukrara owners… (they must tap \"Pair nearby\")", style = MaterialTheme.typography.bodySmall)
        }
    }
    AnimatedItems(found.values.sortedByDescending { it.rssi }, { it.address }) { o ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.name, fontWeight = FontWeight.SemiBold)
                    Text("${o.address} · ${o.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { a.joinNearby(o.address, me) }, enabled = !busy && me.name.isNotBlank()) { Text("Ask") }
            }
        }
    }
}

/** Treatments received from the wearer (SPEC §5), last 24 h; entries this phone sent show their delivery status. */
@Composable
private fun FollowerEntries(u: FollowerUi) {
    SectionTitle("Insulin, carbs & notes (24 h)")
    val rows = u.treatments.filter { !it.tr.del }
    Reveal(rows.isEmpty()) { Text("Nothing in the last 24 h", style = MaterialTheme.typography.bodySmall) }
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
    AnimatedItems(rows, { it.tr.id }) { e ->
        val tr = e.tr
        val what = when (tr.k) {
            "rapid" -> "Insulin ${tr.a} U"
            "long" -> "Long-acting ${tr.a} U"
            "carbs" -> "Carbs ${tr.a.toInt()} g"
            else -> "Note"
        } + (if (tr.n.isNotBlank()) " · ${tr.n}" else "")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(fmt.format(java.util.Date(tr.t * 1000)), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(what, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            // sending… → sent → accepted: the label cross-fades, its colour follows
            e.status?.let { st ->
                val (label, color) = when (st) {
                    "accepted" -> "accepted" to MaterialTheme.colorScheme.primary
                    "waiting" -> "not accepted yet" to MaterialTheme.colorScheme.error
                    "sent" -> "sent" to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> "sending…" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val tint by animateColorAsState(color, Motion.defaultEffectsSpec(), label = "status")
                Swap(label) { Text(it, color = tint, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** Investigation 07: a follower the owner made a writer can add insulin / carbs / a note (the shared [TreatmentDialog]). */
@Composable
private fun AddEntry(a: FollowerActions) {
    var adding by remember { mutableStateOf(false) }
    FilledTonalButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("Add treatment") }
    if (adding) TreatmentDialog(onDismiss = { adding = false }, onSave = { it.forEach { e -> a.addTreatment(e.k, e.a, e.note, e.at) } })
}

@Composable
fun RoleChoice(onOwner: () -> Unit, onFollower: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sukrara", style = MaterialTheme.typography.headlineMedium)
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
