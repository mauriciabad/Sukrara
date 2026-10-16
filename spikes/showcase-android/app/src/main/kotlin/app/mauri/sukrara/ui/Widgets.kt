// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseGraph
import app.mauri.sukrara.graph.GraphData
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.RingSpec
import app.mauri.sukrara.graph.TimeInRange
import app.mauri.sukrara.graph.TirRing
import app.mauri.sukrara.graph.systemPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.mauri.sukrara.treatments.TreatmentFeed
import app.mauri.sukrara.treatments.TreatmentMarks
import kotlinx.coroutines.delay

/** Seconds since epoch, refreshed every [everyMs] while composed. */
@Composable
fun nowSeconds(everyMs: Long = 10_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(everyMs) } }
    return now
}

/**
 * Trend arrow from the numeric rate, continuous angle (like GlucoseBar): 0 mg/dL/min → pointing right, ±3 → vertical.
 */
@Composable
fun TrendArrow(rate: Double?, color: Color, modifier: Modifier = Modifier.size(56.dp)) {
    if (rate == null) { Text("?", fontSize = 36.sp, color = color); return }
    // turns to each new trend with the expressive spatial spring (a hint of overshoot), once per reading
    val angle by animateFloatAsState(-(rate.coerceIn(-3.0, 3.0) / 3.0 * 90.0).toFloat(),
        Motion.defaultSpatialSpec(), label = "trend")
    Canvas(modifier) {
        rotate(angle) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.11f
            drawLine(color, Offset(w * 0.12f, h / 2), Offset(w * 0.78f, h / 2), strokeWidth = stroke)
            val head = Path().apply {
                moveTo(w * 0.92f, h / 2)
                lineTo(w * 0.58f, h * 0.22f)
                lineTo(w * 0.58f, h * 0.78f)
                close()
            }
            drawPath(head, color)
        }
    }
}

/**
 * The home screen's glucose panel (investigation 16), the same picture as the widgets and the notification: value +
 * trend arrow, the time-in-range rings, and the chart with the time-in-range side profile. The value follows
 * [Freshness]: no age while fresh, the reading's time once late, struck through when stale, gone when old; no chart
 * at all when very old. The number rolls and the arrow turns like before (Motion.kt); everything else is drawn by
 * [GlucoseGraph] / [TirRing].
 */
@Composable
fun GlucosePanel(sgv: Int?, rate: Double?, t: Long?, subtitle: String? = null) {
    val ctx = LocalContext.current
    val now = nowSeconds()
    val cfg by GraphSettings.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val pal = remember(dark) { systemPalette(ctx, dark) }
    val treatments by TreatmentFeed.items.collectAsState()
    val fresh = Freshness.of(t?.let { now - it })
    val hours = cfg.hours(GraphPlace.APP)
    // History for the chart and the rings: from the role's store, again for every new reading / setting / 5 min.
    val history by produceState(emptyList<Reading>(), t, hours, now / 300) {
        value = withContext(Dispatchers.IO) { GraphData.readings(ctx, Proto.nowS() - hours * 3600L - 60) }
    }
    val rings by produceState(emptyList<Pair<RingSpec, TimeInRange>>(), t, cfg, now / 300) {
        value = withContext(Dispatchers.IO) {
            cfg.rings().map { it to GraphData.tir(ctx, it.windowS, cfg.bands) }.filter { it.second.coverage >= GlucoseCard.MIN_COVERAGE }
        }
    }
    val color by animateColorAsState(
        when {
            sgv == null || fresh.strike -> MaterialTheme.colorScheme.outline
            else -> Color(pal.of(cfg.bands.of(sgv)))
        }, Motion.slowEffectsSpec(), label = "range")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sgv != null && fresh.showValue) {
                    Box {
                        RollingNumber("$sgv", color, 72.sp, FontWeight.Bold)
                        if (fresh.strike) Canvas(Modifier.matchParentSize()) {
                            drawLine(color, Offset(0f, size.height * 0.55f), Offset(size.width, size.height * 0.55f), strokeWidth = 4.dp.toPx())
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (!fresh.strike) TrendArrow(rate, color)
                    if (fresh.showTime && t != null) Text(Freshness.timeText(t, now), Modifier.padding(start = 8.dp),
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(if (t == null) "No readings yet" else "No reading since\n${Freshness.timeText(t, now)}",
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                for (idx in rings.indices.reversed()) {   // main ring rightmost
                    val (s2, t2) = rings[idx]
                    Canvas(Modifier.padding(start = 8.dp).size(68.dp)) {
                        drawIntoCanvas {
                            TirRing.draw(it.nativeCanvas, size.width / 2, size.height / 2, size.minDimension / 2, t2, s2, pal,
                                floatArrayOf(1f, 0.55f, 0.35f)[idx], density)
                        }
                    }
                }
            }
            if (fresh.showGraph) {
                Spacer(Modifier.height(8.dp))
                val st = cfg.graphStyle(hours, android.text.format.DateFormat.is24HourFormat(ctx))
                Canvas(Modifier.fillMaxWidth().height(210.dp)) {
                    drawIntoCanvas {
                        GlucoseGraph.draw(it.nativeCanvas, RectF(0f, 0f, size.width, size.height), history,
                            treatments.filter { tr -> tr.t >= now - hours * 3600L }, now, st, pal, density)
                    }
                }
            }
            subtitle?.let { Text(it, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
fun RelayList(stats: Map<String, RelayStats>) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp)
    val now = System.currentTimeMillis()
    stats.values.sortedBy { it.url }.forEach { r ->
        val paused = if (r.pausedUntilMs > now) " PAUSED" else ""
        Text("${if (r.connected) "●" else "○"} ${r.host}  ok ${r.ok} fail ${r.fail} rx ${r.rx} q ${r.queued}" +
            "${r.lastLiveOkMs?.let { " live ${it}ms" } ?: ""}$paused", style = mono)
        if (!r.connected) r.lastError?.let { Text("   ${it.take(70)}", style = mono, color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun LogList(lines: List<String>, max: Int = 25) {
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    lines.take(max).forEach { Text(it, style = mono) }
}

@Composable
fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))

fun qrBitmap(text: String, px: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, px, px, mapOf(EncodeHintType.MARGIN to 2))
    val pixels = IntArray(px * px) { i -> if (m[i % px, i / px]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
}
