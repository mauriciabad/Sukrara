// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.R
import app.mauri.sukrara.alerts.AlertNotifier
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertSounds
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.alerts.KindCfg

/*
 * Glucose alerts settings (investigation 15, spikes/alerts/SPEC.md). Layout per the Android settings guidelines
 * (developer.android.com/design/ui/mobile/guides/patterns/settings, source.android.com/docs/core/settings/
 * settings-guidelines): single-choice lists open in a dialog, dependent settings stay visible but disabled with a
 * short reason, a summary line shows each setting's current value, and footer text explains below a divider.
 */

private fun thresholdText(k: String, c: KindCfg): String = when (k) {
    Kind.VERY_LOW, Kind.LOW -> "< ${c.value.toInt()} mg/dL"
    Kind.HIGH, Kind.VERY_HIGH, Kind.EXTREMELY_HIGH -> "≥ ${c.value.toInt()} mg/dL"
    Kind.FALLING_FAST -> "faster than −${"%.1f".format(c.value)} mg/dL/min"
    Kind.RISING_FAST -> "faster than +${"%.1f".format(c.value)} mg/dL/min"
    else -> "after ${c.value.toInt()} min"
}

private fun repeatText(m: Int) = if (m == 0) "once" else "every $m min"

@Composable
fun AlertsScreen(follower: Boolean, onBack: () -> Unit) {
    val ctx = LocalContext.current
    remember { Alerts.init(ctx) }
    val s by Alerts.settings.collectAsState()
    val state by Alerts.state.collectAsState()
    val history by Alerts.history.collectAsState()
    var editing by remember { mutableStateOf<String?>(null) }
    var pickPack by remember { mutableStateOf(false) }
    var pickSource by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { AlertSounds.stopPreview() } }
    fun set(f: (AlertSettings) -> AlertSettings) = Alerts.update(ctx, f)
    val mirrored = follower && s.source == "wearer"
    val nm = ctx.getSystemService(NotificationManager::class.java)

    var minutes by remember { mutableStateOf<String?>(null) }   // which quiet rule's minutes dialog is open
    SettingsPage("Alerts", onBack) {
        // what the rules see now, like a live preview at the top of the page
        SettingsHeader { Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val now = state.values.toList()
                Swap(now.isEmpty()) { empty ->
                    if (empty) Text(if (s.on) "Nothing to alert about now" else "Alerts are off", style = MaterialTheme.typography.bodyLarge)
                    else Column {
                        now.forEach { st ->
                            Text(Kind.label(st.kind) + (st.muted?.let { " · quiet: $it" } ?: if (st.n > 0) " · alerted" else " · alerted recently"),
                                style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
                history.take(4).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        } }
        SettingsMainSwitch("Glucose alerts", s.on) { v -> set { it.copy(on = v) } }
        if (follower) SettingsGroup {
            value("Alert rules", if (s.source == "wearer") "Same as the wearer's app" else "This phone's own settings", enabled = s.on) { pickSource = true }
        }

        // each alert: tap opens its threshold, reminder, sound and volume; the switch turns it on or off
        @Composable
        fun kinds(title: String, ks: List<String>) = SettingsGroup(title) {
            for (k in ks) {
                val c = s.cfg(k)
                val ruled = !mirrored || k == Kind.NO_DATA
                val custom = nm.getNotificationChannel(AlertNotifier.channelId(k, c.alarm))?.sound?.let { it != AlertSounds.uri(ctx, k) } == true
                val sound = (if (custom) "sound set in system settings" else AlertSounds.label(s.soundOf(k)).substringBefore(" (")) +
                    if (c.alarm) " · alarm volume" else ""
                splitSwitch(Kind.label(k), if (!ruled) "Set by the wearer's app · $sound" else "${thresholdText(k, c)} · ${repeatText(c.repeatMin)} · $sound",
                    checked = c.on && ruled, rowEnabled = s.on, switchEnabled = s.on && ruled, onClick = { editing = k }) { v ->
                    set { it.copy(kinds = it.kinds + (k to c.copy(on = v))) }
                }
            }
        }
        kinds("Lows", listOf(Kind.VERY_LOW, Kind.LOW))
        kinds("Highs", listOf(Kind.HIGH, Kind.VERY_HIGH, Kind.EXTREMELY_HIGH))
        kinds("Fast changes", Kind.RATES)
        kinds("Signal", listOf(Kind.NO_DATA))

        val rules = s.on && !mirrored
        val locked = "Set by the wearer's app"
        SettingsGroup("Quiet alerts") {
            splitSwitch("After rapid insulin", if (mirrored) locked else "Highs and rising fast wait ${s.muteInsulinMin} min · extremely high always alerts",
                checked = s.muteInsulin, rowEnabled = rules && s.muteInsulin, switchEnabled = rules, onClick = { minutes = "insulin" }) { v ->
                set { it.copy(muteInsulin = v) }
            }
            splitSwitch("When falling back into range", if (mirrored) locked
                else "No high alert while it'll be under ${s.cfg(Kind.HIGH).value.toInt()} within ${s.muteFallingMin} min",
                checked = s.muteFalling, rowEnabled = rules && s.muteFalling, switchEnabled = rules, onClick = { minutes = "falling" }) { v ->
                set { it.copy(muteFalling = v) }
            }
            splitSwitch("After carbs", if (mirrored) locked else "Lows and falling fast wait ${s.muteCarbsMin} min",
                checked = s.muteCarbs, rowEnabled = rules && s.muteCarbs, switchEnabled = rules, onClick = { minutes = "carbs" }) { v ->
                set { it.copy(muteCarbs = v) }
            }
        }

        SettingsGroup("Sounds") {
            value("Sound pack", AlertSounds.label(s.pack), enabled = s.on, icon = R.drawable.ic_sym_volume_up) { pickPack = true }
            switch("A different sound each time", s.shuffle, enabled = s.on,
                summary = "${AlertSounds.VARIANTS} sounds per alert in every pack, picked at random") { v -> set { it.copy(shuffle = v) } }
            link("Notification settings", "Sound, vibration and Do Not Disturb per alert", icon = R.drawable.ic_sym_notifications, external = true) {
                ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
            }
        }
        SettingsFooter("Each alert is its own notification category: a sound picked in the system settings replaces " +
            "Sukrara's. Each alert rings at alarm or notification volume (lows at alarm volume unless you change it). " +
            "Settings stay on this phone.")
    }

    when (minutes) {
        "insulin" -> ChoiceDialog("After rapid insulin", listOf(30, 45, 60, 90, 120, 180).map { Choice("$it min", it) }, s.muteInsulinMin,
            { minutes = null }) { m -> set { it.copy(muteInsulinMin = m) } }
        "falling" -> ChoiceDialog("When falling back into range", listOf(10, 15, 20, 30, 45, 60).map { Choice("Within $it min", it) },
            s.muteFallingMin, { minutes = null }) { m -> set { it.copy(muteFallingMin = m) } }
        "carbs" -> ChoiceDialog("After carbs", listOf(10, 15, 20, 30, 45).map { Choice("$it min", it) }, s.muteCarbsMin,
            { minutes = null }) { m -> set { it.copy(muteCarbsMin = m) } }
    }
    editing?.let { k -> KindDialog(k, s, editable = !mirrored || k == Kind.NO_DATA, onDismiss = { editing = null }) { c, snd ->
        set { it.copy(kinds = it.kinds + (k to c), packFor = if (snd == null) it.packFor - k else it.packFor + (k to snd)) }
        editing = null
    } }
    if (pickPack) ChoiceDialog("Sound pack", AlertSounds.PACKS + AlertSounds.SYSTEM + AlertSounds.NONE, s.pack, AlertSounds::label,
        preview = { p -> AlertSounds.preview(ctx, Kind.HIGH, p, s.cfg(Kind.HIGH).alarm) }, onDismiss = { pickPack = false; AlertSounds.stopPreview() }) { p ->
        set { it.copy(pack = p) }
    }
    if (pickSource) ChoiceDialog("Alert rules", listOf("wearer", "own"), s.source,
        { if (it == "wearer") "Same as the wearer's app" else "This phone's own settings" },
        sub = { if (it == "wearer") "Alerts when the wearer's phone alerts, including its quiet rules. No data is checked here."
                else "The rules below run on this phone with the readings and entries it receives." },
        onDismiss = { pickSource = false }) { v -> set { it.copy(source = v) } }
}

/** Radio list in a dialog (settings guidelines: single choice → dialog). Optional ▶ per option. */
@Composable
private fun ChoiceDialog(title: String, options: List<String>, selected: String, label: (String) -> String,
                         sub: ((String) -> String)? = null, preview: ((String) -> Unit)? = null,
                         onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.selectableGroup()) {
            for (o in options) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                .selectable(o == selected, role = Role.RadioButton) { onPick(o); if (preview == null) onDismiss() }) {
                RadioButton(o == selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = 8.dp, top = 6.dp, bottom = 6.dp)) {
                    Text(label(o))
                    sub?.let { Text(it(o), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
                }
                if (preview != null && o != AlertSounds.NONE) IconButton(onClick = { preview(o) }) { Icon(painterResource(R.drawable.ic_sym_play_arrow), contentDescription = "Play") }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}

/** One kind: threshold, re-alert interval, sound (pack default or one pack for just this alert), test. */
@Composable
private fun KindDialog(k: String, s: AlertSettings, editable: Boolean, onDismiss: () -> Unit, onSave: (KindCfg, String?) -> Unit) {
    val ctx = LocalContext.current
    val c = s.cfg(k)
    var value by remember { mutableStateOf(if (k in Kind.RATES) "%.1f".format(c.value) else c.value.toInt().toString()) }
    var repeat by remember { mutableStateOf(c.repeatMin) }
    var sound by remember { mutableStateOf(s.packFor[k]) }   // null = the pack
    var alarm by remember { mutableStateOf(c.alarm) }
    val parsed = value.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && when (k) {
        in Kind.RATES -> parsed in 0.5..10.0
        Kind.NO_DATA -> parsed in 5.0..240.0
        else -> parsed in 40.0..400.0
    }
    AlertDialog(onDismissRequest = { AlertSounds.stopPreview(); onDismiss() }, title = { Text(Kind.label(k)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!editable) Text("Threshold and reminders come from the wearer's app. Sound and volume are this phone's.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value, { value = it.filter { ch -> ch.isDigit() || ch == '.' || ch == ',' } }, enabled = editable,
                label = { Text(when (k) { in Kind.RATES -> "Rate (mg/dL/min)"; Kind.NO_DATA -> "After (minutes)"; else -> "Threshold (mg/dL)" }) },
                singleLine = true, isError = !valid, modifier = Modifier.width(200.dp),
                keyboardOptions = KeyboardOptions(keyboardType = if (k in Kind.RATES) KeyboardType.Decimal else KeyboardType.Number))
            Text("Remind", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in listOf(0, 5, 10, 15, 30, 60, 120)) FilterChip(repeat == m, { repeat = m }, enabled = editable,
                    label = { Text(if (m == 0) "Once" else "$m min") })
            }
            Text("Sound", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                for (o in listOf<String?>(null) + AlertSounds.PACKS + AlertSounds.SYSTEM + AlertSounds.NONE) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(sound == o, role = Role.RadioButton) { sound = o }) {
                        RadioButton(sound == o, onClick = null)
                        Text(if (o == null) "Pack (${AlertSounds.label(s.pack)})" else AlertSounds.label(o),
                            Modifier.weight(1f).padding(start = 8.dp))
                        val p = o ?: s.pack
                        if (p != AlertSounds.NONE) IconButton(onClick = { AlertSounds.preview(ctx, k, p, alarm) }) { Icon(painterResource(R.drawable.ic_sym_play_arrow), contentDescription = "Play") }
                    }
                }
            }
            Text("Volume", style = MaterialTheme.typography.labelLarge)
            Column(Modifier.selectableGroup()) {
                for ((o, title, sub) in listOf(
                    Triple(true, "Alarm", "Alarm volume, like an alarm clock. Rings in Do Not Disturb when alarms are allowed"),
                    Triple(false, "Notification", "Notification volume. Rings in Do Not Disturb only if reminders are allowed"))) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()
                        .selectable(alarm == o, role = Role.RadioButton) { alarm = o }) {
                        RadioButton(alarm == o, onClick = null)
                        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
                            Text(title)
                            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Alerts.test(ctx, k, share = false) }) { Text("Test") }
                OutlinedButton(onClick = {
                    ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, AlertNotifier.channelId(k, alarm)))
                }) { Text("Vibration & more") }
            }
        }
    }, confirmButton = {
        TextButton(enabled = valid || !editable, onClick = {
            AlertSounds.stopPreview()
            onSave((if (editable && parsed != null) c.copy(value = parsed, repeatMin = repeat) else c).copy(alarm = alarm), sound)
        }) { Text("Save") }
    }, dismissButton = { TextButton(onClick = { AlertSounds.stopPreview(); onDismiss() }) { Text("Cancel") } })
}
