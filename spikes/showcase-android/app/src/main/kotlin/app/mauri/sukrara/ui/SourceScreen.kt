// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.content.Intent
import android.nfc.NfcAdapter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.mauri.sukrara.App
import app.mauri.sukrara.R
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.SensorProfile
import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.wearer.Live
import kotlinx.coroutines.launch

/**
 * Settings → Glucose source, one level for everything about where readings come from (0.11.28; before, a "Source"
 * choice of "Libre 3 directly" or Juggluco plus a nested "Sensor" page). Laid out like AOSP's device-details page
 * (`res/xml/bluetooth_device_details_fragment.xml`: a header `LayoutPreference` with the device, its
 * `ActionButtonsPreference`, then the device's preference categories):
 *
 * - **Header**: the current sensor (family, red *Untested* tag when unverified, serial, started / ends, what the source
 *   is doing) and *Pair new sensor*, which opens the New sensor screen (every family is chosen there).
 * - **Read glucose from**: an inline single choice (SettingsLib SPA `RadioPreferences`, two options stay visible
 *   instead of hiding behind a list dialog): *This phone* (Sukrara reads the sensor itself: any supported family, the
 *   active one named) or *Juggluco* (it keeps the sensor; Sukrara reads its web server).
 * - **Sensor** (this phone only): open Sukrara when a sensor is scanned (NFC), forget this sensor.
 *
 * Settings search indexes the rows from the page itself (SettingsSearch.kt); the header's *Pair new sensor* is
 * recorded by hand, like SettingsMainSwitch does.
 */
@Composable
fun SourceSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val f by Features.state.collectAsState()
    val st by Live.status.collectAsState()
    val life by SensorLife.current.collectAsState()
    val nfc = remember { NfcAdapter.getDefaultAdapter(ctx) }
    var pairing by remember { mutableStateOf(runCatching { SensorPairing.load(ctx) }.getOrNull()) }
    var launchOnScan by remember { mutableStateOf(NfcLaunch.isOn(ctx)) }
    var tagIntents by remember { mutableStateOf(NfcLaunch.allowed(nfc)) }
    var confirmDirect by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        pairing = runCatching { SensorPairing.load(ctx) }.getOrNull()
        tagIntents = NfcLaunch.allowed(nfc)
        onPauseOrDispose { }
    }
    val nav = LocalNav.current
    val direct = f.source == "direct"
    val family = pairing?.let { SensorProfiles.byId(it.profileId) }
    val status = UserErrors.source(ctx, st.source, st.sourceError) ?: st.source.removePrefix("direct: ")
    val pairTitle = stringResource(if (pairing == null) R.string.sensor_pair else R.string.sensor_pair_new)

    SettingsPage(stringResource(R.string.source_title), onBack) {
        SettingsIntro(stringResource(R.string.source_intro))
        // search: the header's action is a row too (it isn't drawn as one)
        LocalSettingsIndex.current?.let { it.rows[null to pairTitle] = IndexedRow(pairTitle, null, null, null) }
        SettingsHeader { Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val p = pairing
                    when {
                        !direct -> {
                            Text(stringResource(R.string.source_juggluco), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.sensor_now, st.source), style = MaterialTheme.typography.bodySmall)
                        }
                        p == null -> {
                            Text(stringResource(R.string.sensor_none), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.sensor_none_text), style = MaterialTheme.typography.bodyMedium)
                        }
                        else -> {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(familyName(family), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                                if (family?.verified == false) UntestedTag()
                            }
                            if (p.serial.isNotEmpty()) Text(p.serial, style = MaterialTheme.typography.bodyMedium)
                            if (p.startEpoch > 0) Text(stringResource(R.string.sensor_started, date(p.startEpoch)), style = MaterialTheme.typography.bodyMedium)
                            life?.takeIf { it.serial == p.serial && p.startEpoch > 0 }?.let {
                                Text(stringResource(R.string.sensor_ends, date(it.endEpoch)), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(stringResource(R.string.sensor_now, status), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Button(onClick = { if (direct) nav?.open("newsensor") else confirmDirect = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(painterResource(R.drawable.ic_sym_nfc), contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(20.dp))
                Text(pairTitle, style = MaterialTheme.typography.titleMedium)
            }
        } }

        SettingsGroup(stringResource(R.string.source_group)) {
            radio(stringResource(R.string.source_direct),
                if (direct && family != null) stringResource(R.string.source_direct_now, familyName(family)) else stringResource(R.string.source_direct_summary),
                selected = direct, leading = { SettingsIconFor(R.drawable.ic_sym_smartphone) }) {
                if (!direct) confirmDirect = true
            }
            radio(stringResource(R.string.source_juggluco), stringResource(R.string.source_juggluco_summary), selected = !direct,
                leading = { ServiceIcon(Service.JUGGLUCO) }) {
                if (direct) Features.update { it.copy(source = "juggluco") }
            }
        }

        if (direct) {
            val launchSummary = stringResource(if (tagIntents) R.string.sensor_launch_on_scan_summary else R.string.sensor_launch_on_scan_blocked)
            val forget = stringResource(R.string.sensor_forget)
            SettingsGroup(stringResource(R.string.sensor_title)) {
                if (nfc != null) switch(stringResource(R.string.sensor_launch_on_scan), launchOnScan && tagIntents, icon = R.drawable.ic_sym_nfc, summary = launchSummary) {
                    if (!tagIntents) ctx.startActivity(Intent(NfcAdapter.ACTION_CHANGE_TAG_INTENT_PREFERENCE))
                    else { NfcLaunch.set(ctx, it); launchOnScan = it }
                }
                if (pairing != null) action(forget, icon = R.drawable.ic_sym_link_off) {
                    SensorPairing.forget(ctx)
                    pairing = null
                    scope.launch { App.owner(ctx).restartSource() }
                }
            }
        }
        SettingsFooter(stringResource(R.string.source_footer))
    }
    if (confirmDirect) AlertDialog(
        onDismissRequest = { confirmDirect = false },
        title = { Text(stringResource(R.string.source_direct_confirm_title)) },
        text = { Text(stringResource(R.string.source_direct_confirm_text)) },
        confirmButton = { Button(onClick = { confirmDirect = false; nav?.open("newsensor") }) { Text(stringResource(R.string.sensor_pair)) } },
        dismissButton = { TextButton(onClick = { confirmDirect = false }) { Text(stringResource(R.string.settings_dialog_cancel)) } },
    )
}

/** What the source is, for the Settings overview: "This phone · Dexcom G7 / ONE+ · live" / "Juggluco · …". */
@Composable
fun sourceSummary(source: String, status: String): String {
    val ctx = LocalContext.current
    val family = remember(source, status) { runCatching { SensorPairing.load(ctx) }.getOrNull()?.let { SensorProfiles.byId(it.profileId) } }
    return if (source == "direct") listOfNotNull(stringResource(R.string.source_direct), family?.let { familyName(it) }, status).joinToString(" · ")
    else stringResource(R.string.source_juggluco) + " · " + status
}

/** The family as Settings names it (its profile name); a pairing without one is the Libre 3. */
private fun familyName(p: SensorProfile?): String = p?.name ?: SensorProfiles.LIBRE_3.name

@Composable
private fun SettingsIconFor(icon: Int) = Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

private fun date(t: Long) = app.mauri.sukrara.graph.Clock.dayTime(t)
