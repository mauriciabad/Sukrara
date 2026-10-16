// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

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
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
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

@Composable
private fun WidgetFrame(content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(actionStartActivity<app.mauri.sukrara.ui.QuickTreatmentActivity>())
            .padding(PAD_DP.dp),
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
    val graph = cfg.graphStyle(w.hours ?: cfg.hours(GraphPlace.WIDGETS), android.text.format.DateFormat.is24HourFormat(c))
        .copy(side = w.side)
    val rings = cfg.rings(w.rings ?: cfg.ringCount, w.ringRange ?: cfg.ringRange)
    return when (kind) {
        WidgetKind.VALUE -> CardStyle(graph = null, rings = rings)
        WidgetKind.GRAPH -> CardStyle(graph = graph, rings = emptyList(), value = false)
        WidgetKind.COMBINED -> CardStyle(graph = graph, rings = rings)
    }
}

@Composable
fun CardContent(frame: WidgetUpdater.Frame, kind: WidgetKind, appWidgetId: Int? = null) {
    val ctx = LocalContext.current
    val size = realSize(appWidgetId)
    val w = size.width.value - 2 * PAD_DP
    val h = size.height.value - 2 * PAD_DP
    val st = cardStyle(ctx, kind, appWidgetId?.let { GraphSettings.widget(ctx, it) } ?: WidgetGraphConfig())
    val d = ctx.resources.displayMetrics.density
    // all sizes of one widget stay well under the RemoteViews bitmap limit
    val scale = min(1f, 1400f / (maxOf(w, h) * d))
    val bmp = createBitmap((w * d * scale).roundToInt().coerceAtLeast(16), (h * d * scale).roundToInt().coerceAtLeast(16))
    GlucoseCard.draw(Canvas(bmp), bmp.width.toFloat(), bmp.height.toFloat(), frame.history, frame.treatments, frame.nowSec,
        st, systemPalette(ctx), d * scale,
        tirOf = if (appWidgetId == null) { r, b -> app.mauri.sukrara.graph.GlucoseStats.timeInRange(frame.history, frame.nowSec - r.windowS, frame.nowSec, b) }
            else { r, b -> GraphData.tir(ctx, r.windowS, b, frame.nowSec) })
    Image(
        ImageProvider(bmp),
        contentDescription = describe(frame.history.lastOrNull(), frame.nowSec),
        modifier = GlanceModifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
    )
}

private fun describe(r: Reading?, now: Long): String {
    if (r == null) return "Glucose: no readings yet"
    val f = Freshness.of(now - r.t)
    return if (!f.showValue) "Glucose: no reading since ${Freshness.timeText(r.t, now)}"
    else "Glucose ${r.sgv} mg/dL, ${Trend.word(r.rate)}${if (f.showTime) ", at ${Freshness.timeText(r.t, now)}" else ""}"
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
