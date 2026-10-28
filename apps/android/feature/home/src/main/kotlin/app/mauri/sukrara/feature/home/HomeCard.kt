// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import android.content.res.Resources
import android.graphics.RectF
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.designsystem.motion.LocalReducedMotion
import app.mauri.sukrara.designsystem.motion.Motion
import app.mauri.sukrara.graph.CardLayout
import app.mauri.sukrara.graph.CardModel
import app.mauri.sukrara.graph.CardMotion
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.graph.ChartView
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.SensorBadge
import app.mauri.sukrara.graph.SensorInfo
import app.mauri.sukrara.graph.SensorLife
import app.mauri.sukrara.graph.TrendAngle
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.MetricsCache
import app.mauri.sukrara.graph.stats.toPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import kotlin.math.roundToInt

/*
 * The home screen's card: the registered `app-panel` surface (contracts/graph/surfaces/surfaces.json). It only chooses
 * its size and style; the renderer (`GlucoseCard`) lays out and draws the value, arrow, badge, slots and chart.
 */

/** The renderer's decision about the latest value on the home card (what the panel, the reason row and TalkBack use). */
fun homeLook(
    state: GlucoseState,
    now: Instant,
): GlucoseLook {
    // the same sensor dates and warm-up rule the drawn card uses (a reading since the sensor's start ends the warm-up)
    val info = state.sensor.info(now)
    val warming = info != null && SensorLife.warming(info, state.latest?.t?.epochSecond, now.epochSecond)
    val sensor =
        when (val s = state.sensor) {
            SensorState.Ended -> {
                SensorBadge.Look(label = "", life = 0f, ended = true)
            }

            is SensorState.WarmingUp -> {
                if (warming) SensorBadge.Look(label = "", life = 1f, ended = false, warmup = "", readyAt = s.readyAt.epochSecond) else null
            }

            else -> {
                null
            }
        }
    return GlucoseCard.face(state.latest?.toPoint(), now.epochSecond, sensor, CardSurface.APP_PANEL)
}

/**
 * The sensor's dates as the card needs them; null when nothing is known (the card then shows no sensor badge). An ended
 * sensor without dates ends at [now], so the renderer draws "Sensor ended" + "Replace sensor" in the value's place.
 */
private fun SensorState.info(now: Instant): SensorInfo? =
    when (this) {
        SensorState.Ended -> {
            SensorInfo(startS = now.epochSecond - SENSOR_LIFE.seconds, endS = now.epochSecond)
        }

        is SensorState.Active -> {
            endsAt?.let { SensorInfo(startS = it.epochSecond - SENSOR_LIFE.seconds, endS = it.epochSecond) }
        }

        is SensorState.WarmingUp -> {
            val start = readyAt.epochSecond - WARMUP.seconds
            SensorInfo(startS = start, endS = start + SENSOR_LIFE.seconds, warmupEndS = readyAt.epochSecond)
        }

        else -> {
            null
        }
    }

private val SENSOR_LIFE = Duration.ofDays(15)
private val WARMUP = Duration.ofHours(1)

/**
 * The card for [glucose] and the 31 days of [history] / [treatments] at [now]. The chart scrolls through the history
 * with the platform's drag, fling and stretch overscroll; two fingers zoom the time axis (2 h…31 d) at their centre;
 * the renderer's *Now* pill (or TalkBack's *Back to now*) returns to the usual window. The y range and the plot's
 * bottom ease towards the visible window's own (critically damped: a bouncing scale would read as the data moving).
 *
 * TalkBack reads [description] as one node; its custom actions are [actions] plus Earlier / Later / Zoom in / Zoom out
 * / Back to now. The status pill is a button ([pill], read as [pillWords]) that calls [onPill].
 */
@Composable
fun PanelCard(
    glucose: GlucoseState,
    history: List<Reading>,
    treatments: List<Treatment>,
    now: Instant,
    config: GraphConfig,
    description: String,
    modifier: Modifier = Modifier,
    actions: List<CustomAccessibilityAction> = emptyList(),
    scroll: ChartScroll = remember { ChartScroll() },
    pill: PillAction = PillAction.None,
    pillWords: String? = null,
    onPill: (PillAction) -> Unit = {},
) {
    val resources = LocalContext.current.resources
    val words = remember(resources) { CardWords.of(resources) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = GraphPalette.of(dark)
    val reduced = LocalReducedMotion.current
    val nowS = now.epochSecond
    val points = remember(history) { history.map { it.toPoint() } }
    val status = glucose.alerts.status
    val sensor = glucose.sensor.info(now)
    val model =
        remember(points, treatments, nowS, words, status, sensor) {
            CardModel(
                readings = points,
                treatments = treatments,
                nowS = nowS,
                words = words,
                status = status,
                sensor = sensor,
                // each slot's window counted once per reading, not per scrolled frame
                metricsOf = MetricsCache(points, nowS),
            )
        }
    val style = remember(config) { config.appPanelStyle() }
    val motion = panelMotion(glucose, now, config, palette, reduced)
    val base = style.graph
    val default = base?.windowS ?: 0L
    val latestNow by rememberUpdatedState(nowS)
    val first by rememberUpdatedState(points.firstOrNull()?.t ?: nowS)
    val laid = remember { Laid() }
    val state =
        rememberScrollableState { delta ->
            // reversed: a finger moving left (positive) goes forward in time, towards now; px of the plot
            val span = scroll.span(default)
            val spp = span / laid.plotW.coerceAtLeast(1f).toDouble()
            (scroll.by(delta * spp, first + span, latestNow) / spp).toFloat()
        }
    // the system's stretch at the ends of the history (now, the oldest reading)
    val overscroll = rememberOverscrollEffect()
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val away by remember(scroll) { derivedStateOf { scroll.away } }
    val nowPill = animateFloatAsState(if (away) 1f else 0f, Motion.defaultEffects(reduced), label = "now pill")
    val pointsNow by rememberUpdatedState(points)

    fun zoomed() = base?.copy(windowS = scroll.span(default))

    // the visible window's own y range, eased (critically damped at the spatial spring's stiffness)
    val y =
        remember {
            Animatable(
                zoomed()?.let { GlucoseCard.yRange(points, scroll.endS ?: nowS, it) }.toOffset(),
                Offset.VectorConverter,
            )
        }
    LaunchedEffect(base, scroll, reduced) {
        snapshotFlow { zoomed()?.let { GlucoseCard.yRange(pointsNow, scroll.endS ?: latestNow, it) }.toOffset() }
            .collectLatest { if (reduced) y.snapTo(it) else y.animateTo(it, spring(1f, EASE_STIFFNESS)) }
    }
    // the plot's bottom, eased the same way: the treatment lane coming and going with the window never makes it jump
    val inset = remember { Animatable(Float.NaN) }
    LaunchedEffect(scroll, reduced) {
        snapshotFlow { laid.insetTarget }.collectLatest { t ->
            if (t.isNaN()) return@collectLatest
            if (reduced || inset.value.isNaN() || (!scroll.away && !inset.isRunning)) {
                inset.snapTo(t)
            } else {
                inset.animateTo(t, spring(1f, EASE_STIFFNESS))
            }
        }
    }

    // back to now and the place's own window, end and length together (inside the scroll's mutex: a fling stops)
    fun back() {
        if (!scroll.away) return
        val end0 = (scroll.endS ?: latestNow).toDouble()
        val span0 = scroll.span(default).toDouble()
        scope.launch {
            state.scroll {
                if (!reduced) {
                    Animatable(0f).animateTo(1f, spring(1f, EASE_STIFFNESS)) {
                        val n = latestNow
                        scroll.set(end0 + (n - end0) * value, span0 + (default - span0) * value, default, n)
                    }
                }
                scroll.set(latestNow.toDouble(), default.toDouble(), default, latestNow)
            }
        }
    }

    fun page(forward: Boolean) {
        val span = scroll.span(default)
        scroll.by((if (forward) 1 else -1) * span / 2.0, first + span, latestNow)
    }
    val earlier = stringResource(R.string.home_chart_earlier)
    val later = stringResource(R.string.home_chart_later)
    val zoomIn = stringResource(R.string.home_chart_zoom_in)
    val zoomOut = stringResource(R.string.home_chart_zoom_out)
    val backToNow = stringResource(R.string.home_chart_back)
    val chartActions =
        if (base == null) {
            emptyList()
        } else {
            buildList {
                add(CustomAccessibilityAction(earlier) { true.also { page(forward = false) } })
                if (scroll.endS != null) add(CustomAccessibilityAction(later) { true.also { page(forward = true) } })
                add(CustomAccessibilityAction(zoomOut) { true.also { scroll.step(out = true, default, first, latestNow) } })
                add(CustomAccessibilityAction(zoomIn) { true.also { scroll.step(out = false, default, first, latestNow) } })
                if (away) add(CustomAccessibilityAction(backToNow) { true.also { back() } })
            }
        }
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CARD_HEIGHT)
                .testTag("home-card")
                .clearAndSetSemantics {
                    contentDescription = description
                    val all = actions + chartActions
                    if (all.isNotEmpty()) customActions = all
                }.overscroll(overscroll)
                .scrollable(state, Orientation.Horizontal, enabled = base != null, overscrollEffect = overscroll, reverseDirection = true)
                .pointerInput(scroll, default) {
                    // no chart (3 h+ without readings): nothing to zoom
                    if (base == null) return@pointerInput
                    pinch(scroll, laid, default, { first }, { latestNow }) { vx ->
                        scope.launch { state.scroll { with(fling) { performFling(-vx) } } }
                    }
                }.pointerInput(scroll, default, reduced) {
                    detectTapGestures { p -> if (scroll.away && laid.now?.contains(p.x, p.y) == true) back() }
                },
        ) {
            val view =
                if (!scroll.away && nowPill.value == 0f && !y.isRunning && !inset.isRunning) {
                    null
                } else {
                    ChartView(
                        endS = scroll.endS ?: nowS,
                        y = y.value.x to y.value.y,
                        now = nowPill.value,
                        plotInset = inset.value.takeIf { !it.isNaN() },
                        spanS = scroll.spanS,
                    )
                }
            drawIntoCanvas { canvas ->
                val rec =
                    GlucoseCard.draw(
                        canvas.nativeCanvas,
                        size.width,
                        size.height,
                        model,
                        style,
                        palette,
                        density,
                        CardSurface.APP_PANEL,
                        CardPart.ALL,
                        motion = motion,
                        view = view,
                    )
                laid.read(rec)
            }
        }
        val box = laid.pill
        if (box != null && pill != PillAction.None) {
            PillButton(box, pillWords.orEmpty(), pillLabel(pill)) { onPill(pill) }
        }
    }
}

/**
 * The panel's value motion (paint only, never layout): a new number rolls in from the previous one (up for a rising
 * value), the arrow turns to the new rate and the value's range colour fades. Reduced motion: drawn still (null).
 */
@Composable
private fun panelMotion(
    glucose: GlucoseState,
    now: Instant,
    config: GraphConfig,
    palette: GraphPalette,
    reduced: Boolean,
): CardMotion? {
    val look = homeLook(glucose, now)
    val number = look.text.takeIf { look.state == GlucoseLook.State.VALUE && !look.strike }
    val roll = remember { Animatable(1f) }
    var from by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf(number) }
    var up by remember { mutableStateOf(true) }
    LaunchedEffect(number, reduced) {
        val last = shown
        shown = number
        if (!reduced && number != null && last != null && number != last) {
            up = glucose.latest?.mgdl?.let { new -> (last.toIntOrNull() ?: new) < new } ?: true
            from = last
            roll.snapTo(0f)
            roll.animateTo(1f, Motion.defaultSpatial())
        } else {
            roll.snapTo(1f)
        }
    }
    val rate = glucose.latest?.rateMgdlPerMin
    val angle by animateFloatAsState(rate?.let { TrendAngle.deg(it) } ?: 0f, Motion.defaultSpatial(reduced), label = "trend")
    val latest = glucose.latest
    val target = if (latest == null || look.strike) palette.inkSoft else palette.at(config.bands, latest.mgdl)
    val color by animateColorAsState(Color(target), Motion.defaultEffects(reduced), label = "range")
    if (reduced) return null
    return CardMotion(from, roll.value, up, rate?.let { angle }, color.toArgb())
}

/** TalkBack's "double-tap to …" for [action]. */
@Composable
private fun pillLabel(action: PillAction): String =
    stringResource(
        when (action) {
            is PillAction.Treat -> R.string.home_add_treatment
            PillAction.ReplaceSensor -> R.string.home_pill_replace_sensor
            else -> R.string.home_pill_alert_options
        },
    )

/**
 * The pill's button over the renderer's drawing (the card draws the pill; this only makes it act): at [box] (px in the
 * card), the Button role, [description] as its words and [actionLabel] as the click label, a capsule ripple the
 * pill's size and a touch target of at least 48 dp.
 */
@Composable
private fun PillButton(
    box: RectF,
    description: String,
    actionLabel: String,
    onClick: () -> Unit,
) {
    val d = LocalDensity.current
    val min = with(d) { 48.dp.toPx() }
    val padX = with(d) { 8.dp.toPx() }
    val w = box.width() + 2 * padX
    val h = maxOf(box.height(), min)
    val left = box.left - padX
    val top = box.centerY() - h / 2
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .size(with(d) { w.toDp() }, with(d) { h.toDp() })
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClickLabel = actionLabel,
                onClick = onClick,
            ).semantics { contentDescription = description }
            .testTag("status-pill"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(with(d) { box.width().toDp() }, with(d) { box.height().toDp() })
                .clip(RoundedCornerShape(percent = 50))
                .indication(interaction, ripple()),
        )
    }
}

/**
 * Two fingers zoom the time axis at their centroid and move it with them (one finger is the scroll's). Only the fingers
 * down in both the previous and this event count, so a finger landing or lifting never makes the centroid or the spread
 * jump. After a pinch the remaining finger pans 1:1 and, lifted, flings ([onFling], px/s) — it never zooms.
 */
private suspend fun PointerInputScope.pinch(
    scroll: ChartScroll,
    laid: Laid,
    default: Long,
    first: () -> Long,
    now: () -> Long,
    onFling: (Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var pinched = false
        val velocity = VelocityTracker()
        var lifted = false
        do {
            val ev = awaitPointerEvent()
            val held = ev.changes.filter { it.pressed && it.previousPressed }
            val span = scroll.span(default)
            val spp = span / laid.plotW.coerceAtLeast(1f).toDouble()
            if (held.size >= 2) {
                pinched = true
                val c = held.fold(Offset.Zero) { acc, h -> acc + h.position } / held.size.toFloat()
                val p = held.fold(Offset.Zero) { acc, h -> acc + h.previousPosition } / held.size.toFloat()
                val sizeNow = held.sumOf { (it.position - c).getDistance().toDouble() }
                val sizeBefore = held.sumOf { (it.previousPosition - p).getDistance().toDouble() }
                val panX = c.x - p.x
                if (panX != 0f) scroll.by(-panX * spp, first() + span, now())
                if (sizeBefore > 0 && sizeNow > 0 && sizeNow != sizeBefore) {
                    val anchor = ((c.x - laid.plotLeft) / laid.plotW.coerceAtLeast(1f)).toDouble()
                    scroll.zoom(sizeNow / sizeBefore, anchor, default, first(), now())
                }
                velocity.resetTracking()
            } else if (pinched && held.size == 1) {
                val f = held[0]
                val dx = f.position.x - f.previousPosition.x
                if (dx != 0f) scroll.by(-dx * spp, first() + span, now())
                velocity.addPosition(f.uptimeMillis, f.position)
            }
            if (pinched) {
                ev.changes.forEach { it.consume() }
                if (ev.changes.any { !it.pressed && it.previousPressed }) lifted = true
            }
        } while (ev.changes.any { it.pressed })
        if (pinched && lifted) {
            val vx = velocity.calculateVelocity().x
            if (vx != 0f) onFling(vx)
        }
    }
}

/** What the last drawing laid out (written while drawing, read by the gestures and the pill's button). */
private class Laid {
    var plotW = 0f
    var plotLeft = 0f
    var now: RectF? = null

    /** The plot's bottom as px above the chart's bottom: a state, so the easing follows it. */
    var insetTarget by mutableFloatStateOf(Float.NaN)

    /** Where the pill is drawn: a state, so its button follows it. */
    var pill by mutableStateOf<RectF?>(null)

    fun read(rec: CardLayout) {
        rec.first("chart.plot")?.box?.let { b ->
            plotW = b.width()
            plotLeft = b.left
        }
        now = rec.first("chart.now")?.box
        val area = rec.first("chart")?.box
        rec.first("chart.plot.natural")?.box?.let { b -> if (area != null) insetTarget = area.bottom - b.bottom }
        val box = (rec.all("value.status") + rec.all("value.pill")).firstNotNullOfOrNull { it.box }
        if (box != pill) pill = box?.let(::RectF)
    }
}

private fun Pair<Float, Float>?.toOffset(): Offset = this?.let { Offset(it.first, it.second) } ?: Offset.Zero

/** Stiffness of the eased y range, plot bottom and the way back to now (the spatial spring's, critically damped). */
private const val EASE_STIFFNESS = 380f
private val CARD_HEIGHT = 300.dp

/**
 * What TalkBack reads for the card: the value sentence ("112 milligrams per decilitre, falling slowly, in range,
 * 2 minutes ago"; an old value says so first) and the chart summary. Without a value only the chart summary (the reason
 * under the card speaks for the missing value); empty when the chart has nothing either.
 */
fun cardDescription(
    state: HomeUiState,
    resources: Resources,
): String {
    val bands = state.config.bands
    val hours = state.config.hours(GraphPlace.APP)
    val window = state.glucose.recent.filter { it.t >= state.now.minusSeconds(hours * 3600L) }
    val chart =
        if (window.isEmpty()) {
            null
        } else {
            val inRange = window.count { bands.of(it.mgdl).group == 0 } * 100 / window.size
            resources.getString(R.string.home_a11y_chart, hours, inRange)
        }
    val reading = state.glucose.latest
    val text = state.look.text
    if (!state.look.shown || reading == null || text == null) return chart.orEmpty()
    val words = CardWords.of(resources)
    val range =
        when (bands.of(reading.mgdl)) {
            Band.VERY_LOW -> R.string.home_a11y_range_very_low
            Band.LOW -> R.string.home_a11y_range_low
            Band.TIGHT, Band.TARGET -> R.string.home_a11y_range_in
            Band.HIGH -> R.string.home_a11y_range_high
            Band.VERY_HIGH -> R.string.home_a11y_range_very_high
        }
    val minutes = Duration.between(reading.t, state.now).toMinutes().coerceAtLeast(0)
    val age = if (minutes == 0L) resources.getString(R.string.home_a11y_just_now) else words.ago(Durations.text(minutes, words.durations))
    val trend = words.trend(state.look.rate.takeIf { state.look.trend == TrendMark.ARROW })
    val sentence = resources.getString(R.string.home_a11y_value, text, trend, resources.getString(range), age)
    val value = if (state.look.strike) resources.getString(R.string.home_a11y_stale, sentence) else sentence
    return if (chart == null) value else "$value. $chart"
}
