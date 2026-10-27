// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucostatus

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Switchboard for the spike: every approach can be toggled independently. Note the Live Update chip is hidden
 * while this activity is on screen (SystemUI hides an app's chip when the app is visible) — go home to see it.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    @Composable
    private fun Screen() {
        var cfg by remember { mutableStateOf(Config.load(this)) }
        val st by Hub.state.collectAsState()
        fun set(new: Config) { cfg = new; new.save(this); if (st.source != "stopped") StatusService.start(this) }
        val nm = getSystemService(NotificationManager::class.java)

        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val r = st.reading
            Text(r?.let { "${it.mgdl} ${Trend.arrow(it.rate)}" } ?: "—", fontSize = 56.sp,
                color = r?.let { Range.color(it.mgdl) }?.let { Color(it) } ?: Color.Unspecified)
            Text(r?.let { "rate ${it.rate?.let { x -> "%+.2f".format(x) } ?: "?"} mg/dL/min · ${it.ageSec}s old" } ?: "", fontSize = 13.sp)
            Text("Source: ${st.source}", fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { StatusService.start(this@MainActivity) }) { Text("Start") }
                OutlinedButton(onClick = { StatusService.stop(this@MainActivity) }) { Text("Stop") }
            }
            Toggle("Demo data (sine wave, 5 s steps)", cfg.demo) { set(cfg.copy(demo = it)) }
            HorizontalDivider()

            Text("1 · Live Update chip", style = MaterialTheme.typography.titleMedium)
            Toggle("Promote to Live Update (chip)", cfg.chip) { set(cfg.copy(chip = it)) }
            Toggle("MetricStyle card (Android 17)", cfg.metricStyle) { set(cfg.copy(metricStyle = it)) }
            Toggle("Yield to other apps' Live Updates", cfg.yieldChip) { set(cfg.copy(yieldChip = it)) }
            Text("Allowed to promote: ${nm.canPostPromotedNotifications()} · others now: ${st.otherLiveUpdates.ifEmpty { listOf("none") }.joinToString()}", fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }) { Text("Live Update setting") }
                OutlinedButton(onClick = {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            ComponentName(this@MainActivity, ChipYieldListener::class.java).flattenToString()))
                }) { Text("Listener access") }
            }
            HorizontalDivider()

            Text("2 · Number as notification icon", style = MaterialTheme.typography.titleMedium)
            Toggle("Dynamic small icon", cfg.dynamicIcon) { set(cfg.copy(dynamicIcon = it)) }
            HorizontalDivider()

            Text("3a · System icons (root)", style = MaterialTheme.typography.titleMedium)
            Toggle("Number + arrow next to Wi-Fi/battery", cfg.slot) { set(cfg.copy(slot = it)) }
            Text(st.slot, fontSize = 13.sp)
            HorizontalDivider()

            Text("3b · SystemUI hook (LSPosed)", style = MaterialTheme.typography.titleMedium)
            Toggle("Show in status bar", cfg.hook) { set(cfg.copy(hook = it)) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, pos) in listOf("Before clock" to HookPosition.BEFORE_CLOCK, "After clock" to HookPosition.AFTER_CLOCK,
                    "Left of icons" to "${HookPosition.ICONS_PREFIX}0",
                    "3a icons after VPN" to "${HookPosition.SLOT_PREFIX}vpn", "after Bluetooth" to "${HookPosition.SLOT_PREFIX}bluetooth",
                    "after mobile" to "${HookPosition.SLOT_PREFIX}mobile", "after Wi-Fi" to "${HookPosition.SLOT_PREFIX}wifi")) {
                    FilterChip(selected = cfg.hookPosition == pos, onClick = { set(cfg.copy(hookPosition = pos)) }, label = { Text(label) })
                }
            }
            HorizontalDivider()
            st.notes.forEach { Text(it, fontSize = 12.sp) }
        }
    }

    @Composable
    private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f))
            Switch(checked = value, onCheckedChange = onChange)
        }
    }
}
