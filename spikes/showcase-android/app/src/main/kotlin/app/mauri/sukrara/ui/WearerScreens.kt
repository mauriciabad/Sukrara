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
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.input.ImeAction
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
            Text("Nostr: ${if (!f.nostr) "off" else if (f.nostrIntervalMin == 0) "every reading" else "every ${f.nostrIntervalMin} min"} · " +
                "Bluetooth followers ${on(f.bleFollowers)}", style = MaterialTheme.typography.bodySmall)
            Text("Garmin: ${st.garmin}" + (if (st.watchPolledAt > 0) " · watch asked ${sinceText(st.watchPolledAt / 1000)}" else ""),
                style = MaterialTheme.typography.bodySmall)
            Text("LibreView: ${if (f.libreview) st.libreview else "off"}", style = MaterialTheme.typography.bodySmall)
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
internal fun Toggle(label: String, checked: Boolean, enabled: Boolean = true, sub: String? = null, onChange: (Boolean) -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else DISABLED_ALPHA, Motion.defaultEffectsSpec(), label = "enabled")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)) {
        Column(Modifier.weight(1f).graphicsLayer { this.alpha = alpha }) {
            Text(label)
            // the summary (status / "Unavailable while …") cross-fades when it changes
            Swap(sub) { it?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) } }
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A settings row that opens something (ListPreference-style): label + current value as summary. */
@Composable
internal fun ValueRow(label: String, value: String, enabled: Boolean, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else DISABLED_ALPHA, Motion.defaultEffectsSpec(), label = "enabled")
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 4.dp)
        .graphicsLayer { this.alpha = alpha }) {
        Text(label)
        Swap(value) { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
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
    Reveal(f.nightscout && f.nightscoutLan) {
        TextButton(onClick = { showSecret = !showSecret }) { Text(if (showSecret) "Hide API secret" else "Show API secret") }
        Reveal(showSecret) {
            Text("API secret: ${Features.nightscoutSecret(ctx)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** Glucose sources, in Settings' order (label, value, one-line description). Libre 3 directly is the default. */
private val SOURCES = listOf(
    Triple("Libre 3 directly", "direct", "Sukrara holds the sensor (scan it once with NFC)"),
    Triple("Juggluco", "juggluco", "Reads Juggluco's web server; Juggluco keeps the sensor and its alarms"),
)

/**
 * Settings, per the Android settings guidelines (developer.android.com/design/ui/mobile/guides/patterns/settings,
 * source.android.com/docs/core/settings/settings-guidelines): a choice among options is a row whose summary shows the
 * current value and opens radio buttons in a dialog; a parent switch disables (doesn't hide) what depends on it, with
 * a brief reason; longer explanations go in a footer below a divider.
 */
@Composable
fun SettingsScreen(onSensor: () -> Unit, onAlerts: () -> Unit, onBack: () -> Unit) {
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    var confirmDirect by remember { mutableStateOf(false) }
    var choose by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }

        SectionTitle("Alerts")
        val alerts by app.mauri.sukrara.alerts.Alerts.settings.collectAsState()
        AlertsRow(alerts, follower = false, onClick = onAlerts)

        SectionTitle("Glucose source")
        val direct = f.source == "direct"
        ValueRow("Source", SOURCES.first { it.second == f.source }.first + " · " + st.source + (st.sourceError?.let { " — $it" } ?: ""),
            enabled = true) { choose = "source" }
        ValueRow("Sensor", if (direct) st.sensorSerial?.let { "Libre 3 $it · scan to start or take over" } ?: "Scan to start or take over"
            else "Unavailable while Juggluco holds the sensor", enabled = direct, onClick = onSensor)
        ValueRow("Sensor link priority", if (direct) f.connPriority.replaceFirstChar { it.uppercase() }
            else "Unavailable while Juggluco holds the sensor", enabled = direct) { choose = "priority" }

        SectionTitle("Sharing")
        Toggle("Share over Nostr relays", f.nostr, sub = "End-to-end encrypted; followers anywhere") { v -> set { it.copy(nostr = v) } }
        ValueRow("Upload interval", if (f.nostr) intervalLabel(f.nostrIntervalMin) else "Unavailable while sharing over Nostr is off",
            enabled = f.nostr) { choose = "nostr" }
        Toggle("Bluetooth followers nearby", f.bleFollowers, sub = "Same encrypted events over Bluetooth LE (no internet needed)") { v ->
            set { it.copy(bleFollowers = v) }
        }

        SectionTitle("Status bar")
        StatusBarSettings(f, st)

        LocalServersSection()

        LibreViewSettings(f, st)
        Spacer(Modifier.size(24.dp))
    }
    when (choose) {
        "source" -> ChoiceDialog("Glucose source", SOURCES.map { Choice(it.first, it.second, it.third) }, f.source, { choose = null }) { v ->
            if (v == "direct" && f.source != "direct") confirmDirect = true else set { it.copy(source = v) }
        }
        "priority" -> ChoiceDialog("Sensor link priority", Features.PRIORITIES.map { Choice(it.replaceFirstChar { c -> c.uppercase() }, it) },
            f.connPriority, { choose = null }) { v -> set { it.copy(connPriority = v) } }
        "nostr" -> ChoiceDialog("Upload interval", listOf(0, 5, 15, 30).map { Choice(intervalLabel(it), it) }, f.nostrIntervalMin,
            { choose = null }) { v -> set { it.copy(nostrIntervalMin = v) } }
    }
    if (confirmDirect) AlertDialog(
        onDismissRequest = { confirmDirect = false },
        title = { Text("Read the sensor directly?") },
        text = { Text("Sukrara reads your Libre 3 itself: you scan the sensor once with NFC. Only one app can hold a " +
            "Libre 3, so stop Juggluco first (Pause app, then Force stop). While Sukrara holds the sensor, Juggluco's alarms " +
            "(low/high) do NOT ring.") },
        confirmButton = { Button(onClick = { confirmDirect = false; onSensor() }) { Text("Scan sensor") } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text("Cancel") } },
    )
}

private fun intervalLabel(min: Int) = if (min == 0) "Every reading" else "Every $min min"

@Composable
private fun StatusBarSettings(f: FeatureState, st: app.mauri.sukrara.wearer.WearerStatus) {
    val ctx = LocalContext.current
    Toggle("Live Update chip", f.chip, sub = "The number next to the clock (Android 16+)") { v -> set { it.copy(chip = v) } }
    val nm = ctx.getSystemService(NotificationManager::class.java)
    Reveal(f.chip && !nm.canPostPromotedNotifications()) {
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }) { Text("Allow Live Updates for Sukrara") }
    }
    Toggle("Number as notification icon", f.icon) { v -> set { it.copy(icon = v) } }
    Toggle("Pause refreshes while the screen is off", f.screenOffSkip,
        sub = "Saves a post per minute; catches up on screen on (${st.notificationPosts} posts, ${st.notificationSkipped} skipped)") { v ->
        set { it.copy(screenOffSkip = v) }
    }
    RootStatusBarSettings(f)
}

/**
 * LibreView (investigation 04), a parent switch with its dependents: the account row first (the switch needs a
 * login), then *Upload to LibreView*, then what goes up — every part on by default, so switching the upload on
 * uploads everything, like LibreLink — each disabled with the reason while the upload is off.
 */
@Composable
private fun LibreViewSettings(f: FeatureState, st: app.mauri.sukrara.wearer.WearerStatus) {
    val ctx = LocalContext.current
    remember { Treat.init(ctx) }
    val uploader = Treat.uploader
    val s by uploader.settings.collectAsState()
    val status by uploader.status.collectAsState()
    var signIn by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val signedIn = s.email.isNotEmpty() && s.hasPassword
    val on = f.libreview && signedIn
    val off = "Unavailable while the upload is off"
    val direct = f.source == "direct"

    SectionTitle("LibreView")
    ValueRow("Account", if (signedIn) s.email else "Not signed in", enabled = true) { signIn = true }
    Toggle("Upload to LibreView", on, enabled = signedIn, sub = when {
        !signedIn -> "Unavailable until you sign in"
        on -> status.replaceFirstChar { it.uppercase() }
        else -> "Off"
    }) { v -> set { it.copy(libreview = v) }; uploader.kick() }
    Toggle("Glucose readings", s.glucose, enabled = on && direct, sub = when {
        !on -> off
        !direct -> "Only while Sukrara reads the sensor itself"
        else -> uploader.glucoseFrom?.let { "From ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it * 1000))}" }
            ?: if (s.glucose) "From now on" else "Off"
    }) { uploader.configure(glucose = it) }
    Toggle("Insulin, carbs & notes", s.treatments, enabled = on, sub = if (on) "Yours and your followers'" else off) {
        uploader.configure(treatments = it)
    }
    Toggle("Use this phone as the account's device", s.takeover, enabled = on, sub = if (!on) off
        else if (s.takeover) "The Libre app or Juggluco stops uploading to this account" else "Uploads stop if another app holds the account") {
        uploader.configure(takeover = it)
    }
    ValueRow("Upload every", if (on) intervalLabel(f.libreviewIntervalMin) else off, enabled = on) { choosing = true }
    HorizontalDivider()
    Text("LibreView is the record your doctor sees: uploaded entries can't be deleted there. Glucose goes up from the " +
        "moment you switch it on.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    if (choosing) ChoiceDialog("Upload every", listOf(5, 15, 60).map { Choice(intervalLabel(it), it) }, f.libreviewIntervalMin,
        { choosing = false }) { v -> set { it.copy(libreviewIntervalMin = v) } }
    if (signIn) LibreViewSignIn(s, onClose = { signIn = false })
}

/** Sign in (or out) of LibreView. The password is only stored; the next upload (or the Sensor screen) uses it. */
@Composable
private fun LibreViewSignIn(s: app.mauri.sukrara.libreview.LibreViewUploader.Settings, onClose: () -> Unit) {
    val uploader = Treat.uploader
    var email by remember { mutableStateOf(s.email) }
    var password by remember { mutableStateOf("") }
    val signedIn = s.email.isNotEmpty() && s.hasPassword
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("LibreView account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The account your doctor's clinic sees. Sukrara also learns your sensor's receiver ID from it.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next))
                OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Password") }, placeholder = if (s.hasPassword) ({ Text("Saved · type to change") }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done))
            }
        },
        confirmButton = {
            Button(enabled = email.isNotBlank() && (password.isNotEmpty() || s.hasPassword), onClick = {
                uploader.configure(email = email, password = password.ifEmpty { null }); onClose()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (signedIn) TextButton(onClick = { set { it.copy(libreview = false) }; uploader.signOut(); onClose() }) { Text("Sign out") }
                TextButton(onClick = onClose) { Text("Cancel") }
            }
        },
    )
}

/** One option of a [ChoiceDialog]. */
data class Choice<T>(val label: String, val value: T, val sub: String? = null)

/** ListPreference: radio buttons in a dialog; choosing saves and closes, Cancel keeps the old value. */
@Composable
fun <T> ChoiceDialog(title: String, options: List<Choice<T>>, selected: T, onClose: () -> Unit, onChoose: (T) -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (o in options) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(selected = o.value == selected, role = Role.RadioButton, onClick = { onChoose(o.value); onClose() })
                        .padding(vertical = 6.dp)) {
                        RadioButton(selected = o.value == selected, onClick = null)
                        Spacer(Modifier.size(12.dp))
                        Column {
                            Text(o.label)
                            o.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

/**
 * The owner's treatments on the home screen: *Add treatment* opens the [TreatmentDialog]; below, the last 24 h
 * (also entries from followers and Macs), newest first. Tapping one offers to delete it.
 */
@Composable
fun TreatmentsSection() {
    val ctx = LocalContext.current
    remember { Treat.init(ctx) }
    val store = Treat.store
    val f by Features.state.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<Treatment?>(null) }
    val items by store.items.collectAsState()
    val since = System.currentTimeMillis() / 1000 - 24 * 3600
    val recent = items.filter { !it.del && it.t >= since }
    val pal = app.mauri.sukrara.treatments.TreatmentMarks.palette(androidx.compose.foundation.isSystemInDarkTheme())

    Row(verticalAlignment = Alignment.CenterVertically) {
        SectionTitle("Treatments (24 h)")
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = { adding = true }) { Text("Add treatment") }
    }
    Reveal(recent.isEmpty()) { Text("Nothing in the last 24 h. Tip: tap a Sukrara widget or the glucose notification to add one from anywhere.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
    // new entries (also from followers / the Mac) fade + expand in, deleted ones fade + shrink out
    AnimatedItems(recent, { it.id }) { tr ->
        Row(Modifier.fillMaxWidth().clickable { deleting = tr }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KindMark(tr.k, androidx.compose.ui.graphics.Color(app.mauri.sukrara.treatments.TreatmentMarks.color(pal, tr.k)))
            Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tr.t * 1000)), fontWeight = FontWeight.Medium)
            Text(describe(tr), Modifier.weight(1f), maxLines = 2)
            if (f.libreview) Text(if (tr.needsUpload) "LibreView ⇡" else "LibreView ✓", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline)
        }
    }

    if (adding) TreatmentDialog(onDismiss = { adding = false }, onSave = { QuickEntry.save(ctx, it) })
    deleting?.let { tr ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this entry?") },
            text = { Text(DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(tr.t * 1000)) + " · " + describe(tr) +
                if (f.libreview && !tr.needsUpload) "\n\nIt stays in LibreView: entries can't be deleted there." else "") },
            confirmButton = { Button(onClick = { store.delete(tr.id); Treat.uploader.kick(); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun describe(tr: Treatment): String = when (tr.k) {
    "carbs" -> "${tr.a.toInt()} g carbs"
    "note" -> tr.n.ifEmpty { "Note" }
    "long" -> "${tr.a} U long-acting"
    else -> "${tr.a} U rapid"
} + (if (tr.k != "note" && tr.n.isNotEmpty()) " · ${tr.n}" else "") + (if (tr.o != "phone") " · from ${tr.o}" else "")

/**
 * Root status bar (investigation 05), per the Android settings guidelines
 * (developer.android.com/design/ui/mobile/guides/patterns/settings, source.android.com/docs/core/settings/settings-guidelines):
 *  - dependent settings sit below the switch they depend on and are disabled (not hidden) while it's off, with a
 *    brief reason ("Dependency"; AndroidX Preference `dependency`);
 *  - summaries show the status, one line; the longer explanation goes in a footer below a divider;
 *  - a single choice among several options is radio buttons in a dialog (ListPreference), never in the list.
 */
@Composable
private fun RootStatusBarSettings(f: FeatureState) {
    val rootBar = app.mauri.sukrara.status.RootStatusBar
    val root by rootBar.root.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { rootBar.checkRoot() }
    val ok = root == true
    val on = ok && f.hook
    val placement = Features.HOOK_POSITIONS.firstOrNull { it.second == f.hookPosition }?.first ?: f.hookPosition
    var choosing by remember { mutableStateOf(false) }
    val off = "Unavailable while the status bar value is off"

    Toggle("Value in the status bar", f.hook && ok, enabled = ok, sub = when (root) {
        null -> "Checking root…"
        false -> "Unavailable: needs root for Sukrara (KernelSU)"
        true -> if (f.hook) "On · $placement" else "Off"
    }) { v -> set { it.copy(hook = v) } }
    Toggle("Colour by range", f.hookColor, enabled = on, sub = if (on) null else off) { v -> set { it.copy(hookColor = v) } }
    ValueRow("Placement", if (on) placement else off, enabled = on) { choosing = true }
    Toggle("Hide Sukrara's notification icon", f.hideNotifIcon, enabled = on,
        sub = if (on) "In the status bar only; the notification stays in the shade" else off) { v -> set { it.copy(hideNotifIcon = v) } }
    HorizontalDivider()
    Text("Uses root. Colour, hiding the notification icon and every placement except \"With the system icons\" also need the Sukrara module " +
        "enabled in LSPosed (scope System UI), then a System UI restart." + if (f.systemIcons) " Icons: ${rootBar.state}." else "",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)

    if (choosing) ChoiceDialog("Placement", Features.HOOK_POSITIONS.map { Choice(it.first, it.second) }, f.hookPosition,
        { choosing = false }) { pos -> set { it.copy(hookPosition = pos) } }
}
