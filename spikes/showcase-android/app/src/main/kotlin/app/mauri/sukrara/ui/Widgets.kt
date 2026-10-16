// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import android.graphics.Bitmap
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
import app.mauri.sukrara.core.RelayStats
import app.mauri.sukrara.treatments.TreatmentFeed
import app.mauri.sukrara.treatments.TreatmentMarks
import kotlinx.coroutines.delay

val InRange = Color(0xFF2E7D32)
val High = Color(0xFFEF6C00)
val Low = Color(0xFFC62828)

fun glucoseColor(sgv: Int, low: Int, high: Int) = when { sgv <= low -> Low; sgv >= high -> High; else -> InRange }

/** Seconds since epoch, refreshed every [everyMs] while composed. */
@Composable
fun nowSeconds(everyMs: Long = 10_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(everyMs) } }
    return now
}

fun ago(s: Long): String = when {
    s < 90 -> "${s.coerceAtLeast(0)} s ago"
    s < 5400 -> "${s / 60} min ago"
    else -> "${s / 3600} h ${(s % 3600) / 60} min ago"
}

/**
 * Trend arrow from the numeric rate, continuous angle (like GlucoseBar): 0 mg/dL/min → pointing right, ±3 → vertical.
 */
@Composable
fun TrendArrow(rate: Double?, color: Color, modifier: Modifier = Modifier.size(56.dp)) {
    if (rate == null) { Text("?", fontSize = 36.sp, color = color); return }
    val angle = -(rate.coerceIn(-3.0, 3.0) / 3.0 * 90.0).toFloat()
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

@Composable
fun BigGlucose(sgv: Int?, rate: Double?, t: Long?, low: Int, high: Int, subtitle: String? = null) {
    val now = nowSeconds()
    val color = sgv?.let { glucoseColor(it, low, high) } ?: MaterialTheme.colorScheme.outline
    val stale = t != null && now - t > 15 * 60
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(sgv?.toString() ?: "–", fontSize = 72.sp, fontWeight = FontWeight.Bold,
                    color = if (stale) MaterialTheme.colorScheme.outline else color)
                Column {
                    if (sgv != null) TrendArrow(rate, if (stale) MaterialTheme.colorScheme.outline else color)
                    Text("mg/dL", style = MaterialTheme.typography.labelLarge)
                }
            }
            val rateTxt = rate?.let { "%+.1f mg/dL/min".format(it) } ?: "trend unknown"
            Text(if (t != null) "${ago(now - t)} · $rateTxt" else "no reading yet",
                color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

/**
 * 12 h chart: dots coloured by range, dashed low/high lines, hour grid. [points] = (t, sgv). Treatments of the active
 * role ([TreatmentFeed]) along the bottom, same markers as the widgets ([TreatmentMarks]), notes with a short text.
 */
@Composable
fun GlucoseChart(points: List<Pair<Long, Int>>, low: Int, high: Int, hours: Int = 12) {
    val now = nowSeconds(60_000)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val background = MaterialTheme.colorScheme.background.toArgb()
    val dark = isSystemInDarkTheme()
    val treatments by TreatmentFeed.items.collectAsState()
    Column {
        Canvas(Modifier.fillMaxWidth().height(180.dp)) {
            val from = now - hours * 3600
            val yMin = 40f
            val yMax = maxOf(300f, (points.maxOfOrNull { it.second } ?: 0) + 10f)
            fun x(t: Long) = ((t - from).toFloat() / (hours * 3600)) * size.width
            fun y(v: Float) = size.height - (v - yMin) / (yMax - yMin) * size.height
            for (k in 0..hours) {
                val t = from - Math.floorMod(from, 3600L) + k * 3600
                if (t >= from) drawLine(grid, Offset(x(t), 0f), Offset(x(t), size.height), strokeWidth = 1f)
            }
            val dash = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
            drawLine(Low, Offset(0f, y(low.toFloat())), Offset(size.width, y(low.toFloat())), strokeWidth = 2f, pathEffect = dash)
            drawLine(High, Offset(0f, y(high.toFloat())), Offset(size.width, y(high.toFloat())), strokeWidth = 2f, pathEffect = dash)
            points.filter { it.first >= from }.forEach { (t, v) ->
                drawCircle(glucoseColor(v, low, high), radius = 3.5f, center = Offset(x(t), y(v.toFloat())))
            }
            drawIntoCanvas {
                TreatmentMarks.draw(it.nativeCanvas, treatments.filter { tr -> tr.t in from..now }, { t -> x(t) },
                    0f, size.width, size.height, density, dark, textDp = 10f, noteChars = 14, top = 0f, halo = background)
            }
            drawRect(grid, style = Stroke(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("-${hours} h", style = MaterialTheme.typography.labelSmall, color = label)
            Text("${points.count { it.first >= now - hours * 3600 }} readings", style = MaterialTheme.typography.labelSmall, color = label)
            Text("now", style = MaterialTheme.typography.labelSmall, color = label)
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
