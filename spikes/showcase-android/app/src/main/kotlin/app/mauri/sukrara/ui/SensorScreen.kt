// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import app.mauri.sukrara.core.SensorLife
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.App
import app.mauri.sukrara.direct.Libre3Nfc
import app.mauri.sukrara.direct.ReceiverId
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings → Glucose source → Sensor: the paired sensor (serial, start, end, what the source is doing), the way to
 * pair a new one (the New sensor screen, ui/NewSensorScreen.kt, does the scan and the pairing), and the NFC options.
 */
@Composable
fun SensorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    val life by SensorLife.current.collectAsState()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var pairing by remember { mutableStateOf(runCatching { SensorPairing.load(ctx) }.getOrNull()) }
    var launchOnScan by remember { mutableStateOf(NfcLaunch.isOn(ctx)) }
    var tagIntents by remember { mutableStateOf(NfcLaunch.allowed(nfc)) }
    LifecycleResumeEffect(Unit) {
        pairing = runCatching { SensorPairing.load(ctx) }.getOrNull()
        tagIntents = NfcLaunch.allowed(nfc)
        onPauseOrDispose { }
    }
    val nav = LocalNav.current

    SettingsPage(stringResource(R.string.sensor_title), onBack) {
        SettingsHeader { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val p = pairing
                if (p == null) {
                    Text(stringResource(R.string.sensor_none), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.sensor_none_text), style = MaterialTheme.typography.bodyMedium)
                } else {
                    // the family's own name for the other brands (investigation 23); the Libre 3's line as before
                    val family = app.mauri.sukrara.core.SensorProfiles.byId(p.profileId)
                    val name = if (family == null || family.id == app.mauri.sukrara.core.SensorProfiles.LIBRE_3.id) "Libre 3" else family.name
                    Text(listOf(name, p.serial).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.titleMedium)
                    if (p.startEpoch > 0) Text(stringResource(R.string.sensor_started, date(p.startEpoch)), style = MaterialTheme.typography.bodyMedium)
                    life?.takeIf { it.serial == p.serial }?.let {
                        Text(stringResource(R.string.sensor_ends, date(it.endEpoch)), style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(if (f.source != "direct") stringResource(R.string.sensor_not_in_use)
                         else stringResource(R.string.sensor_now, st.source.removePrefix("direct: ") + (st.sourceError?.let { " — $it" } ?: "")),
                        style = MaterialTheme.typography.bodySmall)
                    if (f.source != "direct") Button(onClick = { Features.update { it.copy(source = "direct") } }) {
                        Text(stringResource(R.string.sensor_use))
                    }
                }
            }
        }
        Button(onClick = { nav?.open("newsensor") }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(painterResource(R.drawable.ic_sym_nfc), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
            Text(stringResource(if (pairing == null) R.string.sensor_pair else R.string.sensor_pair_new), style = MaterialTheme.typography.titleMedium)
        }
        } }

        val launchTitle = stringResource(R.string.sensor_launch_on_scan)
        val launchSummary = stringResource(if (tagIntents) R.string.sensor_launch_on_scan_summary else R.string.sensor_launch_on_scan_blocked)
        val forget = stringResource(R.string.sensor_forget)
        SettingsGroup {
            if (nfc != null) switch(launchTitle, launchOnScan && tagIntents, icon = R.drawable.ic_sym_nfc, summary = launchSummary) {
                if (!tagIntents) ctx.startActivity(Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE))
                else { NfcLaunch.set(ctx, it); launchOnScan = it }
            }
            if (pairing != null) action(forget, icon = R.drawable.ic_sym_person_remove) {
                SensorPairing.forget(ctx)
                pairing = null
                if (f.source == "direct") scope.launch { App.owner(ctx).restartSource() }
            }
        }
    }
}

private fun date(t: Long) = app.mauri.sukrara.graph.Clock.dayTime(t)
