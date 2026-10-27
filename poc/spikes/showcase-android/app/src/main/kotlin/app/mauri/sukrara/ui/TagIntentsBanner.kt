// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Intent
import android.nfc.NfcAdapter
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.R
import app.mauri.sukrara.features.Features

/**
 * Android blocks Sukrara's tag intents while a tap feature is on (pen reading On, *Open Sukrara when you scan a sensor*):
 * every tap is muted until the user allows it. One home banner names what's blocked: tonal (not an error), *Allow*
 * (Android's tag-intent screen, a confirm haptic) and *Turn off* (turns the blocked features off: the honest exit when
 * the user doesn't want taps). It goes away by itself: checked again on every resume. Not shown in pen reading's *Ask*
 * state (no pen yet: the tap never reaches Sukrara, Android's own notification is the way in; the pen settings row
 * covers it).
 */
/** Whether Android lets taps reach Sukrara, checked again whenever the screen comes back (the user may have allowed it). */
@Composable
fun rememberTagIntentsAllowed(): Boolean {
    val ctx = LocalContext.current
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var allowed by remember { mutableStateOf(NfcLaunch.allowed(nfc)) }
    LifecycleResumeEffect(Unit) { allowed = NfcLaunch.allowed(nfc); onPauseOrDispose { } }
    return allowed
}

/** Android's own screen where the user allows Sukrara's tag intents (Android 16+). */
fun openTagIntentSettings(ctx: android.content.Context) { runCatching { ctx.startActivity(Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE)) } }

@Composable
fun TagIntentsBanner() {
    val ctx = LocalContext.current
    val f by Features.state.collectAsState()
    val allowed = rememberTagIntentsAllowed()
    var sensorOn by remember { mutableStateOf(NfcLaunch.isOn(ctx)) }
    LifecycleResumeEffect(Unit) { sensorOn = NfcLaunch.isOn(ctx); onPauseOrDispose { } }
    val hasNfc = remember { NfcAdapter.getDefaultAdapter(ctx) != null }
    val pensOn = f.penReading == "on" && app.mauri.sukrara.pen.Pens.available(ctx)
    val kind = if (!hasNfc && !NfcLaunch.fakeBlocked) null else NfcLaunch.blocked(allowed, pensOn, sensorOn && f.source == "direct")
    RevealValue(kind) { k ->
        TagBlockedCard(k) {
            if (k != NfcLaunch.Blocked.SENSOR) app.mauri.sukrara.pen.Pens.setReading(ctx, false)
            if (k != NfcLaunch.Blocked.PEN) { NfcLaunch.set(ctx, false); sensorOn = false }
        }
    }
}

/**
 * The blocked-taps card (home, the Insulin pens page): title and line name what's blocked, *Allow* opens Android's
 * screen (confirm haptic); [onTurnOff] adds *Turn off* (home), null on a settings page that has its own switch.
 */
@Composable
fun TagBlockedCard(k: NfcLaunch.Blocked, onTurnOff: (() -> Unit)?) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val title = stringResource(when (k) { NfcLaunch.Blocked.PEN -> R.string.pens_blocked; NfcLaunch.Blocked.SENSOR -> R.string.tags_blocked_sensor; else -> R.string.tags_blocked_both })
    val text = stringResource(when (k) { NfcLaunch.Blocked.PEN -> R.string.tags_blocked_pen_text; NfcLaunch.Blocked.SENSOR -> R.string.tags_blocked_sensor_text; else -> R.string.tags_blocked_both_text })
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                // needs-attention: taps silently do nothing until allowed, so the warning icon and title take the error
                // colour roles (on a tonal card, not an error container: nothing failed, the user has to act)
                Icon(painterResource(R.drawable.ic_sym_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // actions: pills, like "Eat carbs" (the visual language)
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onTurnOff?.let { FilledTonalButton(onClick = it) { Text(stringResource(R.string.tags_turn_off)) } }
                Button(onClick = { view.performHapticFeedback(HapticFeedbackConstants.CONFIRM); openTagIntentSettings(ctx) }) {
                    Text(stringResource(R.string.tags_allow))
                }
            }
        }
    }
}

/**
 * The settings row of a tap feature (the sensor page's *Open Sukrara when you scan a sensor*, the pens page's *Pen taps
 * are blocked*): normally a plain switch; while Android blocks the taps, a warning row (warning icon and summary in the
 * error colour, *Blocked · tap to allow*) that opens Android's allow screen. One component for both pages.
 */
fun SettingsGroupScope.tapFeatureRow(title: String, summary: String, on: Boolean, allowed: Boolean, onChange: (Boolean) -> Unit) {
    if (allowed) { switch(title, on, icon = R.drawable.ic_sym_nfc, summary = summary, onChange = onChange); return }
    row(title, null) {
        val ctx = LocalContext.current
        val blocked = stringResource(R.string.sensor_launch_on_scan_blocked)
        Row(Modifier.fillMaxWidth().clickable(onClickLabel = stringResource(R.string.tags_allow)) { openTagIntentSettings(ctx) }
            .padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_sym_warning), contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(blocked, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
