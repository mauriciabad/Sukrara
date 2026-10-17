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
    Kind.URGENT_LOW, Kind.LOW -> "≤ ${c.value.toInt()} mg/dL"
    Kind.HIGH, Kind.VERY_HIGH, Kind.EXTREMELY_HIGH -> "≥ ${c.value.toInt()} mg/dL"
    Kind.FALLING_FAST -> "faster than −${"%.1f".format(c.value)} mg/dL/min"
    Kind.RISING_FAST -> "faster than +${"%.1f".format(c.value)} mg/dL/min"
    else -> "after ${c.value.toInt()} min"
}

private fun repeatText(m: Int) = if (m == 0) "once" else "every $m min"

/** Home-screen / settings entry: "Alerts" + a one-line summary. */
@Composable
fun AlertsRow(s: AlertSettings, follower: Boolean, onClick: () -> Unit) {
    val on = Kind.ALL.filter { s.cfg(it).on }
    val summary = when {
        !s.on -> "Off"
        follower && s.source == "wearer" -> "Same as the wearer's app · ${AlertSounds.label(s.pack)}"
        else -> "${on.size} of ${Kind.ALL.size} on · low ${s.cfg(Kind.LOW).value.toInt()} · high ${s.cfg(Kind.HIGH).value.toInt()} · " +
            AlertSounds.label(s.pack)
    }
    ValueRow("Glucose alerts", summary, enabled = true, onClick = onClick)
}

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

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Alerts", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("Done") }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val now = state.values.toList()
                Swap(now.isEmpty()) { empty ->
                    if (empty) Text(if (s.on) "Nothing to alert about now" else "Alerts are off", style = MaterialTheme.typography.bodyMedium)
                    else Column {
                        now.forEach { st ->
                            Text(Kind.label(st.kind) + (st.muted?.let { " · quiet: $it" } ?: if (st.n > 0) " · alerted" else " · alerted recently"),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                history.take(4).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            }
        }

        Toggle("Glucose alerts", s.on, sub = if (s.on) "Notifications when glucose leaves your range" else "No glucose notifications") { v ->
            set { it.copy(on = v) }
        }
        if (follower) ValueRow("Alert rules", if (s.source == "wearer") "Same as the wearer's app" else "This phone's own settings",
            enabled = s.on) { pickSource = true }

        SectionTitle("Alerts")
        for (k in Kind.ALL) {
            val c = s.cfg(k)
            val ruled = !mirrored || k == Kind.NO_DATA
            val custom = nm.getNotificationChannel(AlertNotifier.channelId(k, c.alarm))?.sound?.let { it != AlertSounds.uri(ctx, k) } == true
            val sound = (if (custom) "sound set in system settings" else AlertSounds.label(s.soundOf(k)).substringBefore(" (")) +
                if (c.alarm) " · alarm volume" else ""
            KindRow(Kind.label(k), checked = c.on && ruled, enabled = s.on && ruled,
                sub = if (!ruled) "Set by the wearer's app · $sound" else "${thresholdText(k, c)} · ${repeatText(c.repeatMin)} · $sound",
                onClick = { editing = k }) { v -> set { it.copy(kinds = it.kinds + (k to c.copy(on = v))) } }
        }

        SectionTitle("Quiet highs")
        Toggle("After rapid insulin", s.muteInsulin, enabled = s.on && !mirrored,
            sub = if (mirrored) "Set by the wearer's app"
                  else "High, very high and rising fast wait ${s.muteInsulinMin} min after a rapid dose. Extremely high always alerts") { v ->
            set { it.copy(muteInsulin = v) }
        }
        Reveal(s.muteInsulin && !mirrored) {
            Minutes(s.muteInsulinMin, listOf(30, 45, 60, 90, 120, 180), enabled = s.on) { m -> set { it.copy(muteInsulinMin = m) } }
        }
        Toggle("When falling back into range", s.muteFalling, enabled = s.on && !mirrored,
            sub = if (mirrored) "Set by the wearer's app"
                  else "No high alert while the current fall brings it under ${s.cfg(Kind.HIGH).value.toInt()} within ${s.muteFallingMin} min") { v ->
            set { it.copy(muteFalling = v) }
        }
        Reveal(s.muteFalling && !mirrored) {
            Minutes(s.muteFallingMin, listOf(10, 15, 20, 30, 45, 60), enabled = s.on) { m -> set { it.copy(muteFallingMin = m) } }
        }

        SectionTitle("Quiet lows")
        Toggle("After carbs", s.muteCarbs, enabled = s.on && !mirrored,
            sub = if (mirrored) "Set by the wearer's app"
                  else "Low and falling fast wait ${s.muteCarbsMin} min after carbs. Urgent low always alerts") { v ->
            set { it.copy(muteCarbs = v) }
        }
        Reveal(s.muteCarbs && !mirrored) {
            Minutes(s.muteCarbsMin, listOf(10, 15, 20, 30, 45), enabled = s.on) { m -> set { it.copy(muteCarbsMin = m) } }
        }

        SectionTitle("Sounds")
        ValueRow("Sound pack", AlertSounds.label(s.pack), enabled = s.on) { pickPack = true }
        Toggle("A different sound each time", s.shuffle, enabled = s.on,
            sub = "Each alert has ${AlertSounds.VARIANTS} sounds in every pack, picked at random") { v -> set { it.copy(shuffle = v) } }
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }) { Text("Notification settings") }
        HorizontalDivider()
        Text("Each alert is its own notification category. Change its sound, vibration or Do Not Disturb behaviour in the " +
            "system settings; a sound picked there replaces Sukrara's. Each alert rings at alarm or notification volume " +
            "(lows at alarm volume unless you change it). Settings stay on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.size(24.dp))
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

/** A settings row with a switch on the right and its own tap target for the detail dialog (a "primary switch" row). */
@Composable
private fun KindRow(label: String, checked: Boolean, enabled: Boolean, sub: String, onClick: () -> Unit, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).clickable(enabled = true, onClick = onClick).padding(vertical = 4.dp)) {
            Text(label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
            Swap(sub) { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
        }
        Switch(checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun Minutes(value: Int, options: List<Int>, enabled: Boolean, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (m in options) FilterChip(value == m, { onPick(m) }, enabled = enabled, label = { Text("$m min") })
    }
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
                if (preview != null && o != AlertSounds.NONE) IconButton(onClick = { preview(o) }) { Text("▶") }
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
                        if (p != AlertSounds.NONE) IconButton(onClick = { AlertSounds.preview(ctx, k, p, alarm) }) { Text("▶") }
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
