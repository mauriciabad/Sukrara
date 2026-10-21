// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.R
import android.content.Context
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphData
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.WidgetGraphConfig
import app.mauri.sukrara.graph.systemPalette
import kotlin.math.min
import kotlin.math.roundToInt

// ---- Widgets -------------------------------------------------------------------------------------------------

/**
 * The three widgets draw the same card ([GlucoseCard], investigation 16) into one bitmap at the real pixel size of
 * each placement (SizeMode.Exact + [WidgetProbe]), so nothing is stretched and the layout adapts: rings that don't
 * fit are dropped, short wide placements put the value beside the graph. Each placement can override the window,
 * the rings and the side profile ([WidgetConfigActivity], "Reconfigure" on long-press).
 */
enum class WidgetKind { VALUE, GRAPH, COMBINED }

abstract class CardWidget(private val kind: WidgetKind) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.current(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent { Live { CardContent(it, kind, appWidgetId) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { WidgetFrame { CardContent(sampleFrame(), kind) } } }
    }
}

class ValueWidget : CardWidget(WidgetKind.VALUE) {
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(110.dp, 48.dp), DpSize(250.dp, 80.dp)))
}

class GraphWidget : CardWidget(WidgetKind.GRAPH) {
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(350.dp, 160.dp)))
}

class CombinedWidget : CardWidget(WidgetKind.COMBINED) {
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 170.dp), DpSize(350.dp, 220.dp)))
}

abstract class BaseReceiver : GlanceAppWidgetReceiver() {
    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        SukraraHooks.ensureService(context)
    }

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        SukraraHooks.ensureService(context)
    }

    /** Placed or resized: the reported size changed, so measure the real one again. */
    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: android.appwidget.AppWidgetManager,
            appWidgetId: Int, newOptions: android.os.Bundle) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        SukraraHooks.remeasure(context, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        WidgetProbe.forget(context, appWidgetIds)
        GraphSettings.forgetWidgets(context, appWidgetIds)
    }
}

class ValueWidgetReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = ValueWidget() }
class GraphWidgetReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = GraphWidget() }
class CombinedWidgetReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = CombinedWidget() }

// ---- Shared content ------------------------------------------------------------------------------------------

/** Theme + background + tap-to-open, fed by the live frame. */
@Composable
private fun Live(content: @Composable (WidgetUpdater.Frame) -> Unit) {
    val ctx = LocalContext.current
    val frame by WidgetUpdater.frame.collectAsState()
    GlanceTheme { WidgetFrame { content(frame ?: WidgetUpdater.current(ctx)) } }
}

private const val PAD_DP = 10

/** This phone wears the sensor and it has ended: taps open the Sensor screen (scan the new one). */
private fun sensorEndedHere(c: Context): Boolean = app.mauri.sukrara.App.role(c) == "owner" &&
    app.mauri.sukrara.core.SensorLife.current.value?.let { app.mauri.sukrara.core.SensorLife.ended(it.endEpoch, Proto.nowS()) } == true

@Composable
private fun WidgetFrame(content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            // an ended sensor: the tap does what the "Replace sensor" pill says (the wearer's Sensor screen)
            .clickable(if (sensorEndedHere(LocalContext.current)) actionStartActivity(app.mauri.sukrara.ui.MainActivity.sensorIntent(LocalContext.current))
                else actionStartActivity<app.mauri.sukrara.ui.QuickTreatmentActivity>()),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * The size to lay out and draw for: the measured one ([WidgetProbe]) when the launcher's reported size
 * ([LocalSize]) is known to be wrong, else the reported one. Previews have no widget id.
 */
@Composable
private fun realSize(appWidgetId: Int?): DpSize {
    val reported = LocalSize.current
    return appWidgetId?.let { WidgetProbe.actual(LocalContext.current, it, reported) } ?: reported
}

/** What [kind] shows with this placement's own settings ([WidgetGraphConfig]) over the app-wide ones. */
fun cardStyle(c: Context, kind: WidgetKind, w: WidgetGraphConfig): CardStyle {
    val cfg = GraphSettings.value
    val graph = cfg.graphStyle(w.hours ?: cfg.hours(GraphPlace.WIDGETS))
        .copy(side = w.side ?: cfg.side)
    val rings = cfg.rings(w.rings ?: cfg.ringCount, w.ringRange ?: cfg.ringRange)
    val sensor = w.sensor ?: cfg.sensorTime(GraphPlace.WIDGETS)
    return when (kind) {
        WidgetKind.VALUE -> CardStyle(graph = null, rings = rings, last = cfg.lastPlace, lastElse = cfg.lastElseRing(), sensorTime = sensor,
            bands = cfg.bands)
        WidgetKind.GRAPH -> CardStyle(graph = graph, rings = emptyList(), value = false, sensorTime = sensor)
        WidgetKind.COMBINED -> CardStyle(graph = graph, rings = rings, last = cfg.lastPlace, lastElse = cfg.lastElseRing(), sensorTime = sensor)
    }
}

/** The registered surface of each widget kind ([CardSurface]). */
fun WidgetKind.surface() = when (this) {
    WidgetKind.VALUE -> CardSurface.WIDGET_VALUE
    WidgetKind.GRAPH -> CardSurface.WIDGET_GRAPH
    WidgetKind.COMBINED -> CardSurface.WIDGET_COMBINED
}

/**
 * A widget's picture, the one entry every home-screen widget and the widget settings' preview draw through: the
 * card for [kind] with this placement's settings [w], [wDp] × [hDp] dp of widget. The picture covers the whole
 * widget and the card is drawn inset by the margin, so what overflows the card (a ring's laurel or crown) shows in
 * the margin instead of being cut at the card's edge. [metricsOf] null = the slots from the stored history.
 */
fun renderWidget(c: Context, kind: WidgetKind, wDp: Float, hDp: Float, frame: WidgetUpdater.Frame, w: WidgetGraphConfig,
                 metricsOf: ((app.mauri.sukrara.graph.RingSpec, app.mauri.sukrara.graph.Bands) -> app.mauri.sukrara.graph.GlucoseMetrics)? = null): android.graphics.Bitmap {
    val cw = wDp - 2 * PAD_DP
    val ch = hDp - 2 * PAD_DP
    val st = cardStyle(c, kind, w)
    val d = c.resources.displayMetrics.density
    // all sizes of one widget stay well under the RemoteViews bitmap limit
    val scale = min(1f, 1400f / (maxOf(cw, ch) * d))
    val pad = PAD_DP * d * scale
    val bmp = createBitmap((cw * d * scale + 2 * pad).roundToInt().coerceAtLeast(16), (ch * d * scale + 2 * pad).roundToInt().coerceAtLeast(16))
    val cv = Canvas(bmp).apply { translate(pad, pad) }
    GlucoseCard.draw(cv, bmp.width - 2 * pad, bmp.height - 2 * pad, frame.history, frame.treatments, frame.nowSec,
        st, systemPalette(c), d * scale, kind.surface(), CardPart.ALL,
        metricsOf = metricsOf ?: { r, b -> GraphData.metrics(c, r.windowS, b, frame.nowSec) })
    return bmp
}

@Composable
fun CardContent(frame: WidgetUpdater.Frame, kind: WidgetKind, appWidgetId: Int? = null) {
    val ctx = LocalContext.current
    val size = realSize(appWidgetId)
    val bmp = renderWidget(ctx, kind, size.width.value, size.height.value, frame,
        appWidgetId?.let { GraphSettings.widget(ctx, it) } ?: WidgetGraphConfig(),
        // the picker's preview has no stored history: its slots come from the sample frame
        metricsOf = if (appWidgetId == null) { r, b -> app.mauri.sukrara.graph.GlucoseMetrics.of(frame.history, frame.nowSec - r.windowS, frame.nowSec, b) } else null)
    Image(
        ImageProvider(bmp),
        contentDescription = describe(ctx, frame, if (kind == WidgetKind.GRAPH) null else frame.treatments),
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

private fun describe(c: Context, frame: WidgetUpdater.Frame, treatments: List<app.mauri.sukrara.treatments.Treatment>?): String =
    describeCard(c, frame.history.lastOrNull(), frame.nowSec, treatments)

/**
 * What a card says to a screen reader (the home widgets, the app panel): the value ([valueWords]) and, on cards with a
 * value, the status badge's words; [treatments] null = no badge. Its decision is the card's ([GlucoseCard.face]).
 */
fun describeCard(c: Context, r: Reading?, now: Long, treatments: List<app.mauri.sukrara.treatments.Treatment>?): String {
    val look = GlucoseCard.face(r, now, app.mauri.sukrara.graph.SensorBadge.look(app.mauri.sukrara.core.SensorLife.current.value, now, r?.t),
        CardSurface.WIDGET_DESCRIPTION)
    if (look.state == GlucoseLook.State.ENDED) return c.getString(R.string.card_sensor_ended) + ". " + c.getString(R.string.badge_replace_sensor)
    if (r == null || look.state != GlucoseLook.State.VALUE) return valueWords(c, look, r, now, withTime = true)
    val badge = treatments?.let {
        app.mauri.sukrara.graph.StatusBadge.describe(app.mauri.sukrara.alerts.AlertStatus.of(r, app.mauri.sukrara.alerts.Alerts.settings.value, it, now,
            app.mauri.sukrara.alerts.Alerts.paused.value, app.mauri.sukrara.alerts.Alerts.allMuted.value), now)
    }
    val value = valueWords(c, look, r, now, withTime = true)
    return value + (badge?.let { ". $it" } ?: "")
}

/**
 * What a screen reader says for the latest reading [r] with its [look] ([GlucoseCard.face]): the value (and "old" once
 * struck through), the trend's word (unknown with the "no trend" ring), the reading's time once late ([withTime]);
 * "no reading since …" from 20 min; a new sensor's warm-up. The home widgets, the app panel and the lock screen widget.
 */
internal fun valueWords(c: Context, look: GlucoseLook, r: Reading?, now: Long, withTime: Boolean): String = when {
    look.state == GlucoseLook.State.WARMING_UP -> c.getString(R.string.card_warming_up)
    r == null || look.state == GlucoseLook.State.NONE -> c.getString(R.string.widget_desc_no_readings)
    look.state != GlucoseLook.State.VALUE -> c.getString(R.string.widget_desc_no_reading_since, Freshness.timeText(r.t, now))
    else -> {
        val v = if (look.strike) c.getString(R.string.notif_value_old, look.text) else look.text!!
        val trend = Trend.word(r.rate.takeIf { look.trend == app.mauri.sukrara.graph.TrendMark.ARROW })
        if (withTime && look.fresh.showTime) c.getString(R.string.widget_desc_value_at, v, trend, Freshness.timeText(r.t, now))
        else c.getString(R.string.widget_desc_value, v, trend)
    }
}

/** Widget-picker preview data: the synthetic curve (in range, slightly rising). */
fun sampleFrame(): WidgetUpdater.Frame {
    val now = Proto.nowS() / 60 * 60
    val phase = Math.floorMod(170 - now / 60, 180L).toInt() // same picture whenever it's generated: ~110, rising
    return WidgetUpdater.Frame((24 * 60 downTo 0).map { sample(now - it * 60L, phase) }, now)
}

/** Preview curve (copied from the widgets spike's SyntheticSource). */
private fun sample(sec: Long, phaseMin: Int): Reading {
    val m = sec / 60.0 + phaseMin
    val w1 = 2 * Math.PI / 180
    val w2 = 2 * Math.PI / 23
    val v = 140 + 90 * Math.sin(w1 * m) + 6 * Math.sin(w2 * m)
    val rate = 90 * w1 * Math.cos(w1 * m) + 6 * w2 * Math.cos(w2 * m)
    return Reading(sec, Math.round(v).toInt(), rate)
}
