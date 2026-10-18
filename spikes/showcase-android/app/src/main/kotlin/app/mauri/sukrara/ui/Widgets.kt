// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.ui

import androidx.compose.ui.res.stringResource
import app.mauri.sukrara.R
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
import app.mauri.sukrara.graph.GlucoseMetrics
import app.mauri.sukrara.graph.TirRing
import app.mauri.sukrara.graph.systemPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.mauri.sukrara.treatments.TreatmentFeed
import app.mauri.sukrara.treatments.TreatmentMarks
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.graph.LastPlace
import app.mauri.sukrara.graph.Slots
import app.mauri.sukrara.graph.StatusBadge
import app.mauri.sukrara.treatments.LastTreatment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
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
 * trend arrow, the status badge under it ([StatusBadgeChip]), the slots (time-in-range rings, report numbers, the last
 * treatment: [Slots]), and the chart with the time-in-range side profile. The value follows
 * [Freshness]: no age while fresh, the reading's time once late, struck through when stale, gone when old; no chart
 * at all when very old. The number rolls and the arrow turns like before (Motion.kt); everything else is drawn by
 * [GlucoseGraph] / [TirRing].
 */
@Composable
fun GlucosePanel(sgv: Int?, rate: Double?, t: Long?, subtitle: String? = null, onAdd: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val now = nowSeconds()
    val cfg by GraphSettings.state.collectAsState()
    val dark = isSystemInDarkTheme()
    val pal = remember(dark) { systemPalette(ctx, dark) }
    val treatments by TreatmentFeed.items.collectAsState()
    val alerts by Alerts.settings.collectAsState()
    val fresh = Freshness.of(t?.let { now - it })
    val reading = if (sgv != null && t != null) Reading(t, sgv, rate) else null
    val status = AlertStatus.of(reading, alerts, treatments, now)
    val last = if (cfg.lastPlace != LastPlace.OFF) LastTreatment.of(treatments, alerts, status, now) else null
    val hours = cfg.hours(GraphPlace.APP)
    // History for the chart and the rings: from the role's store, again for every new reading / setting / 5 min.
    val history by produceState(emptyList<Reading>(), t, hours, now / 300) {
        value = withContext(Dispatchers.IO) { GraphData.readings(ctx, Proto.nowS() - hours * 3600L - 60) }
    }
    // the user's slots (index, spec, metrics) without the ones lacking data, and the last-treatment slot's fallback
    val rings by produceState(emptyList<Triple<Int, RingSpec, GlucoseMetrics>>(), t, cfg, now / 300) {
        value = withContext(Dispatchers.IO) {
            cfg.rings().mapIndexed { i, s -> Triple(i, s, GraphData.metrics(ctx, s.windowS, cfg.bands)) }
                .filter { it.third.tir.coverage >= GlucoseCard.MIN_COVERAGE }
        }
    }
    val fallback by produceState<Pair<RingSpec, GlucoseMetrics>?>(null, t, cfg, now / 300) {
        value = withContext(Dispatchers.IO) {
            cfg.lastElseRing()?.let { s -> GraphData.metrics(ctx, s.windowS, cfg.bands).takeIf { it.tir.coverage >= GlucoseCard.MIN_COVERAGE }?.let { s to it } }
        }
    }
    val color by animateColorAsState(
        when {
            sgv == null || fresh.strike -> MaterialTheme.colorScheme.outline
            else -> Color(pal.at(cfg.bands, sgv))
        }, Motion.slowEffectsSpec(), label = "range")
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            // as many slots as fit beside a three-digit value + arrow (~188 dp), 76 dp each
            BoxWithConstraints {
            val fit = ((maxWidth.value - 188f) / 76f).toInt().coerceIn(1, 3)
            val slots = Slots.arrange(rings, cfg.lastPlace, last, fallback, fit)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (sgv != null && fresh.showValue) {
                    // A late reading's time goes under the number, so it never takes the rings' room.
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box {
                                // the rounded face of the card's numbers (graph/RoundedFont.kt), like SF Pro Rounded on the Mac
                                val rounded = remember { androidx.compose.ui.text.font.FontFamily(app.mauri.sukrara.graph.RoundedFont.typeface(700)) }
                                RollingNumber("$sgv", color, 72.sp, FontWeight.Bold, rounded)
                                if (fresh.strike) Canvas(Modifier.matchParentSize()) {
                                    drawLine(color, Offset(0f, size.height * 0.55f), Offset(size.width, size.height * 0.55f), strokeWidth = 4.dp.toPx())
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            if (!fresh.strike) TrendArrow(rate, color)
                        }
                        RevealValue(StatusBadge.look(status, now)) { StatusBadgeChip(it) }
                        if (fresh.showTime && t != null) Text(Freshness.timeText(t, now),
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(if (t == null) stringResource(R.string.card_no_readings_yet) else stringResource(R.string.card_no_reading_since) + "\n" + Freshness.timeText(t, now),
                        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                for (item in slots.reversed()) {   // main ring rightmost
                    Canvas(Modifier.padding(start = 8.dp).size(68.dp)) {
                        drawIntoCanvas {
                            Slots.draw(it.nativeCanvas, item, size.width / 2, size.height / 2, size.minDimension / 2, now, pal, density)
                        }
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
            onAdd?.let {
                androidx.compose.material3.FilledTonalButton(onClick = it, modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.mauri.sukrara.R.drawable.ic_sym_add),
                        contentDescription = null, modifier = Modifier.padding(end = 8.dp).size(18.dp))
                    Text(stringResource(R.string.treatment_title))
                }
            }
        }
    }
}

/**
 * The status badge under the value ([StatusBadge] draws the same one into the widgets): a filled pill to act ("Eat
 * carbs", "Take insulin"), a tonal one while a treatment mutes the alerts (paused bell + time left). Not clickable: a
 * status, so a plain Surface with the chip shape rather than an AssistChip (developer.android.com/develop/ui/compose/components/chip:
 * an assist chip "guides the user during a task").
 */
@Composable
fun StatusBadgeChip(look: StatusBadge.Look) {
    val dark = isSystemInDarkTheme()
    val c by animateColorAsState(Color(TreatmentMarks.color(TreatmentMarks.palette(dark), look.kind)), Motion.slowEffectsSpec(), label = "badge")
    val bg = if (look.tonal) c.copy(alpha = if (dark) 0.24f else 0.18f) else c
    val fg = if (look.tonal) c else Color(TreatmentMarks.onColor(dark))
    Surface(shape = CircleShape, color = bg, modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)) {
        Row(Modifier.padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (look.tonal) androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(app.mauri.sukrara.R.drawable.ic_sym_notifications_paused),
                contentDescription = stringResource(R.string.badge_alerts_paused), tint = fg, modifier = Modifier.size(18.dp))
            else KindMark(look.kind, fg)
            Spacer(Modifier.width(6.dp))
            Text(look.text, style = MaterialTheme.typography.labelLarge, color = fg)
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
