// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.SizeF
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.lifecycleScope
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphData
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.TirRange
import app.mauri.sukrara.graph.WINDOW_HOURS
import app.mauri.sukrara.graph.WidgetGraphConfig
import app.mauri.sukrara.graph.systemPalette
import app.mauri.sukrara.ui.Chips
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.produceState
import kotlin.math.min

/**
 * One widget placement's own settings (investigation 16), so the same widget can be added twice and look different.
 * Declared with `widgetFeatures="reconfigurable|configuration_optional"`: a new widget follows the app's settings
 * without asking, and touch & hold → Reconfigure opens this. Per developer.android.com/develop/ui/compose/glance/
 * configuration: start with RESULT_CANCELED, save, update the Glance widget, then RESULT_OK with the widget id.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        GraphSettings.init(this)
        val mgr = AppWidgetManager.getInstance(this)
        val kind = when (mgr.getAppWidgetInfo(id)?.provider?.className) {
            ValueWidgetReceiver::class.java.name -> WidgetKind.VALUE
            GraphWidgetReceiver::class.java.name -> WidgetKind.GRAPH
            else -> WidgetKind.COMBINED
        }
        // The size the launcher reports for this placement (portrait entry), for the preview.
        val size = mgr.getAppWidgetOptions(id).getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            ?.minByOrNull { it.width / it.height } ?: SizeF(300f, 160f)

        enableEdgeToEdge()
        setContent {
            val ctx = LocalContext.current
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var w by remember { mutableStateOf(GraphSettings.widget(ctx, id)) }
                    val app = GraphSettings.value
                    Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("This widget", style = MaterialTheme.typography.headlineSmall)
                        Preview(kind, w, size)
                        Text("Graph window", style = MaterialTheme.typography.titleMedium)
                        if (kind != WidgetKind.VALUE)
                            Chips(listOf(0) + WINDOW_HOURS, w.hours ?: 0, { if (it == 0) "App setting (${app.hours(GraphPlace.WIDGETS)} h)" else "$it h" }) {
                                w = w.copy(hours = it.takeIf { h -> h != 0 })
                            }
                        if (kind != WidgetKind.GRAPH) {
                            Text("Time in range rings", style = MaterialTheme.typography.titleMedium)
                            Chips(listOf(-1, 0, 1, 2, 3), w.rings ?: -1, { if (it == -1) "App setting (${app.ringCount})" else if (it == 0) "None" else "$it" }) {
                                w = w.copy(rings = it.takeIf { n -> n >= 0 })
                            }
                            Chips(listOf<TirRange?>(null) + TirRange.entries, w.ringRange, { it?.label ?: "App setting (${app.ringRange.label})" }) {
                                w = w.copy(ringRange = it)
                            }
                        }
                        if (kind != WidgetKind.VALUE) Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Time in range beside the graph")
                                Text("The distribution and % on the left", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline)
                            }
                            Switch(w.side ?: app.side, { w = w.copy(side = it) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { w = WidgetGraphConfig() }) { Text("Use app settings") }
                            Button(onClick = { save(id, w) }) { Text("Save") }
                        }
                    }
                }
            }
        }
    }

    private fun save(id: Int, w: WidgetGraphConfig) {
        GraphSettings.setWidget(this, id, w)
        lifecycleScope.launch {
            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(id)
            WidgetUpdater.refreshSafely(this@WidgetConfigActivity, "widget configured", force = true)
            WidgetUpdater.widgets.forEach { (widget, _) -> runCatching { widget.update(this@WidgetConfigActivity, glanceId) } }
            setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
            finish()
        }
    }
}

@androidx.compose.runtime.Composable
private fun Preview(kind: WidgetKind, w: WidgetGraphConfig, size: SizeF) {
    val ctx = LocalContext.current
    val d = ctx.resources.displayMetrics.density
    val bw = size.width - 20; val bh = size.height - 20
    // Off the main thread: the history and the multi-day rings come from the database.
    val bmp by produceState<android.graphics.Bitmap?>(null, w) {
        value = withContext(Dispatchers.Default) {
            val frame = WidgetUpdater.current(ctx)
            val scale = min(1f, 1400f / (maxOf(bw, bh) * d))
            createBitmap((bw * d * scale).toInt().coerceAtLeast(16), (bh * d * scale).toInt().coerceAtLeast(16)).also {
                GlucoseCard.draw(android.graphics.Canvas(it), it.width.toFloat(), it.height.toFloat(), frame.history,
                    frame.treatments, frame.nowSec, cardStyle(ctx, kind, w), systemPalette(ctx), d * scale,
                    metricsOf = { r, b -> GraphData.metrics(ctx, r.windowS, b, frame.nowSec) })
            }
        }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(10.dp)
            .size(bw.dp, bh.dp)) {
            bmp?.let { Image(it.asImageBitmap(), contentDescription = "Preview", Modifier.size(bw.dp, bh.dp)) }
        }
    }
}
