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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.collectAsState
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
import app.mauri.sukrara.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.owner.Follower
import app.mauri.sukrara.owner.OwnerUi
import kotlinx.coroutines.launch

interface OwnerActions {
    fun create(profile: app.mauri.sukrara.core.Profile)
    fun saveProfile(profile: app.mauri.sukrara.core.Profile)
    fun setInviteLabel(label: String)
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

private fun date(t: Long) = app.mauri.sukrara.graph.Clock.dayTime(t)

@Composable
fun OwnerOnboarding(busy: String?, error: String?, a: OwnerActions) {
    var paste by remember { mutableStateOf(false) }
    var secret by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf(app.mauri.sukrara.core.Profile("", app.mauri.sukrara.core.Avatar.random())) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.onboarding_wearer_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.onboarding_wearer_intro))
        ProfileEditor(profile, { profile = it }, stringResource(R.string.profile_name_label))
        RevealValue(busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp)); Text(it)
            }
        }
        RevealValue(error) { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { a.create(profile) }, enabled = busy == null && profile.name.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_start_fresh))
        }
        OutlinedButton(onClick = a::restoreFromPasswordManager, enabled = busy == null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.onboarding_restore_password_manager))
        }
        Text(stringResource(R.string.onboarding_restore_note),
            style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { paste = !paste }) { Text(stringResource(if (paste) R.string.onboarding_hide_paste else R.string.onboarding_show_paste)) }
        Reveal(paste) { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(secret, { secret = it }, label = { Text("nsec1… or ncryptsec1…") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Reveal(secret.trim().startsWith("ncryptsec1")) {
                OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.onboarding_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Button(onClick = { a.restorePasted(secret, pw) }, enabled = busy == null && secret.isNotBlank()) { Text(stringResource(R.string.onboarding_restore)) }
        } }
    }
}

@Composable
fun OwnerHome(u: OwnerUi, a: OwnerActions) {
    val ctx = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // the wearer's own profile: what followers see; tap to edit
            Row(Modifier.weight(1f).clickable { a.open("profile") }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AvatarImage(u.profile?.avatar, u.profile?.name ?: "", 48.dp)
                Column {
                    Text(u.profile?.name?.ifBlank { null } ?: stringResource(R.string.home_add_your_name), style = MaterialTheme.typography.headlineSmall,
                        color = if (u.profile?.name.isNullOrBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    val n = u.followers.count { it.revoked == null }
                    Text(pluralStringResource(R.plurals.home_wearer_followers, n, n), style = MaterialTheme.typography.bodySmall)
                }
            }
            IconButton(onClick = { a.open("settings") }) {
                Icon(painterResource(app.mauri.sukrara.R.drawable.ic_sym_settings), contentDescription = stringResource(R.string.home_settings))
            }
        }
        LibreViewBanner(onSetUp = { a.open("libreview") }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        // Settings → Backup holds the details; home only nags while there's no backup (AOSP BannerMessagePreference)
        Reveal(u.backupSavedAt == 0L) {
            Card(onClick = { a.open("backup") }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.home_backup_banner_title), fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.home_backup_banner_text), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        GlucosePanel(u.last?.sgv, u.last?.rate, u.last?.t, subtitle = u.sourceError?.let { "${u.source} — $it" }, onAdd = { adding = true })
        // an untested sensor family (investigation 23): the feedback banner, directly under the graph, for as long as it is used
        UntestedSensorBanner()
        // no graph widget on the launcher yet: offer to add it (the app is meant to be used through it)
        HomeWidgetBanner()
        // The glucose panel (value, rings, chart) is the screen's main content: above the fold, everything else below.
        NewSensorButton(u.source) { a.open("newsensor") }
        GlucoseReport(u.last?.t)
        TreatmentsSection()
        Reveal(u.treatments.isNotEmpty()) { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle(stringResource(R.string.home_follower_entries))
            AnimatedItems(u.treatments, { it }) { Text(it, style = MaterialTheme.typography.bodySmall) }
        } }
        Button(onClick = a::share, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(app.mauri.sukrara.R.drawable.ic_sym_share), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
            Text(stringResource(R.string.share_title))
        }
        Spacer(Modifier.size(24.dp))
    }
    if (adding) TreatmentDialog(onDismiss = { adding = false }, onSave = { QuickEntry.save(ctx, it) })
    u.busy?.let { BusyDialog(it) }
}

/**
 * Home's way to the New sensor screen, under the glucose card when it's due: filled while there's no sensor or it has
 * ended (the thing to do now), tonal in the sensor's last day (the end reminder's "have a new sensor ready"). In
 * between it stays out of the way; Settings → Glucose source → Sensor always has it.
 */
@Composable
private fun NewSensorButton(source: String, onClick: () -> Unit) {
    val sensor by app.mauri.sukrara.core.SensorLife.current.collectAsState()
    val now by androidx.compose.runtime.produceState(app.mauri.sukrara.core.Proto.nowS()) {
        while (true) { kotlinx.coroutines.delay(60_000); value = app.mauri.sukrara.core.Proto.nowS() }
    }
    val due = NewSensorDue.of(source, sensor, now)
    Reveal(due != NewSensorDue.NO) {
        val label = stringResource(if (due == NewSensorDue.FIRST) R.string.home_pair_sensor else R.string.home_pair_new_sensor)
        val content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {
            Icon(painterResource(app.mauri.sukrara.R.drawable.ic_sym_nfc), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        val m = Modifier.fillMaxWidth().height(56.dp)
        if (due == NewSensorDue.SOON) androidx.compose.material3.FilledTonalButton(onClick, m, content = content)
        else Button(onClick, m, content = content)
    }
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
internal fun RecoveryDialog(a: OwnerActions, onClose: () -> Unit) {
    var pw by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(R.string.recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.recovery_text))
                Swap(out) { code ->
                    if (code == null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(pw, { pw = it }, label = { Text(stringResource(R.string.recovery_password)) }, singleLine = true,
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
            }) { Text(stringResource(R.string.recovery_create)) }
            else Button(onClick = { a.copy(out!!); onClose() }) { Text(stringResource(R.string.recovery_copy_close)) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.recovery_close)) } },
    )
}

@Composable
fun ShareScreen(u: OwnerUi, a: OwnerActions, onBack: () -> Unit) {
    val link = u.invite
    SettingsPage(stringResource(R.string.share_title), onBack) { Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (link == null) { CircularProgressIndicator(); return@Column }
        var label by remember(link) { mutableStateOf("") }
        OutlinedTextField(label, { label = it.take(app.mauri.sukrara.core.Profile.MAX_NAME); a.setInviteLabel(label) },
            label = { Text(stringResource(R.string.share_label_hint)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.share_label_support)) })
        Text(stringResource(R.string.share_instructions),
            style = MaterialTheme.typography.bodyMedium)
        val qr = remember(link) { qrBitmap(link) }
        Image(qr.asImageBitmap(), contentDescription = stringResource(R.string.share_qr_description), modifier = Modifier.size(280.dp))
        Text(label.trim().let { l -> if (l.isBlank()) stringResource(R.string.share_link_note_unnamed, date(u.inviteExpiry))
            else stringResource(R.string.share_link_note, date(u.inviteExpiry), l) },
            style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.share_they_see), style = MaterialTheme.typography.bodySmall)
            AvatarImage(u.profile?.avatar, u.profile?.name ?: "", 24.dp)
            Text(u.profile?.name?.ifBlank { null } ?: stringResource(R.string.share_no_name), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { a.open("profile") }) { Text(stringResource(R.string.share_edit)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { a.shareLink(link) }) { Text(stringResource(R.string.share_send_link)) }
            OutlinedButton(onClick = { a.copy(link) }) { Text(stringResource(R.string.share_copy)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(stringResource(R.string.share_waiting), style = MaterialTheme.typography.bodySmall)
        }
    } }
}

/** "Mom" vs the invite's "Mom's phone": the same person? null = no label given. Names can be made up, so it's only a hint. */
internal fun labelMatches(label: String, name: String): Boolean? {
    fun norm(x: String) = x.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()   // "Mom's phone" → "mom s phone"
    val l = norm(label)
    val n = norm(name)
    if (l.isEmpty()) return null
    return n.isNotEmpty() && (l == n || l.split(' ').contains(n) || n.split(' ').contains(l))
}

private fun hhmm(t: Long) = app.mauri.sukrara.graph.Clock.time(t)

/**
 * Shown wherever the wearer is when a request arrives. Material basic dialog, centred variant: the follower's picture
 * and name are the headline; custom content shows the invite they used (the wearer's own label for it, whether it
 * matches, when it was sent); actions end-aligned, Allow last. Link requests need no code (the invite is single-use);
 * nearby ones need the code typed from the follower's screen, because a Bluetooth invite reaches every phone in range.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ApproveDialog(u: OwnerUi, a: OwnerActions) {
    val p = u.pending.firstOrNull() ?: return
    var code by remember(p.f) { mutableStateOf("") }
    val match = labelMatches(p.label, p.name)
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.BasicAlertDialog(onDismissRequest = {},
        properties = androidx.compose.ui.window.DialogProperties(dismissOnClickOutside = false)) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.extraLarge, color = cs.surfaceContainerHigh,
            tonalElevation = androidx.compose.material3.AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                AvatarImage(p.avatar, p.name, 96.dp)
                Spacer(Modifier.height(16.dp))
                Text(p.name, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis, color = cs.onSurface)
                Text(stringResource(if (p.nearby) R.string.approve_wants_nearby else R.string.approve_wants),
                    style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                // the invite they used, as the wearer labelled it
                androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = cs.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(if (p.nearby) R.string.approve_pair_nearby else R.string.approve_your_invite).uppercase(), style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant, letterSpacing = 1.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(when {
                                    p.label.isNotBlank() -> stringResource(R.string.approve_invite_for, p.label)
                                    p.nearby -> stringResource(R.string.approve_nearby_window)
                                    else -> stringResource(R.string.approve_no_label)
                                }, style = MaterialTheme.typography.titleMedium, color = cs.onSurface,
                                modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            when (match) {
                                true -> Pill(stringResource(R.string.approve_label_matches), cs.primaryContainer, cs.onPrimaryContainer, check = true)
                                false -> Pill(stringResource(R.string.approve_label_differs), cs.tertiaryContainer, cs.onTertiaryContainer)
                                null -> {}
                            }
                        }
                        Text(if (p.inviteAt > 0) stringResource(R.string.approve_sent_asked, hhmm(p.inviteAt), hhmm(p.at)) else stringResource(R.string.approve_asked, hhmm(p.at)),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
                if (p.others > 0) {
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = cs.errorContainer,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(pluralStringResource(R.plurals.approve_others_tried, p.others, p.others),
                            style = MaterialTheme.typography.bodyMedium, color = cs.onErrorContainer, modifier = Modifier.padding(16.dp))
                    }
                }
                if (p.nearby) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(code, { code = it.filter(Char::isDigit).take(4) }, singleLine = true,
                        label = { Text(stringResource(R.string.approve_code_label)) },
                        textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                            letterSpacing = 8.sp),
                        isError = code.length == 4 && code != p.code,
                        supportingText = { if (code.length == 4 && code != p.code) Text(stringResource(R.string.approve_code_wrong)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.approve_note, p.name),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { a.deny(p.f) }) { Text(stringResource(R.string.approve_deny)) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { a.approve(p.f) }, enabled = !p.nearby || code == p.code) { Text(stringResource(R.string.approve_allow)) }
                }
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color, check: Boolean = false) {
    androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.CircleShape, color = bg) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (check) Icon(painterResource(app.mauri.sukrara.R.drawable.ic_sym_check), contentDescription = null, tint = fg,
                modifier = Modifier.padding(end = 4.dp).size(16.dp))
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}
