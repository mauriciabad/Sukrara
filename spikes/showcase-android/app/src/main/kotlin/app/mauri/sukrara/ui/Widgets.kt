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
import app.mauri.sukrara.alerts.Kind
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.scrollableArea
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.customActions

/** Seconds since epoch, refreshed every [everyMs] while composed. */
@Composable
fun nowSeconds(everyMs: Long = 10_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis() / 1000; delay(everyMs) } }
    // back in the foreground: the current time at once, not up to [everyMs] later (validation 21: a 3-min-old value)
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) { now = System.currentTimeMillis() / 1000; onPauseOrDispose { } }
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
    val scroll = remember { ChartScroll() }
    // History for the chart: from the role's store, again for every new reading / setting / 5 min.
    val recent by produceState(emptyList<Reading>(), t, hours, now / 300) {
        value = withContext(Dispatchers.IO) { GraphData.readings(ctx, Proto.nowS() - hours * 3600L - 60) }
    }
    // Everything older, once the chart is first scrolled (the store's whole history: 31 days on the wearer's phone).
    var older by remember { androidx.compose.runtime.mutableStateOf(emptyList<Reading>()) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { scroll.endS != null }.first { it }
        older = withContext(Dispatchers.IO) { GraphData.readings(ctx, 0) }
    }
    val history = remember(older, recent) {
        val start = recent.firstOrNull()?.t ?: Long.MAX_VALUE
        if (older.isEmpty()) recent else older.filter { it.t < start } + recent
    }
    // The slots' numbers, off the main thread (multi-day windows come from the database); the card reads them.
    val windows = (cfg.rings() + listOfNotNull(cfg.lastElseRing())).map { it.windowS }.distinct()
    val metrics by produceState(emptyMap<Long, GlucoseMetrics>(), t, cfg, now / 300) {
        value = withContext(Dispatchers.IO) { windows.associateWith { GraphData.metrics(ctx, it, cfg.bands) } }
    }
    // the ways out of a quiet alert (round f29): the pill's sheet, chips for what the pill doesn't show, TalkBack actions
    val active by Alerts.active.collectAsState()
    var sheet by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    PanelCard(reading, history, treatments, now, cfg, pal, alerts, paused, sensor, countdownH, scroll = scroll,
        metricsOf = { s, _ -> metrics[s.windowS] ?: GlucoseMetrics.of(emptyList(), 0, 0, cfg.bands) },
        onEnded = if (app.mauri.sukrara.App.role(ctx) == "owner") ({ ctx.startActivity(MainActivity.sensorIntent(ctx)) }) else null,
        onStatus = { sheet = it }, a11y = quietA11yActions(ctx, active)) {
        // what the pill doesn't show (it shows a level alert's mute): slowed reminders, a quiet fast-change alert
        val pillKind = (AlertStatus.of(reading, alerts, treatments, now, paused) as? AlertStatus.Paused)?.kind
            ?: (AlertStatus.of(reading, alerts, treatments, now, paused) as? AlertStatus.Taken)?.kind
        val chips = active.filter { a -> a.relaxMin > 0 || pillKind == null || Kind.group(a.kind) != Kind.group(pillKind) }
        if (chips.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            for (a in chips) androidx.compose.material3.AssistChip(onClick = { sheet = a.kind }, label = { Text(quietTitle(ctx, a, now)) },
                leadingIcon = { androidx.compose.material3.Icon(androidx.compose.ui.res.painterResource(
                    if (a.quiet != null) R.drawable.ic_sym_notifications_off else R.drawable.ic_sym_snooze), contentDescription = null,
                    modifier = Modifier.size(18.dp)) })
        }
        sheet?.let { k -> AlertQuietSheet(k, onDismiss = { sheet = null },
            onSettings = { ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("screen", "alerts")) }) }
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
              scroll: ChartScroll = remember { ChartScroll() },
              onStatus: ((String) -> Unit)? = null, a11y: List<androidx.compose.ui.semantics.CustomAccessibilityAction> = emptyList(),
              footer: @Composable () -> Unit = {}) {
    val ctx = LocalContext.current
    val card = CardStyle(graph = cfg.graphStyle(cfg.hours(GraphPlace.APP)),
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
    // a muted level alert's pill ("45m left", AlertStatus Taken / Paused) opens its ways out ([onStatus], AlertSheet.kt)
    val quietKind = when (val st = AlertStatus.of(readings.maxByOrNull { it.t }, alerts, treatments, now, paused)) {
        is AlertStatus.Taken -> st.kind
        is AlertStatus.Paused -> st.kind
        else -> null
    }
    val pill = remember { arrayOfNulls<android.graphics.RectF>(1) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Canvas(Modifier.fillMaxWidth().height(PANEL_HEADER_DP.dp).semantics {
                    contentDescription = desc
                    if (a11y.isNotEmpty()) customActions = a11y
                }
                .then(if (face.state == app.mauri.sukrara.graph.GlucoseLook.State.ENDED && onEnded != null) Modifier.clickable { onEnded() } else Modifier)
                .then(if (quietKind != null && onStatus != null) Modifier.pointerInput(quietKind) {
                    detectTapGestures { p -> if (pill[0]?.let { b -> android.graphics.RectF(b).apply { inset(-8 * density, -8 * density) }.contains(p.x, p.y) } == true) onStatus(quietKind) }
                } else Modifier)) {
                drawIntoCanvas {
                    val rec = GlucoseCard.draw(it.nativeCanvas, size.width, size.height, readings, treatments, now, card, pal, density,
                        CardSurface.APP_PANEL, CardPart.HEADER, metricsOf = metricsOf, alerts = alerts, sensor = sensor,
                        countdownH = countdownH, paused = paused, motion = motion)
                    pill[0] = rec.first("value.status")?.box
                }
            }
            if (fresh.showGraph) {
                Spacer(Modifier.height(8.dp))
                ScrollingChart(readings, treatments, now, card, pal, alerts, paused, sensor, countdownH, metricsOf, scroll)
            }
            footer()
        }
    }
}

/**
 * Where the panel's chart is scrolled to: the end of its window ([endS], epoch s; null = live, following now). Hoisted
 * so the panel keeps it across recompositions and the tests can open the panel scrolled.
 */
@androidx.compose.runtime.Stable
class ChartScroll(endS: Long? = null, spanS: Long? = null) {
    var endS by androidx.compose.runtime.mutableStateOf(endS)
        private set
    /** The window's length while zoomed (s; null = the place's "time shown"). */
    var spanS by androidx.compose.runtime.mutableStateOf(spanS)
        private set
    /** The exact end and length while dragging / pinching (sub-second steps add up). */
    private var exact: Double? = endS?.toDouble()
    private var exactSpan: Double? = spanS?.toDouble()

    /** Scrolled or zoomed: the chart doesn't show the place's usual window up to now (the Now pill shows). */
    val away: Boolean get() = endS != null || spanS != null

    /** The window's length now, [default] = the place's "time shown". */
    fun span(default: Long): Long = spanS ?: default

    /** Move the end by [dt] s, kept within [minEnd]..[now] (at [now] the chart follows now again); returns the seconds moved. */
    fun by(dt: Double, minEnd: Long, now: Long): Double {
        val cur = exact ?: now.toDouble()
        val next = (cur + dt).coerceIn(minOf(minEnd, now).toDouble(), now.toDouble())
        exact = if (next >= now) null else next
        endS = exact?.toLong()
        return next - cur
    }

    /**
     * Zoom the time axis by [factor] (> 1 = in) keeping the moment at [anchor] (0 = the plot's left edge, 1 = its right
     * edge: the pinch's centre) where it is. The length stays within [ChartView.MIN_SPAN_S] and [ChartView.MAX_SPAN_S]
     * or the history ([first] = the first reading), never narrower or wider than the place's own [default] needs; a
     * window that reaches now keeps following now.
     */
    fun zoom(factor: Double, anchor: Double, default: Long, first: Long, now: Long) {
        if (factor <= 0.0 || factor.isNaN()) return
        val lo = minOf(app.mauri.sukrara.graph.ChartView.MIN_SPAN_S, default).toDouble()
        val hi = maxOf(default, minOf(app.mauri.sukrara.graph.ChartView.MAX_SPAN_S, now - first)).toDouble()
        val span0 = exactSpan ?: default.toDouble()
        val span1 = (span0 / factor).coerceIn(lo, hi)
        val end0 = exact ?: now.toDouble()
        val a = anchor.coerceIn(0.0, 1.0)
        val at = end0 - span0 * (1 - a)
        val end1 = (at + span1 * (1 - a)).coerceIn(minOf(first + span1, now.toDouble()), now.toDouble())
        set(end1, span1, default, now)
    }

    /** Put the window at [end] with length [span] (the way back to now animates through this). */
    fun set(end: Double, span: Double, default: Long, now: Long) {
        exactSpan = if (kotlin.math.abs(span - default) < 30) null else span
        spanS = exactSpan?.toLong()
        exact = if (end >= now) null else end
        endS = exact?.toLong()
    }
}

/**
 * The panel's chart, scrollable through the stored history (the user, 2026-10-21): a horizontal drag or fling with the
 * platform's own scroll physics and overscroll ([scrollableArea], which the Compose guidance names for custom scroll
 * containers: developer.android.com/develop/ui/compose/touch-input/pointer-input/scroll; the default fling decay and
 * the system's stretch overscroll). The renderer draws the window it's given ([ChartView]), so the line, the
 * distribution and %, the hour labels and the treatments are all about what's visible; the y range animates towards
 * the window's own (no jumps). Away from now the renderer's "Now" pill shows; a tap on it scrolls back.
 *
 * Pinch zoom on the time axis (the user, 2026-10-21): two fingers zoom the x axis only, anchored at their centroid so
 * the moment under the fingers stays put, and move it with their centroid (like Maps / Photos), the Compose multi-touch
 * guidance's `calculateZoom` / `calculateCentroid` / `calculatePan`
 * (developer.android.com/develop/ui/compose/touch-input/pointer-input/multi-touch). Those events are consumed, so the
 * scroll's drag and fling don't also run; one finger is the scroll's. The zoom is the panel's own: it starts at the
 * place's "time shown" every time home is shown, and the Now pill also returns to it.
 */
@Composable
private fun ScrollingChart(readings: List<Reading>, treatments: List<app.mauri.sukrara.treatments.Treatment>, now: Long,
                           card: CardStyle, pal: GraphPalette, alerts: app.mauri.sukrara.alerts.AlertSettings,
                           paused: Map<String, Long>, sensor: app.mauri.sukrara.core.SensorInfo?, countdownH: Int,
                           metricsOf: (RingSpec, app.mauri.sukrara.graph.Bands) -> GlucoseMetrics, scroll: ChartScroll) {
    val base = card.graph ?: return
    val default = base.windowS
    val latestNow = androidx.compose.runtime.rememberUpdatedState(now)
    // the earliest window end: the first stored reading at the left edge
    val firstState = androidx.compose.runtime.rememberUpdatedState(readings.firstOrNull()?.t ?: now)
    // what the last drawing laid out: the plot's width (px per second of drag) and the Now pill's box (taps)
    val laid = remember { ChartGeometry() }
    val state = androidx.compose.foundation.gestures.rememberScrollableState { delta ->
        // scrollableArea's delta: positive = forward (towards now), in px of the plot
        val span = scroll.span(default)
        val spp = span / laid.plotW.coerceAtLeast(1f).toDouble()
        (scroll.by(delta * spp, firstState.value + span, latestNow.value) / spp).toFloat()
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val away by remember(scroll) { androidx.compose.runtime.derivedStateOf { scroll.away } }
    val pill = animateFloatAsState(if (away) 1f else 0f, Motion.defaultEffectsSpec(), label = "now pill")
    // The y range: the window's own, animated (critically damped at the spatial spring's stiffness: a scale that
    // bounced would read as the data moving).
    fun zoomed(): app.mauri.sukrara.graph.GraphStyle = base.copy(windowS = scroll.span(default))
    val y = remember { Animatable(GlucoseCard.yRange(readings, scroll.endS ?: now, zoomed()).let { Offset(it.first, it.second) }, Offset.VectorConverter) }
    val readingsState = androidx.compose.runtime.rememberUpdatedState(readings)
    LaunchedEffect(base, scroll) {
        androidx.compose.runtime.snapshotFlow {
            GlucoseCard.yRange(readingsState.value, scroll.endS ?: latestNow.value, zoomed()).let { Offset(it.first, it.second) }
        }.collectLatest { y.animateTo(it, androidx.compose.animation.core.spring(1f, 380f)) }
    }
    // The plot's bottom (px above the chart's bottom): the layout's, eased the same way, so the treatment lane that
    // comes and goes with the marks in the window doesn't make the plot jump.
    val inset = remember { Animatable(Float.NaN) }
    LaunchedEffect(scroll) {
        androidx.compose.runtime.snapshotFlow { laid.insetTarget }.collectLatest { t ->
            if (t.isNaN()) return@collectLatest
            if (inset.value.isNaN() || !scroll.away && !inset.isRunning) inset.snapTo(t)
            else inset.animateTo(t, androidx.compose.animation.core.spring(1f, 380f))
        }
    }
    val backToNow = stringResource(R.string.chart_back_to_now)
    // back to now and the place's own window: end and length together, with the spatial spring (inside the scroll
    // state's mutex, so a running fling stops)
    fun back() {
        if (!scroll.away) return
        val end0 = (scroll.endS ?: latestNow.value).toDouble()
        val span0 = scroll.span(default).toDouble()
        scope.launch {
            state.scroll {
                val p = Animatable(0f)
                p.animateTo(1f, androidx.compose.animation.core.spring(1f, 380f)) {
                    val n = latestNow.value
                    val f = value.toDouble()
                    scroll.set(end0 + (n - end0) * f, span0 + (default - span0) * f, default, n)
                }
                scroll.set(latestNow.value.toDouble(), default.toDouble(), default, latestNow.value)
            }
        }
    }
    Canvas(Modifier.fillMaxWidth().height(PANEL_CHART_DP.dp).testTag("panel-chart")
        .scrollableArea(state, androidx.compose.foundation.gestures.Orientation.Horizontal)
        // two fingers: zoom the time axis at their centroid and move it with them (one finger is the scroll's)
        .pointerInput(scroll) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                do {
                    val ev = awaitPointerEvent()
                    if (ev.changes.count { it.pressed } >= 2) {
                        val span = scroll.span(default)
                        val spp = span / laid.plotW.coerceAtLeast(1f).toDouble()
                        val c = ev.calculateCentroid(useCurrent = true)
                        val pan = ev.calculatePan()
                        if (pan.x != 0f) scroll.by(-pan.x * spp, firstState.value + span, latestNow.value)
                        val z = ev.calculateZoom()
                        if (z != 1f && c != Offset.Unspecified)
                            scroll.zoom(z.toDouble(), ((c.x - laid.plotLeft) / laid.plotW.coerceAtLeast(1f)).toDouble(), default,
                                firstState.value, latestNow.value)
                        ev.changes.forEach { it.consume() }
                    }
                } while (ev.changes.any { it.pressed })
            }
        }
        .pointerInput(scroll) {
            detectTapGestures { p ->
                if (scroll.away && laid.now?.contains(p.x, p.y) == true) back()
            }
        }
        .semantics {
            if (scroll.away) customActions = listOf(androidx.compose.ui.semantics.CustomAccessibilityAction(backToNow) {
                back(); true
            })
        }) {
        val end = scroll.endS
        val view = if (!scroll.away && pill.value == 0f && !y.isRunning && !inset.isRunning) null
            else app.mauri.sukrara.graph.ChartView(end ?: now, y.value.x to y.value.y, pill.value, inset.value.takeIf { !it.isNaN() },
                scroll.spanS)
        drawIntoCanvas {
            val rec = GlucoseCard.draw(it.nativeCanvas, size.width, size.height, readings, treatments, now, card, pal, density,
                CardSurface.APP_PANEL, CardPart.GRAPH, metricsOf = metricsOf, alerts = alerts, sensor = sensor,
                countdownH = countdownH, paused = paused, view = view)
            rec.first("chart.plot")?.box?.let { b -> laid.plotW = b.width(); laid.plotLeft = b.left }
            laid.now = rec.first("chart.now")?.box
            val area = rec.first("chart")?.box
            rec.first("chart.plot.natural")?.box?.let { b -> if (area != null) laid.insetTarget = area.bottom - b.bottom }
        }
    }
}

/** What the panel's chart last laid out (plain fields: written while drawing, read by the gestures). */
private class ChartGeometry {
    var plotW = 0f
    var plotLeft = 0f
    var now: RectF? = null
    /** Where the layout ends the plot (px above the chart's bottom): a state, so the easing follows it. */
    var insetTarget by androidx.compose.runtime.mutableFloatStateOf(Float.NaN)
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
