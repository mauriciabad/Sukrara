// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucobridge

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.spike.glucobridge.theme.GlucoBridgeTheme
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {
    private val permissions = buildList {
        add(Manifest.permission.BLUETOOTH_ADVERTISE)
        add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BLUETOOTH_ADVERTISE] == true && granted[Manifest.permission.BLUETOOTH_CONNECT] == true) {
            startForegroundService(Intent(this, BridgeService::class.java))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        request.launch(permissions)  // starts the service once granted
        setContent {
            GlucoBridgeTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s by Bridge.status.collectAsState()
                    Column(Modifier.safeDrawingPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("GlucoBridge", style = MaterialTheme.typography.headlineMedium)
                        Text(s.latest?.let { "${it.mgdl} mg/dL  ${it.rate?.let { r -> "%+.2f mg/dL/min".format(r) } ?: ""}" } ?: "—",
                             style = MaterialTheme.typography.displaySmall)
                        s.latest?.let { Text("at " + DateFormat.getTimeInstance().format(Date(it.epochSec * 1000))) }
                        if (s.history.size > 1) {
                            GlucoseChart(s.history)
                            val span = (s.history.last().epochSec - s.history.first().epochSec) / 60
                            Text("${s.history.size} readings over ${span / 60}h ${span % 60}m")
                        }
                        Text("Source: ${s.source}" + (s.sourceDetail?.let { " — $it" } ?: ""))
                        Text("BLE advertising: ${if (s.advertising) "on" else "off"} · Mac subscribed: ${s.subscribed}")
                        s.error?.let { Text("Error: $it", color = MaterialTheme.colorScheme.error) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { request.launch(permissions) }) { Text("Start") }
                            OutlinedButton(onClick = { stopService(Intent(this@MainActivity, BridgeService::class.java)) }) { Text("Stop") }
                        }
                    }
                }
            }
        }
    }
}
