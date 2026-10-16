// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.Role
import app.mauri.sukrara.features.HookPosition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.treatments.Treat
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.wearer.Live
import java.text.DateFormat
import java.util.Date

/** One line per local feature on the wearer's home screen. */
@Composable
fun WearerSummary() {
    val st by Live.status.collectAsState()
    val f by Features.state.collectAsState()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            fun on(b: Boolean) = if (b) "on" else "off"
            Text("Status bar: chip ${on(f.chip)} · number icon ${on(f.icon)}", style = MaterialTheme.typography.bodySmall)
            Text("Mac link (Sukrara for Mac, legacy): " + when {
                !f.macBle -> "off"
                st.macError != null -> st.macError!!
                st.macSubscribed > 0 -> "${st.macSubscribed} Mac connected"
                st.macAdvertising -> "advertising"
                else -> "starting"
            }, style = MaterialTheme.typography.bodySmall)
            Text("Nostr: ${if (!f.nostr) "off" else if (f.nostrIntervalMin == 0) "every reading" else "every ${f.nostrIntervalMin} min"} · " +
                "Bluetooth followers ${on(f.bleFollowers)}", style = MaterialTheme.typography.bodySmall)
            Text("Garmin: ${st.garmin}" + (if (st.watchPolledAt > 0) " · watch asked ${sinceText(st.watchPolledAt / 1000)}" else ""),
                style = MaterialTheme.typography.bodySmall)
            Text("LibreView: ${if (f.libreview) st.libreview else "off (not set up)"}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun sinceText(t: Long): String {
    val s = System.currentTimeMillis() / 1000 - t
    return if (s < 90) "now" else "${s / 60} min ago"
}

/**
 * A settings switch row (Material list item): the whole row toggles, and a disabled row is greyed out as a whole —
 * label, summary and switch (38 % content alpha), like AndroidX Preference's `dependency`.
 */
@Composable
private fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, sub: String? = null, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            Text(label)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A single-choice settings row (radio button), greyed out as a whole when disabled. */
@Composable
private fun RadioRow(label: String, selected: Boolean, enabled: Boolean, sub: String? = null, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
        .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.size(8.dp))
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else DISABLED_ALPHA)) {
            Text(label)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
    }
}

private const val DISABLED_ALPHA = 0.38f

private fun set(f: (FeatureState) -> FeatureState) { Features.update(f) }

/**
 * Garmin watch server + Nightscout read API, for both roles (the follower serves the followed person's values).
 * The LAN URL and API secret are shown here only: nothing else on the screen is a secret.
 */
@Composable
fun LocalServersSection() {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var showSecret by remember { mutableStateOf(false) }
    SectionTitle("Garmin")
    Toggle("Watch server (xDrip+ compatible, 127.0.0.1:17580)", f.garmin, sub = st.garmin +
        (if (st.watchPolledAt > 0) " · last request ${sinceText(st.watchPolledAt / 1000)}" else "")) { v -> set { it.copy(garmin = v) } }
    SectionTitle("Nightscout API")
    Toggle("Nightscout-compatible read API", f.nightscout,
        sub = "For Home Assistant, xDrip+, Nightguard, clocks: http://127.0.0.1:${Features.NIGHTSCOUT_PORT} · ${st.nightscout}") { v ->
        set { it.copy(nightscout = v) }
    }
    Toggle("Also on this network (Wi-Fi)", f.nightscoutLan, enabled = f.nightscout,
        sub = "Other devices need the API secret. Plain HTTP: only on networks you trust") { v -> set { it.copy(nightscoutLan = v) } }
    if (f.nightscout && f.nightscoutLan) {
        TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide API secret" else "Show API secret") }
        if (showSecret) Text("API secret: ${Features.nightscoutSecret(ctx)}", style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var confirmDirect by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }

        SectionTitle("Glucose source")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(f.source == "juggluco", { set { it.copy(source = "juggluco") } }, label = { Text("Juggluco (recommended)") })
            FilterChip(f.source == "direct", { if (f.source != "direct") confirmDirect = true }, label = { Text("Libre 3 directly") })
        }
        Text(if (f.source == "juggluco") "Reads Juggluco's local web server (127.0.0.1:17580). Juggluco keeps the sensor and its alarms."
             else "Sukrara holds the sensor itself. Juggluco must be stopped, and its alarms don't ring.",
            style = MaterialTheme.typography.bodySmall)
        Text("Now: ${st.source}${st.sourceError?.let { " — $it" } ?: ""}", style = MaterialTheme.typography.bodySmall)
        if (f.source == "direct") {
            Text("Sensor link priority", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in Features.PRIORITIES) FilterChip(f.connPriority == p, { set { it.copy(connPriority = p) } }, label = { Text(p) })
            }
        }

        SectionTitle("Sharing")
        Toggle("Share over Nostr relays", f.nostr, sub = "End-to-end encrypted; followers anywhere") { v -> set { it.copy(nostr = v) } }
        Text("Upload interval", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in listOf(0, 5, 15, 30)) FilterChip(f.nostrIntervalMin == m, { set { it.copy(nostrIntervalMin = m) } }, enabled = f.nostr,
                label = { Text(if (m == 0) "Every reading" else "$m min") })
        }
        Toggle("Bluetooth followers nearby", f.bleFollowers, sub = "Same encrypted events over Bluetooth LE (no internet needed)") { v ->
            set { it.copy(bleFollowers = v) }
        }

        SectionTitle("Mac")
        Toggle("Legacy Mac link (Sukrara for Mac, unencrypted Bluetooth)", f.macBle,
            sub = "Unencrypted value + history + treatments, like GlucoBridge. Status: " +
                (st.macError ?: if (st.macSubscribed > 0) "${st.macSubscribed} connected" else if (st.macAdvertising) "advertising" else "idle")) { v ->
            set { it.copy(macBle = v) }
        }

        SectionTitle("Status bar")
        Toggle("Live Update chip", f.chip, sub = "The number next to the clock (Android 16+)") { v -> set { it.copy(chip = v) } }
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (f.chip && !nm.canPostPromotedNotifications()) {
            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }) { Text("Allow Live Updates for Sukrara") }
        }
        Toggle("Number as notification icon", f.icon) { v -> set { it.copy(icon = v) } }
        Toggle("Don't refresh while the screen is off", f.screenOffSkip,
            sub = "Saves a post per minute; catches up on screen on (${st.notificationPosts} posts, ${st.notificationSkipped} skipped)") { v ->
            set { it.copy(screenOffSkip = v) }
        }
        RootStatusBarSettings(f)

        LocalServersSection()

        SectionTitle("LibreView")
        Text(if (f.libreview) "Upload on · ${st.libreview}" else "Off. Set it up under Treatments & LibreView.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(24.dp))
    }
    if (confirmDirect) AlertDialog(
        onDismissRequest = { confirmDirect = false },
        title = { Text("Read the sensor directly?") },
        text = { Text("Juggluco must be stopped first: only one app can hold the Libre 3. While Sukrara holds it, Juggluco's " +
            "alarms (low/high) do NOT ring. It needs the sensor's pairing file (see docs/02). Only for testing.") },
        confirmButton = { Button(onClick = { set { it.copy(source = "direct") }; confirmDirect = false }) { Text("Switch") } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text("Cancel") } },
    )
}

private val LABEL = mapOf("rapid" to "Rapid", "long" to "Long", "carbs" to "Carbs", "note" to "Note")

/** Investigation 04: log insulin / carbs / notes (synced with the Mac over the legacy link) + the LibreView setup. */
@Composable
fun TreatmentsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Treat.init(ctx) }
    val store = Treat.store
    val uploader = Treat.uploader
    var kind by remember { mutableStateOf("rapid") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val f by Features.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Treatments", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
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
        val recent = items.filter { !it.del && it.t >= since }
        if (recent.isEmpty()) Text("Nothing in the last 24 h.", style = MaterialTheme.typography.bodySmall)
        for (tr in recent) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val what = when (tr.k) {
                "carbs" -> "${tr.a.toInt()} g carbs"
                "note" -> "note"
                else -> "${tr.a} U ${tr.k}"
            }
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tr.t * 1000)) + "  $what" +
                (if (tr.n.isNotEmpty()) " · ${tr.n}" else "") + "  · ${tr.o}" + (if (f.libreview) if (tr.needsUpload) " ⇡" else " ✓" else ""),
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { store.delete(tr.id); uploader.kick() }) { Text("Delete") }
        }
        Text("Synced with Sukrara for Mac (legacy link). Entries from followers with \"Can add entries\" land here too.",
            style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("LibreView upload", style = MaterialTheme.typography.titleMedium)
        Text("Off by default. LibreView is a doctor-facing record: uploaded entries can't be deleted there. " +
            "Test with a throwaway account first.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        val s by uploader.settings.collectAsState()
        val status by uploader.status.collectAsState()
        var email by remember { mutableStateOf(s.email) }
        var password by remember { mutableStateOf("") }
        OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("LibreView email") })
        OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(if (s.hasPassword) "password (saved)" else "password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        Button(onClick = { uploader.configure(email = email, password = password.ifEmpty { null }); password = "" }) { Text("Save login") }
        Toggle("Upload to LibreView", f.libreview, enabled = s.email.isNotEmpty() && s.hasPassword,
            sub = "Treatments; sensor glucose too in direct mode") { v -> set { it.copy(libreview = v) }; uploader.kick() }
        Toggle("Upload sensor glucose (direct mode, from now on)", s.glucose) { uploader.configure(glucose = it) }
        Toggle("Take over the account's device (logs the Libre app / Juggluco out of LibreView)", s.takeover) { uploader.configure(takeover = it) }
        Text("Upload every", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (m in listOf(5, 15, 60)) FilterChip(f.libreviewIntervalMin == m, { set { it.copy(libreviewIntervalMin = m) } }, label = { Text("$m min") })
        }
        Text("Status: ${if (f.libreview) status else "off"}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.size(24.dp))
    }
}

/**
 * Root status bar (investigation 05), following the Android settings pattern for dependent options: the main switch,
 * then options that stay visible but are disabled while it's off (or without root), and the placement as a
 * single-choice radio list. "System icons" works with root alone; the colour and the other placements come from the
 * Sukrara module in LSPosed (scope System UI).
 */
@Composable
private fun RootStatusBarSettings(f: FeatureState) {
    val rootBar = app.mauri.sukrara.status.RootStatusBar
    val root by rootBar.root.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { rootBar.checkRoot() }
    val ok = root == true
    Toggle("Value in the status bar (root)", f.hook && ok, enabled = ok, sub = when (root) {
        null -> "Checking root…"
        false -> "Needs root: grant it to Sukrara in KernelSU."
        true -> "Colour and most placements need the Sukrara module in LSPosed (scope System UI) and a System UI " +
            "restart." + if (f.systemIcons) " · ${rootBar.state}" else ""
    }) { v -> set { it.copy(hook = v) } }
    val on = ok && f.hook
    Toggle("Colour by range", f.hookColor, enabled = on,
        sub = "Low and high red, slightly high orange; in range like the clock") { v -> set { it.copy(hookColor = v) } }
    Text("Placement", style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 8.dp).alpha(if (on) 1f else DISABLED_ALPHA))
    Column(Modifier.selectableGroup()) {
        for ((label, pos) in Features.HOOK_POSITIONS) {
            RadioRow(label, selected = f.hookPosition == pos, enabled = on, sub = PLACEMENT_HELP[pos]) {
                set { it.copy(hookPosition = pos) }
            }
        }
    }
}

private val PLACEMENT_HELP = mapOf(
    HookPosition.AFTER_CLOCK to "Next to the clock",
    HookPosition.BEFORE_CLOCK to "Next to the clock",
    "${HookPosition.ICONS_PREFIX}0" to "Left of the icons on the right",
    HookPosition.SLOT_PREFIX to "Next to Wi-Fi and battery, drawn by Android. Works without the LSPosed module",
    "${HookPosition.SLOT_PREFIX}vpn" to "Between the system icons, right after this one",
    "${HookPosition.SLOT_PREFIX}bluetooth" to "Between the system icons, right after this one",
    "${HookPosition.SLOT_PREFIX}mobile" to "Between the system icons, right after this one",
    "${HookPosition.SLOT_PREFIX}wifi" to "Between the system icons, right after this one",
)
