// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.unit.dp
import app.mauri.sukrara.core.model.Durations
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.graph.CardModel
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardStatus
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.graph.ChartView
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.GraphStyle
import app.mauri.sukrara.graph.SensorBadge
import app.mauri.sukrara.graph.SensorInfo
import app.mauri.sukrara.graph.TrendMark
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.toPoint
import java.time.Duration
import java.time.Instant

/*
 * The home screen's card: the registered `app-panel` surface (contracts/graph/surfaces/surfaces.json). It only chooses
 * its size and style; the renderer (`GlucoseCard`) lays out and draws the value, arrow, badge, slots and chart.
 */

/** The renderer's decision about the latest value on the home card (what the panel, the reason row and TalkBack use). */
fun homeLook(
    state: GlucoseState,
    now: Instant,
): GlucoseLook {
    val sensor =
        when (val s = state.sensor) {
            SensorState.Ended -> SensorBadge.Look(label = "", life = 0f, ended = true)
            is SensorState.WarmingUp -> SensorBadge.Look(label = "", life = 1f, ended = false, warmup = "", readyAt = s.readyAt.epochSecond)
            else -> null
        }
    return GlucoseCard.face(state.latest?.toPoint(), now.epochSecond, sensor, CardSurface.APP_PANEL)
}

/** The sensor's dates as the card needs them; null when nothing is known (the card then shows no sensor badge). */
private fun SensorState.info(): SensorInfo? =
    when (this) {
        is SensorState.Active -> endsAt?.let { SensorInfo(startS = it.epochSecond - SENSOR_LIFE.seconds, endS = it.epochSecond) }
        is SensorState.WarmingUp -> {
            val start = readyAt.epochSecond - WARMUP.seconds
            SensorInfo(startS = start, endS = start + SENSOR_LIFE.seconds, warmupEndS = readyAt.epochSecond)
        }
        else -> null
    }

private val SENSOR_LIFE = Duration.ofDays(15)
private val WARMUP = Duration.ofHours(1)

/**
 * The card for [glucose] and [treatments] at [now]: drag to scroll back in time, pinch to zoom, "Now" to come back.
 * TalkBack reads [description] (the value sentence and the chart summary) as one node; [actions] are its custom
 * actions (Unmute).
 */
@Composable
fun PanelCard(
    glucose: GlucoseState,
    treatments: List<Treatment>,
    now: Instant,
    description: String,
    modifier: Modifier = Modifier,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val resources = LocalContext.current.resources
    val words = remember(resources) { CardWords.of(resources) }
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = GraphPalette.of(dark)
    val nowS = now.epochSecond
    val model =
        remember(glucose, treatments, nowS, words) {
            CardModel(
                readings = glucose.recent.map { it.toPoint() },
                treatments = treatments,
                nowS = nowS,
                words = words,
                status = glucose.alerts.mutedUntil?.let { CardStatus.Muted(it.epochSecond) } ?: CardStatus.None,
                sensor = glucose.sensor.info(),
            )
        }
    val style = remember { CardStyle(graph = GraphStyle(windowS = DEFAULT_SPAN_S), sensorTime = true) }
    // null = following now; otherwise the end of the scrolled window
    var endS by rememberSaveable { mutableStateOf<Long?>(null) }
    var spanS by rememberSaveable { mutableStateOf(DEFAULT_SPAN_S) }
    val density = LocalDensity.current.density
    Box(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(CARD_HEIGHT)
                .clearAndSetSemantics {
                    contentDescription = description
                    if (actions.isNotEmpty()) customActions = actions
                }.pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        spanS = (spanS / zoom).toLong().coerceIn(ChartView.MIN_SPAN_S, ChartView.MAX_SPAN_S)
                        val shifted = (endS ?: nowS) - (pan.x / size.width * spanS).toLong()
                        endS = shifted.takeIf { it < nowS - NOW_SNAP_S }
                    }
                },
        ) {
            val view = ChartView(endS = endS ?: nowS, spanS = spanS)
            drawIntoCanvas { canvas ->
                GlucoseCard.draw(canvas.nativeCanvas, size.width, size.height, model, style, palette, density, CardSurface.APP_PANEL, CardPart.ALL, view = view)
            }
        }
        if (endS != null || spanS != DEFAULT_SPAN_S) {
            FilledTonalButton(
                onClick = {
                    endS = null
                    spanS = DEFAULT_SPAN_S
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) { Text(stringResource(R.string.home_chart_now)) }
        }
    }
}

private const val DEFAULT_SPAN_S = 6 * 3600L

/** A scroll that ends within a minute of now follows now again. */
private const val NOW_SNAP_S = 60L
private val CARD_HEIGHT = 300.dp

/**
 * What TalkBack reads for the card: the value sentence ("112 milligrams per decilitre, falling slowly, in range,
 * 2 minutes ago"; an old value says so first) and the chart summary. Empty when there is no value (the reason panel
 * speaks instead).
 */
fun cardDescription(
    state: HomeUiState,
    resources: Resources,
): String {
    val reading = state.glucose.latest
    val text = state.look.text
    if (!state.look.shown || reading == null || text == null) return ""
    val words = CardWords.of(resources)
    val bands = Bands()
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
    val window = state.glucose.recent.filter { it.t >= state.now.minusSeconds(DEFAULT_SPAN_S) }
    if (window.isEmpty()) return value
    val inRange = window.count { bands.of(it.mgdl).group == 0 } * 100 / window.size
    return value + ". " + resources.getString(R.string.home_a11y_chart, (DEFAULT_SPAN_S / 3600).toInt(), inRange)
}
