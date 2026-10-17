// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.graphics.createBitmap
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LocalAppWidgetOptions
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GraphData
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
import app.mauri.sukrara.graph.MetricGauge
import app.mauri.sukrara.graph.SlotKind
import app.mauri.sukrara.graph.TirRing
import app.mauri.sukrara.graph.systemPalette
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Investigation 17: the widget for the empty lower part of the lock screen. Not offered by launchers (category
 * `keyguard` only): Sukrara's LSPosed module hosts it inside SystemUI (hook/LockscreenWidgetHost), sized to the space
 * between the notifications and the shortcuts, and tells it where the lock icon is ([HOLE_LEFT], [HOLE_RIGHT],
 * [HOLE_BOTTOM] in the widget options, dp from the widget's top-left).
 *
 * The same pieces as the other widgets' card ([GlucoseCard], investigation 16), arranged around the lock icon: value
 * and arrow left of it, the time-in-range rings right of it, the chart below. No background (it floats on the
 * wallpaper like the clock), always the dark palette (lock screen content is light), and a tap opens the treatment
 * dialog, which shows over the lock screen.
 */
class LockscreenWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.current(context)
        provideContent {
            val frame by WidgetUpdater.frame.collectAsState()
            LockscreenContent(frame ?: WidgetUpdater.current(LocalContext.current))
        }
    }

    companion object {
        const val RECEIVER = "app.mauri.sukrara.widgets.LockscreenWidgetReceiver"
        const val HOLE_LEFT = "sukrara.lockHoleLeft"
        const val HOLE_RIGHT = "sukrara.lockHoleRight"
        const val HOLE_BOTTOM = "sukrara.lockHoleBottom"
    }
}

class LockscreenWidgetReceiver : BaseReceiver() { override val glanceAppWidget: GlanceAppWidget = LockscreenWidget() }

private val RING_EMPHASIS = floatArrayOf(1f, 0.55f, 0.35f)

@Composable
private fun LockscreenContent(frame: WidgetUpdater.Frame) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val opts = LocalAppWidgetOptions.current
    val w = size.width.value
    val h = size.height.value
    val hole = Hole(
        opts.getFloat(LockscreenWidget.HOLE_LEFT, w / 2 - 44),
        opts.getFloat(LockscreenWidget.HOLE_RIGHT, w / 2 + 44),
        opts.getFloat(LockscreenWidget.HOLE_BOTTOM, 76f),
    )
    val d = ctx.resources.displayMetrics.density
    // well under the RemoteViews bitmap limit, like the other widgets
    val scale = min(1f, 1400f / (maxOf(w, h) * d))
    val bmp = createBitmap((w * d * scale).roundToInt().coerceAtLeast(16), (h * d * scale).roundToInt().coerceAtLeast(16))
    draw(ctx, Canvas(bmp), frame, hole, d * scale)
    Image(
        ImageProvider(bmp),
        contentDescription = describe(frame),
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<app.mauri.sukrara.ui.QuickTreatmentActivity>()),
        contentScale = ContentScale.FillBounds,
    )
}

/** Where the lock icon is, dp from the widget's top-left: [left]…[right] wide, from the top down to [bottom]. */
private data class Hole(val left: Float, val right: Float, val bottom: Float)

private fun draw(ctx: Context, cv: Canvas, frame: WidgetUpdater.Frame, hole: Hole, d: Float) {
    val cfg = GraphSettings.value
    // Transparent: the end dot's halo is drawn in the background colour.
    val pal = systemPalette(ctx, dark = true).copy(bg = 0)
    val w = cv.width.toFloat()
    val h = cv.height.toFloat()
    val rowH = hole.bottom * d

    // Value + arrow, left of the lock icon (a little shorter than the row so the number isn't taller than the icon).
    val valueBox = RectF(0f, rowH * 0.1f, hole.left * d, rowH * 0.9f)
    cv.withBox(valueBox) {
        GlucoseCard.draw(cv, valueBox.width(), valueBox.height(), frame.history, frame.treatments, frame.nowSec,
            CardStyle(graph = null, rings = emptyList()), pal, d)
    }

    // The ring slots (time in range, GMI, average, CV: investigations 16 and 18), right of the lock icon, main
    // rightmost; drawn like GlucoseCard's header.
    val ringsBox = RectF(hole.right * d, 0f, w, rowH)
    val specs = cfg.rings()
    if (specs.isNotEmpty()) {
        val gap = 8 * d
        val size = min(rowH * 0.9f, min(84 * d, (ringsBox.width() - gap * (specs.size - 1)) / specs.size))
        var x = ringsBox.right
        if (size >= 30 * d) for ((i, spec) in specs.withIndex()) {
            val m = GraphData.metrics(ctx, spec.windowS, cfg.bands, frame.nowSec)
            if (m.tir.coverage < GlucoseCard.MIN_COVERAGE) continue
            val e = if (spec.faded) RING_EMPHASIS[i] else 1f
            if (spec.kind == SlotKind.TIR) TirRing.draw(cv, x - size / 2, ringsBox.centerY(), size / 2, m.tir, spec, pal, e, d)
            else MetricGauge.draw(cv, x - size / 2, ringsBox.centerY(), size / 2, m, spec, pal, e, d)
            x -= size + gap
        }
    }

    // The chart below.
    val chart = RectF(0f, rowH + 8 * d, w, h)
    if (chart.height() > 24 * d) cv.withBox(chart) {
        val graph = cfg.graphStyle(cfg.hours(GraphPlace.WIDGETS), android.text.format.DateFormat.is24HourFormat(ctx))
        GlucoseCard.draw(cv, chart.width(), chart.height(), frame.history, frame.treatments, frame.nowSec,
            CardStyle(graph = graph, rings = emptyList(), value = false), pal, d)
    }
}

private inline fun Canvas.withBox(box: RectF, block: () -> Unit) {
    save(); translate(box.left, box.top); clipRect(0f, 0f, box.width(), box.height())
    try { block() } finally { restore() }
}

private fun describe(frame: WidgetUpdater.Frame): String {
    val r = frame.history.lastOrNull() ?: return "Glucose: no readings yet. Add treatment"
    val f = Freshness.of(frame.nowSec - r.t)
    val value = if (!f.showValue) "no reading since ${Freshness.timeText(r.t, frame.nowSec)}"
        else "${r.sgv} mg/dL, ${Trend.word(r.rate)}"
    return "Glucose $value. Add treatment"
}
