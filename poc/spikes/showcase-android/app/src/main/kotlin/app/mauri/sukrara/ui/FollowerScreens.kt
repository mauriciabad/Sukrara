// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
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
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
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
    /** A screen by its route (settings, alerts, graph…). */
    fun open(screen: String)
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
    /** The lookup line and whether it is an error. */
    var lookup by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    LaunchedEffect(link) {
        invite = null
        lookup = null
        if (!Proto.isInvite(link)) return@LaunchedEffect
        lookup = ctx.getString(R.string.join_looking_up) to false
        a.resolve(link).fold({ invite = it; lookup = null }, { lookup = ctx.getString(R.string.join_error_invite, UserErrors.of(ctx, it)) to true })
    }
    var scanning by remember { mutableStateOf(false) }
    var imgError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            // decoding a full-screen screenshot takes a while: never on the main thread
            val got = withContext(Dispatchers.Default) { decodeQrImage(ctx, uri) }
            if (got != null) { link = got; imgError = null } else imgError = ctx.getString(R.string.join_error_no_qr)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onboarding_follower_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.join_intro))
        // the camera preview expands in place of the buttons; back closes it (instead of leaving the app)
        BackHandler(enabled = scanning) { scanning = false }
        Swap(scanning) { on ->
            if (on) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                QrScanner { link = it; scanning = false }
                TextButton(onClick = { scanning = false }) { Text(stringResource(R.string.join_stop_camera)) }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scanning = true }) { Text(stringResource(R.string.join_scan_qr)) }
                    OutlinedButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(stringResource(R.string.join_from_image))
                    }
                }
            }
        }
        OutlinedTextField(link, { link = it.trim() }, label = { Text(stringResource(R.string.join_paste_link)) },
            modifier = Modifier.fillMaxWidth(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
        RevealValue(lookup) { (text, isError) -> Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
        RevealValue(invite) { inv ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    val someone = stringResource(R.string.join_someone)
                    ProfileRow(inv.profile, someone, 56.dp) {
                        Text(inv.profile?.name?.ifBlank { null } ?: someone, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.join_wants_to_share), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        SectionTitle(stringResource(R.string.join_how_they_see_you))
        ProfileEditor(me, { me = it }, stringResource(R.string.profile_name_label))
        RevealValue(error ?: imgError) { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { invite?.let { a.join(it, me) } }, enabled = !busy && invite != null && me.name.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.join_ask_access))
        }
        NearbyJoin(me, busy, a)
    }
}

@Composable
fun FollowerWaiting(u: FollowerUi, a: FollowerActions) {
    val who = u.wearer?.name?.ifBlank { null } ?: u.ownerPub?.let { Proto.shortNpub(it) } ?: stringResource(R.string.waiting_them)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AvatarImage(u.wearer?.avatar, u.wearer?.name ?: "", 96.dp)
        Text(if (u.nearby) stringResource(R.string.waiting_for, who) else stringResource(R.string.waiting_request_sent), style = MaterialTheme.typography.headlineSmall)
        if (u.nearby) {
            // Bluetooth invites reach every phone in range: the wearer types this code to pick ours
            Text(stringResource(R.string.waiting_code_intro, who), style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text(u.code ?: "", fontSize = 64.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 8.sp)
        } else {
            Text(stringResource(R.string.waiting_note, who),
                style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        CircularProgressIndicator()
        Text(stringResource(R.string.waiting_asked_as, u.name ?: ""), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = a::leave) { Text(stringResource(R.string.waiting_cancel)) }
    }
}

/** The invite was used by someone else first, or the wearer said no: nothing was shared; start over with a new invite. */
@Composable
fun FollowerEnded(u: FollowerUi, a: FollowerActions) {
    val name = u.wearer?.name?.ifBlank { null }
    val used = u.status == "used"
    Column(verticalArrangement = Arrangement.spacedBy(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AvatarImage(u.wearer?.avatar, u.wearer?.name ?: "", 96.dp)
        Text(when {
                used -> stringResource(R.string.follower_notif_invite_used_title)
                name != null -> stringResource(R.string.follower_notif_declined_title, name)
                else -> stringResource(R.string.ended_declined_title_unnamed)
            },
            style = MaterialTheme.typography.headlineSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text(when {
                used && name != null -> stringResource(R.string.ended_used_text, name)
                used -> stringResource(R.string.ended_used_text_unnamed)
                name != null -> stringResource(R.string.ended_declined_text, name)
                else -> stringResource(R.string.ended_declined_text_unnamed)
            },
            style = MaterialTheme.typography.bodyMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Button(onClick = a::leave) { Text(stringResource(R.string.ended_new_invite)) }
    }
}

@Composable
fun FollowerHome(u: FollowerUi, a: FollowerActions) {
    var adding by remember { mutableStateOf(false) }
    var addFocus by remember { mutableStateOf<String?>(null) }
    val alerts by app.mauri.sukrara.alerts.Alerts.settings.collectAsState()
    val now = nowSeconds()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                ProfileRow(u.wearer, "Sukrara", 48.dp) {
                    Text(u.wearer?.name?.ifBlank { null } ?: "Sukrara", style = MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.follower_home_subtitle, u.name ?: ""), style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = { a.open("settings") }) {
                Icon(painterResource(app.mauri.sukrara.R.drawable.ic_sym_settings), contentDescription = stringResource(R.string.home_settings))
            }
        }
        Reveal(u.status == "revoked") {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text(stringResource(R.string.follower_notif_revoked_title), fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(stringResource(R.string.follower_home_revoked_text))
                }
            }
        }
        val l = u.last
        val noData = u.status == "active" && (l == null || now - l.t >= alerts.cfg(app.mauri.sukrara.alerts.Kind.NO_DATA).value * 60)
        // only a roster writer may add entries; follows the newest roster live
        GlucosePanel(l?.sgv, l?.rate, l?.t, onAdd = if (u.status == "active" && u.canWrite) ({ addFocus = it; adding = true }) else null, muteAlerts = true)
        RevealValue(if (noData && l != null) (now - l.t) / 60 else null) {
            Text(stringResource(R.string.follower_home_no_data, app.mauri.sukrara.core.Durations.text(it)), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        Reveal(u.status == "active" && l == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text(stringResource(R.string.follower_home_fetching))
            }
        }
        SystemUiRestartBanner()
        // no graph widget on the launcher yet: offer to add it (the app is meant to be used through it)
        HomeWidgetBanner()
        // The glucose panel (value, rings, chart) is the screen's main content: above the fold, everything else below.
        GlucoseReport(l?.t)
        Reveal(u.status == "active") { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FollowerEntries(u)
            Reveal(!u.canWrite) {
                Text(stringResource(R.string.follower_home_no_write), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        Spacer(Modifier.size(24.dp))
    }
    if (adding) TreatmentDialog(onDismiss = { adding = false }, onSave = { it.forEach { e -> a.addTreatment(e.k, e.a, e.note, e.at) } }, focus = addFocus)
}

/** Investigation 07: list owners advertising the Sukrara service nearby; tap one to ask for access over BLE. */
@Composable
private fun NearbyJoin(me: app.mauri.sukrara.core.Profile, busy: Boolean, a: FollowerActions) {
    var scanning by remember { mutableStateOf(false) }
    val found = remember { androidx.compose.runtime.mutableStateMapOf<String, app.mauri.sukrara.core.NearbyOwner>() }
    SectionTitle(stringResource(R.string.nearby_title))
    Swap(if (!scanning) 0 else if (found.isEmpty()) 1 else 2) { step ->
        if (step == 0) OutlinedButton(onClick = { scanning = true; a.scanNearby { found[it.address] = it } }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.nearby_find))
        } else if (step == 1) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.nearby_looking), style = MaterialTheme.typography.bodySmall)
        }
    }
    AnimatedItems(found.values.sortedByDescending { it.rssi }, { it.address }) { o ->
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.nearby_phone), fontWeight = FontWeight.SemiBold)
                    Text("${o.address} · ${o.rssi} dBm", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { a.joinNearby(o.address, me) }, enabled = !busy && me.name.isNotBlank()) { Text(stringResource(R.string.nearby_ask)) }
            }
        }
    }
}

/**
 * Treatments received from the wearer (SPEC §5), last 24 h, with the time since each ("12m"); entries this phone sent
 * show their delivery status.
 */
@Composable
private fun FollowerEntries(u: FollowerUi) {
    SectionTitle(stringResource(R.string.follower_entries_title))
    val rows = u.treatments.filter { !it.tr.del }
    Reveal(rows.isEmpty()) { Text(stringResource(R.string.follower_entries_empty), style = MaterialTheme.typography.bodySmall) }
    val now = nowSeconds(30_000)
    var penEntry by remember { mutableStateOf<app.mauri.sukrara.treatments.Treatment?>(null) }
    // a pen entry: its doses read-only (the wearer's entries aren't the follower's to change), PRODUCT §4.3
    penEntry?.let { PenEntrySheet(it, readOnly = true) { penEntry = null } }
    AnimatedItems(rows, { it.tr.id }) { e ->
        val tr = e.tr
        val amount = app.mauri.sukrara.treatments.TreatmentMarks.amount(tr)
        val what = when (tr.k) {
            "rapid" -> stringResource(R.string.follower_entry_rapid, amount ?: "")
            "long" -> stringResource(R.string.follower_entry_long, amount ?: "")
            "carbs" -> stringResource(R.string.follower_entry_carbs, amount ?: "")
            else -> stringResource(R.string.treatment_note)
        } + (if (tr.n.isNotBlank()) " · ${tr.n}" else "") +
            (if (isPenEntry(tr)) " · " + stringResource(R.string.treatments_row_from, stringResource(R.string.pen_row_the_pen)) else "")
        Row(Modifier.then(if (isPenEntry(tr)) Modifier.clickable { penEntry = tr } else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(app.mauri.sukrara.graph.Clock.time(tr.t), fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
            Text(what, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            Text(app.mauri.sukrara.treatments.TreatmentMarks.duration(now - tr.t), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // sending… → sent → accepted: the label cross-fades, its colour follows
            e.status?.let { st ->
                val (label, color) = when (st) {
                    "accepted" -> stringResource(R.string.follower_entry_accepted) to MaterialTheme.colorScheme.primary
                    "waiting" -> stringResource(R.string.follower_entry_waiting) to MaterialTheme.colorScheme.error
                    "sent" -> stringResource(R.string.follower_entry_sent) to MaterialTheme.colorScheme.onSurfaceVariant
                    else -> stringResource(R.string.follower_entry_sending) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                val tint by animateColorAsState(color, Motion.defaultEffectsSpec(), label = "status")
                Swap(label) { Text(it, color = tint, style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** Investigation 07: a follower the owner made a writer can add insulin / carbs / a note (the shared [TreatmentDialog]). */
@Composable
fun RoleChoice(onOwner: () -> Unit, onFollower: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Sukrara", style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.role_intro))
        Button(onClick = onOwner, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text(stringResource(R.string.onboarding_wearer_title)) }
        OutlinedButton(onClick = onFollower, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_follower_title)) }
        Text(stringResource(R.string.role_note),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun FollowerRefresh(refresh: () -> Unit) {
    LaunchedEffect(Unit) { while (true) { refresh(); kotlinx.coroutines.delay(30_000) } }
}
