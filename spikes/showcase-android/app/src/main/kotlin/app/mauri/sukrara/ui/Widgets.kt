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
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.CardSurface
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
import app.mauri.sukrara.graph.SlotItem
import app.mauri.sukrara.graph.GraphPalette
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.mauri.sukrara.graph.StatusBadge
import app.mauri.sukrara.treatments.LastTreatment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import app.mauri.sukrara.graph.CardMotion
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material3.Surface
import kotlinx.coroutines.delay

/** Seconds since epoch, refreshed every [everyMs] while composed. */
@Composable
fun nowSeconds(everyMs: Long = 10_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(everyMs) } }
    return now
}

/** The app panel's header height (dp): its slots come out 73 dp, two of them beside the value on a 296–380 dp phone. */
const val PANEL_HEADER_DP = 76f
/** The app panel's chart height (dp). */
const val PANEL_CHART_DP = 210f

/**
 * The home screen's glucose panel (investigation 16): ONE card (the widgets', the notification's, the Mac's) drawn by
 * [GlucoseCard] in two parts with one whole-card style ([CardPart.HEADER]: value, arrow, status badge, sensor badge,
 * slots; [CardPart.GRAPH]: the chart), so nothing here lays the card out. Only the motion is the panel's own, and it
 * is paint only ([CardMotion]): the number rolls, the arrow turns, the colour fades (Motion.kt's specs).
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
    val paused by Alerts.paused.collectAsState()
    val sensor by app.mauri.sukrara.core.SensorLife.current.collectAsState()
    val countdownH by app.mauri.sukrara.core.SensorLife.countdown.collectAsState()
    val reading = if (sgv != null && t != null) Reading(t, sgv, rate) else null
    val hours = cfg.hours(GraphPlace.APP)
    // History for the chart: from the role's store, again for every new reading / setting / 5 min.
    val history by produceState(emptyList<Reading>(), t, hours, now / 300) {
        value = withContext(Dispatchers.IO) { GraphData.readings(ctx, Proto.nowS() - hours * 3600L - 60) }
    }
    // The slots' numbers, off the main thread (multi-day windows come from the database); the card reads them.
    val windows = (cfg.rings() + listOfNotNull(cfg.lastElseRing())).map { it.windowS }.distinct()
    val metrics by produceState(emptyMap<Long, GlucoseMetrics>(), t, cfg, now / 300) {
        value = withContext(Dispatchers.IO) { windows.associateWith { GraphData.metrics(ctx, it, cfg.bands) } }
    }
    PanelCard(reading, history, treatments, now, cfg, pal, alerts, paused, sensor, countdownH,
        metricsOf = { s, _ -> metrics[s.windowS] ?: GlucoseMetrics.of(emptyList(), 0, 0, cfg.bands) },
        onEnded = if (app.mauri.sukrara.App.role(ctx) == "owner") ({ ctx.startActivity(MainActivity.sensorIntent(ctx)) }) else null) {
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

/**
 * The panel's card (its one drawing entry, also the tests'): the header and the chart, both [GlucoseCard] parts of
 * the style built from the App place's settings. [onEnded] = what a tap does while the sensor has ended (the
 * "Replace sensor" pill's action, like the widgets' tap); null = nothing.
 */
@Composable
fun PanelCard(reading: Reading?, history: List<Reading>, treatments: List<app.mauri.sukrara.treatments.Treatment>, now: Long,
              cfg: app.mauri.sukrara.graph.GraphConfig, pal: GraphPalette, alerts: app.mauri.sukrara.alerts.AlertSettings,
              paused: Map<String, Long>, sensor: app.mauri.sukrara.core.SensorInfo?, countdownH: Int,
              metricsOf: (RingSpec, app.mauri.sukrara.graph.Bands) -> GlucoseMetrics, onEnded: (() -> Unit)?,
              footer: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val card = CardStyle(graph = cfg.graphStyle(cfg.hours(GraphPlace.APP), android.text.format.DateFormat.is24HourFormat(ctx)),
        rings = cfg.rings(), last = cfg.lastPlace, lastElse = cfg.lastElseRing(), sensorTime = cfg.sensorTime(GraphPlace.APP))
    // the newest reading is the panel's own (it may be newer than the stored history)
    val readings = if (reading != null && (history.lastOrNull()?.t ?: 0) < reading.t) history + reading else history
    val look = app.mauri.sukrara.graph.SensorBadge.look(sensor, now, reading?.t)
    val face = GlucoseCard.face(reading, now, look, CardSurface.APP_PANEL)
    val fresh = face.fresh
    // Motion (paint only): the number rolls from the previous one, the arrow turns, the colour fades.
    val number = face.text.takeIf { face.shown }
    val roll = remember { Animatable(1f) }
    var from by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var shown by remember { androidx.compose.runtime.mutableStateOf(number) }
    var up by remember { androidx.compose.runtime.mutableStateOf(true) }
    LaunchedEffect(number) {
        val last = shown
        shown = number
        if (number != null && last != null && number != last) {
            up = (number.toIntOrNull() ?: 0) > (last.toIntOrNull() ?: 0)
            from = last
            roll.snapTo(0f)
            roll.animateTo(1f, Motion.defaultSpatialSpec())
        }
    }
    val angle by animateFloatAsState(reading?.rate?.let { app.mauri.sukrara.core.Trend.angleDeg(it) } ?: 0f,
        Motion.defaultSpatialSpec(), label = "trend")
    val target = when {
        reading == null || face.strike -> pal.inkSoft
        else -> pal.at(cfg.bands, reading.sgv)
    }
    val color by animateColorAsState(Color(target), Motion.slowEffectsSpec(), label = "range")
    val motion = CardMotion(from, roll.value, up, angle, color.toArgb())
    val desc = app.mauri.sukrara.widgets.describeCard(ctx, reading, now, treatments)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Canvas(Modifier.fillMaxWidth().height(PANEL_HEADER_DP.dp).semantics { contentDescription = desc }
                .then(if (face.state == app.mauri.sukrara.graph.GlucoseLook.State.ENDED && onEnded != null) Modifier.clickable { onEnded() } else Modifier)) {
                drawIntoCanvas {
                    GlucoseCard.draw(it.nativeCanvas, size.width, size.height, readings, treatments, now, card, pal, density,
                        CardSurface.APP_PANEL, CardPart.HEADER, metricsOf = metricsOf, alerts = alerts, sensor = sensor,
                        countdownH = countdownH, paused = paused, motion = motion)
                }
            }
            if (fresh.showGraph) {
                Spacer(Modifier.height(8.dp))
                Canvas(Modifier.fillMaxWidth().height(PANEL_CHART_DP.dp)) {
                    drawIntoCanvas {
                        GlucoseCard.draw(it.nativeCanvas, size.width, size.height, readings, treatments, now, card, pal, density,
                            CardSurface.APP_PANEL, CardPart.GRAPH, metricsOf = metricsOf, alerts = alerts, sensor = sensor,
                            countdownH = countdownH, paused = paused)
                    }
                }
            }
            footer()
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
