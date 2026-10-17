// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.mauri.sukrara.core.Range
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.core.ageS
import app.mauri.sukrara.core.ageText
import app.mauri.sukrara.core.stale
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

/**
 * Investigation 17: the widget for the empty lower part of the lock screen. Not offered by launchers (category
 * `keyguard` only): Sukrara's LSPosed module hosts it inside SystemUI (hook/LockscreenWidgetHost), sized to the space
 * between the notifications and the shortcuts, and tells it where the lock icon is ([HOLE_LEFT], [HOLE_RIGHT],
 * [HOLE_BOTTOM] in the widget options, dp from the widget's top-left). The value sits left of the lock icon, age and
 * rate right of it, the graph below. No background (it floats on the wallpaper like the clock), always the dark
 * palette (lock screen text is light), and a tap opens the treatment dialog, which shows over the lock screen.
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

private val LIGHT = ColorProvider(Color.White)
private val DIM = ColorProvider(Color(0xCCFFFFFF))

@Composable
private fun LockscreenContent(frame: WidgetUpdater.Frame) {
    val size = LocalSize.current
    val opts = LocalAppWidgetOptions.current
    val w = size.width.value
    val holeLeft = opts.getFloat(LockscreenWidget.HOLE_LEFT, w / 2 - 44)
    val holeRight = opts.getFloat(LockscreenWidget.HOLE_RIGHT, w / 2 + 44)
    val rowH = opts.getFloat(LockscreenWidget.HOLE_BOTTOM, 76f)
    val r = frame.history.lastOrNull()
    Box(GlanceModifier.fillMaxSize().clickable(actionStartActivity<app.mauri.sukrara.ui.QuickTreatmentActivity>())) {
        Column(GlanceModifier.fillMaxSize()) {
            Row(GlanceModifier.fillMaxWidth().height(rowH.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.width(holeLeft.dp).fillMaxHeight(), contentAlignment = Alignment.CenterStart) {
                    if (r != null) Value(r, frame.nowSec) else Small("No data")
                }
                Spacer(GlanceModifier.width((holeRight - holeLeft).dp))
                Box(GlanceModifier.defaultWeight().fillMaxHeight(), contentAlignment = Alignment.CenterEnd) {
                    if (r != null) Column(horizontalAlignment = Alignment.End) {
                        Small(ageText(r.ageS(frame.nowSec)))
                        Small(r.rate?.let { "%+.1f/min".format(it) } ?: "no trend")
                    }
                }
            }
            Spacer(GlanceModifier.height(6.dp))
            LockGraph(frame, w, size.height.value - rowH - 6)
        }
    }
}

@Composable
private fun Value(r: Reading, now: Long) {
    val stale = r.stale(now)
    val color = if (stale) DIM else Range.color(r.sgv)?.let { ColorProvider(Color(it)) } ?: LIGHT
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "${r.sgv}",
            style = TextStyle(color = color, fontSize = 50.sp, fontWeight = FontWeight.Medium,
                fontFamily = FontFamily("google-sans-flex"),
                textDecoration = if (stale) TextDecoration.LineThrough else TextDecoration.None),
            maxLines = 1,
        )
        Image(
            ImageProvider(Trend.drawable(r.rate)),
            contentDescription = Trend.word(r.rate),
            modifier = GlanceModifier.size(36.dp),
            colorFilter = ColorFilter.tint(color),
        )
    }
}

@Composable
private fun Small(text: String) = Text(
    text, style = TextStyle(color = DIM, fontSize = 15.sp, textAlign = TextAlign.End), maxLines = 1,
)

/** The widgets' graph, always in the dark palette: lock screen content is light on the wallpaper. */
@Composable
private fun ColumnScope.LockGraph(frame: WidgetUpdater.Frame, widthDp: Float, heightDp: Float) {
    if (heightDp < 24) return
    val ctx = LocalContext.current
    val night = ctx.createConfigurationContext(Configuration(ctx.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or Configuration.UI_MODE_NIGHT_YES
    })
    val hours = GraphRenderer.hoursFor(widthDp)
    val bmp = GraphRenderer.render(night, frame.history, frame.nowSec,
        GraphRenderer.px(ctx, widthDp), GraphRenderer.px(ctx, heightDp), hours, treatments = frame.treatments)
    Image(
        ImageProvider(bmp),
        contentDescription = "Glucose graph, last $hours hours",
        modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
        contentScale = ContentScale.FillBounds,
    )
}
