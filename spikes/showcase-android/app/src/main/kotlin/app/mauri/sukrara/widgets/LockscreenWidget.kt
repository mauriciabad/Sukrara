// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import app.mauri.sukrara.R
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
import app.mauri.sukrara.features.FeatureState
import app.mauri.sukrara.features.Features
import app.mauri.sukrara.features.LockAppearance
import app.mauri.sukrara.features.LockBackground
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.Freshness
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.Slots
import app.mauri.sukrara.graph.appSlots
import app.mauri.sukrara.graph.GraphPlace
import app.mauri.sukrara.graph.GraphSettings
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
 * and arrow left of it, the time-in-range rings right of it, the chart below. The background (frosted glass by
 * default, [LockBackground]) is drawn by the module around it, except the shadow. Dark or light like the home-screen
 * widgets ([LockAppearance], default: follows the dark theme), and a tap opens the treatment dialog, which shows over
 * the lock screen.
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

        /** DebugReceiver `cmd locktest`: draw a square and a circle of the same size to measure distortion on screen. */
        @Volatile var testPattern = false

        /**
         * The ids SystemUI hosts. AppWidgetManager.getAppWidgetIds(provider), which Glance's updateAll uses, doesn't
         * list a widget bound by SystemUI's host, so without this the widget only redrew while a Glance session
         * happened to be alive and went stale after. Learnt from the widget callbacks and from the module's hello.
         */
        private fun prefs(c: Context) = c.getSharedPreferences("lock-widget", Context.MODE_PRIVATE)
        fun ids(c: Context): Set<Int> = prefs(c).getStringSet("ids", emptySet())!!.mapNotNull { it.toIntOrNull() }.toSet()
        fun remember(c: Context, ids: Collection<Int>) {
            if (ids.isEmpty() || ids(c).containsAll(ids)) return
            prefs(c).edit().putStringSet("ids", (ids(c) + ids).map { "$it" }.toSet()).apply()
        }
        fun forget(c: Context, ids: Collection<Int>) =
            prefs(c).edit().putStringSet("ids", (ids(c) - ids.toSet()).map { "$it" }.toSet()).apply()

        suspend fun updateAll(c: Context) {
            val mgr = androidx.glance.appwidget.GlanceAppWidgetManager(c)
            for (id in ids(c)) runCatching { LockscreenWidget().update(c, mgr.getGlanceIdBy(id)) }
                .onFailure { android.util.Log.w("Sukrara.widgets", "lock screen widget $id: $it"); forget(c, listOf(id)) }
        }
    }
}

class LockscreenWidgetReceiver : BaseReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LockscreenWidget()

    override fun onUpdate(context: Context, appWidgetManager: android.appwidget.AppWidgetManager, appWidgetIds: IntArray) {
        LockscreenWidget.remember(context, appWidgetIds.toList())
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: android.appwidget.AppWidgetManager,
            appWidgetId: Int, newOptions: android.os.Bundle) {
        LockscreenWidget.remember(context, listOf(appWidgetId))
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        LockscreenWidget.forget(context, appWidgetIds.toList())
        super.onDeleted(context, appWidgetIds)
    }
}

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
    var bmp = createBitmap((w * d * scale).roundToInt().coerceAtLeast(16), (h * d * scale).roundToInt().coerceAtLeast(16))
    val f = Features.value
    draw(ctx, Canvas(bmp), frame, hole, d * scale, f)
    if (f.lockBg == LockBackground.SHADOW) bmp = withShadow(bmp, d * scale, LockAppearance.dark(f.lockLook, ctx))
    Image(
        ImageProvider(bmp),
        contentDescription = describe(ctx, frame),
        modifier = GlanceModifier.fillMaxSize().clickable(actionStartActivity<app.mauri.sukrara.ui.QuickTreatmentActivity>()),
        contentScale = ContentScale.FillBounds,
    )
}

/** Where the lock icon is, dp from the widget's top-left: [left]…[right] wide, from the top down to [bottom]. */
private data class Hole(val left: Float, val right: Float, val bottom: Float)

private fun draw(ctx: Context, cv: Canvas, frame: WidgetUpdater.Frame, hole: Hole, d: Float, f: FeatureState) {
    val cfg = GraphSettings.value
    // The home widgets' palette in the chosen appearance. Transparent: the end dot's halo is drawn in the background colour.
    val pal = systemPalette(ctx, dark = LockAppearance.dark(f.lockLook, ctx)).copy(bg = 0)
    // Card and frosted glass (drawn by the module behind the widget) have an edge: keep the content off it.
    val pad = if (LockBackground.boxed(f.lockBg)) 12 * d else 0f
    val w = cv.width.toFloat() - pad
    val h = cv.height.toFloat() - pad
    val rowH = hole.bottom * d

    // Value + arrow, left of the lock icon (a little shorter than the row so the number isn't taller than the icon).
    val valueBox = RectF(pad, rowH * 0.1f, hole.left * d, rowH * 0.9f)
    cv.withBox(valueBox) {
        GlucoseCard.draw(cv, valueBox.width(), valueBox.height(), frame.history, frame.treatments, frame.nowSec,
            CardStyle(graph = null, rings = emptyList()), pal, d)
    }

    // The slots (time in range, GMI, average, CV, last treatment: investigations 16 and 18), right of the lock icon,
    // main rightmost, chosen like GlucoseCard's header.
    val ringsBox = RectF(hole.right * d, 0f, w, rowH)
    val gap = 8 * d
    val size = min(rowH * 0.9f - pad, 84 * d)
    if (size >= 30 * d) {
        val fit = ((ringsBox.width() + gap) / (size + gap)).toInt().coerceIn(0, 4)
        val x = ringsBox.right
        val items = appSlots(ctx, frame.history, frame.treatments, frame.nowSec, fit)
        val main = Slots.mainIndex(items)
        val countdown = app.mauri.sukrara.graph.SensorBadge.countdownNow(frame.nowSec)
        val cx = Slots.centers(items, x, size, gap)
        Slots.drawUnder(cv, items, cx, ringsBox.centerY(), size / 2, pal)
        for ((i, item) in items.withIndex())
            Slots.draw(cv, item, cx[i], ringsBox.centerY(), size / 2, frame.nowSec, pal, d, countdown.takeIf { i == main })
    }

    // The chart below.
    val chart = RectF(pad, rowH + 8 * d, w, h)
    if (chart.height() > 24 * d) cv.withBox(chart) {
        val graph = cfg.graphStyle(cfg.hours(GraphPlace.WIDGETS), android.text.format.DateFormat.is24HourFormat(ctx))
        GlucoseCard.draw(cv, chart.width(), chart.height(), frame.history, frame.treatments, frame.nowSec,
            CardStyle(graph = graph, rings = emptyList(), value = false), pal, d)
    }
    if (LockscreenWidget.testPattern) testPattern(cv, d)
}

/**
 * Text protection without a box: a soft shadow around everything drawn (the content's alpha, blurred), like the
 * shadow SystemUI puts under the lock screen clock. Dark behind light content, a white glow behind dark content.
 */
private fun withShadow(src: android.graphics.Bitmap, d: Float, dark: Boolean): android.graphics.Bitmap {
    val off = IntArray(2)
    val mask = src.extractAlpha(android.graphics.Paint().apply {
        maskFilter = android.graphics.BlurMaskFilter(5 * d, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }, off)
    val out = createBitmap(src.width, src.height)
    val cv = Canvas(out)
    val p = android.graphics.Paint().apply { color = if (dark) 0x99000000.toInt() else 0xB3FFFFFF.toInt() }
    // twice: the blur spreads the alpha thin, one pass is too faint behind thin lines
    repeat(2) { cv.drawBitmap(mask, off[0].toFloat(), off[1].toFloat() + 0.5f * d, p) }
    cv.drawBitmap(src, 0f, 0f, null)
    mask.recycle(); src.recycle()
    return out
}

private fun testPattern(cv: Canvas, d: Float) {
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF00FF.toInt() }
    val s = 100 * d
    val y = cv.height - s
    val x = cv.width - s
    cv.drawRect(x, y, x + s, y + s, p)
    cv.drawCircle(x - 12 * d - s / 2, y + s / 2, s / 2, p)
}

private inline fun Canvas.withBox(box: RectF, block: () -> Unit) {
    save(); translate(box.left, box.top); clipRect(0f, 0f, box.width(), box.height())
    try { block() } finally { restore() }
}

private fun describe(c: android.content.Context, frame: WidgetUpdater.Frame): String {
    val r = frame.history.lastOrNull()
    val f = r?.let { Freshness.of(frame.nowSec - it.t) }
    val value = when {
        r == null -> c.getString(R.string.widget_desc_no_readings)
        !f!!.showValue -> c.getString(R.string.widget_desc_no_reading_since, Freshness.timeText(r.t, frame.nowSec))
        else -> c.getString(R.string.widget_desc_value, r.sgv, Trend.word(r.rate))
    }
    return c.getString(R.string.lock_widget_desc, value)
}
