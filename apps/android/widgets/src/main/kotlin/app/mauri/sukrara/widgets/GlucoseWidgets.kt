// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.widgets

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import app.mauri.sukrara.core.model.Role
import app.mauri.sukrara.core.model.SensorState
import app.mauri.sukrara.graph.A11y
import app.mauri.sukrara.graph.CardModel
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardStyle
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.CardWords
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.graph.SensorBadge
import app.mauri.sukrara.graph.cardModelOf
import app.mauri.sukrara.graph.widgetCardStyle
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * The three home-screen widgets: registered card surfaces (contracts/graph/surfaces/surfaces.json) that draw the one
 * card ([GlucoseCard]) into a bitmap at each placement's real size ([SizeMode.Exact] → [LocalSize]). A widget only
 * chooses its size, its place's settings ([widgetCardStyle] with the placement's overrides) and the part; its words
 * for TalkBack come from the renderer ([describeCard]).
 */

/** The widget kinds and their registered card surfaces. */
enum class WidgetKind(
    val surface: CardSurface,
) {
    VALUE(CardSurface.WIDGET_VALUE),
    GRAPH(CardSurface.WIDGET_GRAPH),
    COMBINED(CardSurface.WIDGET_COMBINED),
    ;

    fun widget(): GlanceAppWidget =
        when (this) {
            VALUE -> ValueWidget()
            GRAPH -> GraphWidget()
            COMBINED -> CombinedWidget()
        }
}

/** The margin inside the widget around the card: a ring's laurel or crown shows there instead of being cut. */
private const val MARGIN_DP = 10f

/**
 * The share of the RemoteViews bitmap limit (screen width × height × 4 × 1.5 bytes) one picture may use: Glance keeps
 * one bitmap per reported size (portrait + landscape), so each stays under a third with room to spare.
 */
private const val SHARE_OF_LIMIT = 1.5f / 3f

/** The card's model for [frame] (the phone's language). */
fun widgetModel(
    context: Context,
    frame: WidgetFrame,
): CardModel = cardModelOf(frame.glucose, frame.treatments, frame.now, CardWords.of(context.resources), frame.slots)

/** The card style of [kind] for the placement with [overrides]. */
fun widgetStyle(
    kind: WidgetKind,
    frame: WidgetFrame,
    overrides: WidgetOverrides,
): CardStyle = widgetCardStyle(kind.surface, frame.config, overrides.toPlace())

/**
 * A widget's picture, the one entry every home-screen widget draws through: the card for [kind] at [wDp] × [hDp] dp of
 * widget (margins included), drawn at the screen's density unless that would pass [SHARE_OF_LIMIT] of the limit.
 */
fun renderWidget(
    context: Context,
    kind: WidgetKind,
    wDp: Float,
    hDp: Float,
    frame: WidgetFrame,
    overrides: WidgetOverrides,
): Bitmap {
    RoundedFont.init(context)
    val metrics = context.resources.displayMetrics
    val density = metrics.density
    val maxPixels = metrics.widthPixels.toFloat() * metrics.heightPixels * SHARE_OF_LIMIT
    val d = density * min(1f, sqrt(maxPixels / (wDp * density * hDp * density)))
    val pad = MARGIN_DP * d
    val bmp =
        Bitmap.createBitmap(
            (wDp * d).roundToInt().coerceAtLeast(16),
            (hDp * d).roundToInt().coerceAtLeast(16),
            Bitmap.Config.ARGB_8888,
        )
    val canvas = Canvas(bmp).apply { translate(pad, pad) }
    val w = (bmp.width - 2 * pad).coerceAtLeast(1f)
    val h = (bmp.height - 2 * pad).coerceAtLeast(1f)
    val model = widgetModel(context, frame)
    val style = widgetStyle(kind, frame, overrides)
    GlucoseCard.draw(canvas, w, h, model, style, GraphPalette.of(frame.dark), d, kind.surface, CardPart.ALL)
    return bmp
}

/** The renderer's decision about the value a widget shows (the description and the tap follow it). */
fun widgetLook(
    model: CardModel,
): GlucoseLook {
    val latest = model.latest
    val sensor = SensorBadge.look(model.sensor, model.nowS, latest?.t, model.words)
    return GlucoseCard.face(latest, model.nowS, sensor, CardSurface.WIDGET_DESCRIPTION)
}

/**
 * What a widget says to TalkBack: the value sentence (value, trend, range, age; or why there's none) and, with a chart,
 * its summary. A follower's widget starts with the wearer's name.
 */
fun describeCard(
    context: Context,
    kind: WidgetKind,
    frame: WidgetFrame,
    overrides: WidgetOverrides,
): String {
    val model = widgetModel(context, frame)
    val style = widgetStyle(kind, frame, overrides)
    val value = A11y.value(model, style.bands, CardSurface.WIDGET_DESCRIPTION, badge = style.value && style.badge)
    val sentence = listOfNotNull(value, style.graph?.let { A11y.chart(model, it) }).joinToString(". ")
    val name =
        frame.glucose.link
            ?.wearerName
            ?.takeIf { frame.glucose.role == Role.Follower && it.isNotBlank() }
    return if (name != null) context.getString(R.string.widget_desc_follower, name, sentence) else sentence
}

/** What a tap on the widget for [frame] does ([WidgetTap]). */
fun widgetTap(
    context: Context,
    frame: WidgetFrame,
): WidgetTap {
    val model = widgetModel(context, frame)
    return WidgetTap.of(
        role = frame.glucose.role,
        look = widgetLook(model),
        mgdl = model.latest?.sgv,
        lowLimit = frame.config.bands.low,
        firingKinds = frame.glucose.alerts.firingKinds,
        sensorEnded = frame.glucose.sensor == SensorState.Ended,
    )
}

/** One widget kind as a Glance widget: composes the updater's frame at the placement's size. */
abstract class CardWidget(
    private val kind: WidgetKind,
) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val host = context.widgetsHost
        host.awaitReady()
        val updater = host.widgets.updater
        val first = updater.current()
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val frame by updater.frame.collectAsState()
            WidgetContent(kind, frame ?: first, appWidgetId)
        }
    }

    /**
     * A placement was removed: forget its settings and recheck the home banner. Glance runs this inside the
     * receiver's own `goAsync`, so the process stays up until it's done (the receiver must not call `goAsync` again).
     */
    override suspend fun onDelete(
        context: Context,
        glanceId: GlanceId,
    ) {
        val host = context.widgetsHost
        host.awaitReady()
        host.widgets.forget(GlanceAppWidgetManager(context).getAppWidgetId(glanceId))
    }
}

@Composable
private fun WidgetContent(
    kind: WidgetKind,
    frame: WidgetFrame,
    appWidgetId: Int,
) {
    val context = LocalContext.current
    val size: DpSize = LocalSize.current
    val overrides = frame.placements[appWidgetId]
    val bitmap = remember(frame, size, overrides) { renderWidget(context, kind, size.width.value, size.height.value, frame, overrides) }
    val description = remember(frame, overrides) { describeCard(context, kind, frame, overrides) }
    val tap = remember(frame) { WidgetTap.intent(context, widgetTap(context, frame)) }
    Box(
        GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(Color(GraphPalette.of(frame.dark).bg))
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .clickable(actionStartActivity(tap)),
    ) {
        Image(
            ImageProvider(bitmap),
            contentDescription = description,
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.FillBounds,
        )
    }
}

class ValueWidget : CardWidget(WidgetKind.VALUE)

class GraphWidget : CardWidget(WidgetKind.GRAPH)

class CombinedWidget : CardWidget(WidgetKind.COMBINED)

/** A widget kind's receiver: tells the home banner what's placed (a removed placement: [CardWidget.onDelete]). */
abstract class CardWidgetReceiver(
    kind: WidgetKind,
) : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = kind.widget()

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        context.widgetsHost.widgets.presence
            .refresh()
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        context.widgetsHost.widgets.presence
            .refresh()
    }
}

class ValueWidgetReceiver : CardWidgetReceiver(WidgetKind.VALUE)

class GraphWidgetReceiver : CardWidgetReceiver(WidgetKind.GRAPH)

class CombinedWidgetReceiver : CardWidgetReceiver(WidgetKind.COMBINED)
