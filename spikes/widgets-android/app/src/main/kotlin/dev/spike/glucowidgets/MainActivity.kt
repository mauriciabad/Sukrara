// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.glucowidgets

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        // adb: `am start … --ez restart true` restarts the source after a DebugReceiver config change.
        LiveService.tryStart(this, "activity", restart = intent.getBooleanExtra("restart", false))
        RefreshWorker.schedule(this)
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize()) { Screen() }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("restart", false)) LiveService.tryStart(this, "activity", restart = true)
    }
}

@Composable
private fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val history by GlucoseStore.history.collectAsState()
    val status by LiveService.status.collectAsState()
    val running by LiveService.running.collectAsState()
    val stats by WidgetUpdater.stats.collectAsState()
    var source by remember { mutableStateOf(Config.load(ctx).source) }
    var note by remember { mutableStateOf("") }

    fun pin(receiver: Class<out GlanceAppWidgetReceiver>) = scope.launch {
        val ok = GlanceAppWidgetManager(ctx).requestPinGlanceAppWidget(receiver)
        note = if (ok) "Confirm the dialog to add the widget" else "This launcher can't pin widgets"
    }

    Column(
        Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Glucose Widgets", style = MaterialTheme.typography.headlineMedium)
        val r = history.lastOrNull()
        Text(
            if (r == null) "No data yet" else "${r.mgdl} mg/dL ${Trend.word(r.rate)} · ${ageText(r.ageSec(nowSec()))}",
            style = MaterialTheme.typography.titleLarge,
        )
        Text("${history.size} readings stored · service ${if (running) "running" else "stopped"} · $status",
            style = MaterialTheme.typography.bodyMedium)
        Text("Widget renders ${stats.renders} (last: ${stats.lastReason}, ${stats.lastRenderMs} ms) · skipped " +
            "${stats.skippedSame} unchanged, ${stats.skippedScreenOff} screen off",
            style = MaterialTheme.typography.bodySmall)

        Text("Source", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((id, label) in listOf(Config.SOURCE_JUGGLUCO to "Juggluco (127.0.0.1)",
                Config.SOURCE_SYNTHETIC to "Synthetic (debug)")) {
                FilterChip(selected = source == id, label = { Text(label) }, onClick = {
                    source = id
                    Config.load(ctx).copy(source = id, freezeAgeMin = 0).save(ctx)
                    LiveService.tryStart(ctx, "source change", restart = true)
                })
            }
        }

        Text("Add a widget", style = MaterialTheme.typography.titleMedium)
        Button(onClick = { pin(ValueWidgetReceiver::class.java) }, Modifier.fillMaxWidth()) { Text("Pin value widget") }
        Button(onClick = { pin(GraphWidgetReceiver::class.java) }, Modifier.fillMaxWidth()) { Text("Pin graph widget") }
        Button(onClick = { pin(CombinedWidgetReceiver::class.java) }, Modifier.fillMaxWidth()) {
            Text("Pin graph + value widget")
        }
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)

        Text("Debug", style = MaterialTheme.typography.titleMedium)
        FilledTonalButton(onClick = { scope.launch { note = WidgetUpdater.publishPreviews(ctx) } },
            Modifier.fillMaxWidth()) { Text("Publish widget-picker previews") }
        FilledTonalButton(onClick = { scope.launch { WidgetUpdater.refresh(ctx, "button", force = true) } },
            Modifier.fillMaxWidth()) { Text("Refresh widgets now") }
        FilledTonalButton(onClick = {
            if (running) LiveService.stop(ctx) else LiveService.tryStart(ctx, "button")
        }, Modifier.fillMaxWidth()) { Text(if (running) "Stop live service" else "Start live service") }
    }
}
