// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.R

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.layout.ContentScale

// ---- Widgets -------------------------------------------------------------------------------------------------

/** Current value: number + trend arrow + age. Responsive: 1×1, 2×1, 2×2 layouts. */
class ValueWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))
    override val previewSizeMode = SizeMode.Responsive(setOf(SMALL, WIDE, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.current(context)
        provideContent { Live { ValueContent(it) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { WidgetFrame { ValueContent(sampleFrame()) } } }
    }

    companion object {
        val SMALL = DpSize(48.dp, 48.dp)
        val WIDE = DpSize(110.dp, 48.dp)
        val LARGE = DpSize(110.dp, 110.dp)
    }
}

/**
 * Graph of the last hours. SizeMode.Exact (not Responsive) on purpose: the bitmap is drawn for the real pixel size
 * of each placement (portrait + landscape), so it is never stretched; wider placements show more hours.
 */
class GraphWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 110.dp), DpSize(350.dp, 160.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.current(context)
        provideContent { Live { GraphContent(it) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { WidgetFrame(padding = 8) { GraphContent(sampleFrame()) } } }
    }
}

/** Value header + graph. Exact for the same reason as [GraphWidget]; short placements put the value on the left. */
class CombinedWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(250.dp, 170.dp), DpSize(350.dp, 220.dp)))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        WidgetUpdater.current(context)
        provideContent { Live { CombinedContent(it) } }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { GlanceTheme { WidgetFrame { CombinedContent(sampleFrame()) } } }
    }
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

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        Unit
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

@Composable
private fun WidgetFrame(padding: Int = 10, content: @Composable () -> Unit) {
    Box(
        GlanceModifier.fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(actionStartActivity<app.mauri.sukrara.ui.MainActivity>())
            .padding(padding.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

private data class ValueStyle(val color: ColorProvider, val deco: TextDecoration, val stale: Boolean)

@Composable
private fun styleFor(r: Reading, now: Long): ValueStyle {
    val stale = r.stale(now)
    val rc = Range.color(r.mgdl)
    val color = when {
        stale -> GlanceTheme.colors.outline
        rc != null -> ColorProvider(Color(rc))
        else -> GlanceTheme.colors.onSurface
    }
    return ValueStyle(color, if (stale) TextDecoration.LineThrough else TextDecoration.None, stale)
}

@Composable
private fun ValueRow(r: Reading, now: Long, fontSize: TextUnit, arrowDp: Int) {
    val s = styleFor(r, now)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${r.mgdl}",
            style = TextStyle(color = s.color, fontSize = fontSize, fontWeight = FontWeight.Bold,
                textDecoration = s.deco),
            maxLines = 1,
        )
        Spacer(GlanceModifier.width(2.dp))
        Image(
            ImageProvider(Trend.drawable(r.rate)),
            contentDescription = Trend.word(r.rate),
            modifier = GlanceModifier.size(arrowDp.dp),
            colorFilter = ColorFilter.tint(s.color),
        )
    }
}

@Composable
private fun Secondary(text: String, size: TextUnit = 12.sp) = Text(
    text, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size), maxLines = 1,
)

private fun detail(r: Reading) = r.rate?.let { "%+.1f/min".format(it) } ?: "no trend"

@Composable
private fun NoData() = Secondary("No data")

@Composable
fun ValueContent(frame: WidgetUpdater.Frame) {
    val r = frame.history.lastOrNull() ?: return NoData()
    val now = frame.nowSec
    val size = LocalSize.current
    val age = ageText(r.ageSec(now))
    when {
        size.width < ValueWidget.WIDE.width -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ValueRow(r, now, 22.sp, 16)
            Secondary(age.removeSuffix(" ago"), 11.sp)
        }
        size.height < ValueWidget.LARGE.height -> Row(verticalAlignment = Alignment.CenterVertically) {
            ValueRow(r, now, 34.sp, 26)
            Spacer(GlanceModifier.width(8.dp))
            Column { Secondary(age); Secondary(detail(r)) }
        }
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            ValueRow(r, now, 48.sp, 36)
            Secondary("mg/dL · ${Trend.word(r.rate)}")
            Secondary(age)
        }
    }
}

@Composable
private fun Graph(frame: WidgetUpdater.Frame, widthDp: Float, heightDp: Float) {
    val ctx = LocalContext.current
    val hours = GraphRenderer.hoursFor(widthDp)
    val bmp = GraphRenderer.render(ctx, frame.history, frame.nowSec,
        GraphRenderer.px(ctx, widthDp), GraphRenderer.px(ctx, heightDp), hours)
    Image(
        ImageProvider(bmp),
        contentDescription = "Glucose graph, last $hours hours",
        modifier = GlanceModifier.width(widthDp.dp).height(heightDp.dp),
        contentScale = ContentScale.FillBounds,
    )
}

@Composable
fun GraphContent(frame: WidgetUpdater.Frame) {
    val size = LocalSize.current
    Graph(frame, size.width.value - 16, size.height.value - 16)
}

@Composable
fun CombinedContent(frame: WidgetUpdater.Frame) {
    val r = frame.history.lastOrNull() ?: return NoData()
    val now = frame.nowSec
    val size = LocalSize.current
    val w = size.width.value - 20
    val h = size.height.value - 20
    if (h >= 120) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                ValueRow(r, now, 36.sp, 28)
                Spacer(GlanceModifier.defaultWeight())
                Column(horizontalAlignment = Alignment.End) {
                    Secondary(ageText(r.ageSec(now)))
                    Secondary(detail(r))
                }
            }
            Spacer(GlanceModifier.height(4.dp))
            Graph(frame, w, h - 52)
        }
    } else {
        Row(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ValueRow(r, now, 30.sp, 22)
                Secondary(ageText(r.ageSec(now)), 11.sp)
            }
            Graph(frame, w - 96, h)
        }
    }
}

/** Widget-picker preview data: the synthetic curve (in range, slightly rising). */
fun sampleFrame(): WidgetUpdater.Frame {
    val now = nowSec() / 60 * 60
    val phase = Math.floorMod(170 - now / 60, 180L).toInt() // same picture whenever it's generated: ~110, rising
    return WidgetUpdater.Frame((360 downTo 0).map { sample(now - it * 60L, phase) }, now)
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
