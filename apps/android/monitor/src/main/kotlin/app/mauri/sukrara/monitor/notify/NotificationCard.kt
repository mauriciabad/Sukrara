// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.monitor.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import app.mauri.sukrara.core.model.GlucoseState
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.graph.CardModel
import app.mauri.sukrara.graph.CardPart
import app.mauri.sukrara.graph.CardSurface
import app.mauri.sukrara.graph.GlucoseCard
import app.mauri.sukrara.graph.GlucoseLook
import app.mauri.sukrara.graph.GraphConfig
import app.mauri.sukrara.graph.GraphPalette
import app.mauri.sukrara.graph.RoundedFont
import app.mauri.sukrara.graph.notificationCardStyle
import app.mauri.sukrara.graph.stats.MetricsCache
import app.mauri.sukrara.graph.stats.toPoint
import java.time.Instant
import kotlin.math.min

/**
 * What the custom card draws besides the content: the hub state, the treatments, the graph settings, the theme, and the
 * stored days before the live window ([stored], for the slots' 7–30-day windows).
 */
data class CardFrame(
    val state: GlucoseState,
    val treatments: List<Treatment>,
    val config: GraphConfig,
    val dark: Boolean,
    val stored: List<Reading> = emptyList(),
) {
    /** The stored days as points, mapped once per frame (they change hourly; the live window is joined per call). */
    private val storedPoints by lazy { stored.map { it.toPoint() } }

    /** The slots' numbers over the stored days and the live window, as home and the widgets count them. */
    fun slots(now: Instant): MetricsCache {
        val start = state.recent.firstOrNull()?.t
        val older = if (start == null) storedPoints else storedPoints.filter { it.t < start.epochSecond }
        return MetricsCache(older + state.recent.map { it.toPoint() }, now.epochSecond)
    }
}

/** The custom card's pictures: the header row expanded and collapsed, and the chart on its rounded surface. */
class NotificationArt(
    val header: Bitmap,
    val collapsed: Bitmap,
    val graph: Bitmap?,
)

/**
 * The glucose notification's custom card (surface `notification`), shown when the Live Update chip is off: the card's
 * header row as the renderer draws it on every card ([CardPart.HEADER]: value, trend or "no trend" ring, the late
 * reading's time, the status pill, the slots on discs) on the range colour, and the chart ([CardPart.GRAPH]) below on
 * a rounded surface of the theme. Android 12+ clips custom content right of the icon column, so the pictures fill the
 * content column. Drawn at most at 2× density so the three pictures stay far below the binder's parcel limit.
 */
object NotificationCard {
    const val HEADER_DP = 64f

    /** Android 12+ gives a collapsed custom view at most 48 dp. */
    const val COLLAPSED_DP = 48f
    const val GRAPH_DP = 96f
    private const val MAX_DENSITY = 2f

    /** A struck-through value (5–20 min old) colours the card grey instead of its range. */
    const val STALE_BG = 0xFF8E9099.toInt()

    /** The card's colour for [look] of [mgdl]: its range's (the light palette: it doesn't follow the theme), grey when struck. */
    fun background(
        look: GlucoseLook,
        mgdl: Int,
        config: GraphConfig,
    ): Int = if (look.strike) STALE_BG else GraphPalette.LIGHT.at(config.bands, mgdl)

    /** Black or white, whichever reads better on [bg] (the system does the same for the header). */
    fun ink(bg: Int): Int {
        val opaque = bg or 0xFF000000.toInt()
        return if (ColorUtils.calculateContrast(Color.BLACK, opaque) >=
            ColorUtils.calculateContrast(Color.WHITE, opaque)
        ) {
            Color.BLACK
        } else {
            Color.WHITE
        }
    }

    /** The content column's width (dp) on this screen: the shade's 16 dp sides, the 72 dp icon column, a 16 dp end margin. */
    fun columnDp(context: Context): Float {
        val dm = context.resources.displayMetrics
        return min(dm.widthPixels, dm.heightPixels) / dm.density - 32f - 72f - 16f
    }

    /**
     * The card for [model] at [wDp] wide on [bg]; [graph] false skips the chart (reused from before). One whole-card
     * style for all parts ([notificationCardStyle]), so the whole-card decisions are made once.
     */
    fun renderNotificationArt(
        context: Context,
        model: CardModel,
        config: GraphConfig,
        bg: Int,
        dark: Boolean,
        wDp: Float,
        graph: Boolean = true,
    ): NotificationArt {
        RoundedFont.init(context)
        val d = min(context.resources.displayMetrics.density, MAX_DENSITY)
        val surface =
            context.getColor(
                if (dark) android.R.color.system_surface_container_high_dark else android.R.color.system_surface_container_high_light,
            )
        val disc = context.getColor(android.R.color.system_surface_container_high_light)
        val style = notificationCardStyle(config, valueInk = ink(bg), slotDisc = disc)

        fun header(hDp: Float) =
            createBitmap((wDp * d).toInt(), (hDp * d).toInt()).also {
                GlucoseCard.draw(
                    Canvas(it),
                    it.width.toFloat(),
                    it.height.toFloat(),
                    model,
                    style,
                    GraphPalette.LIGHT,
                    d,
                    CardSurface.NOTIFICATION,
                    CardPart.HEADER,
                )
            }

        val chart =
            if (!graph || style.graph == null) {
                null
            } else {
                val w = (wDp * d).toInt()
                val h = (GRAPH_DP * d).toInt()
                val pad = 6 * d
                val plot = createBitmap((w - 2 * pad).toInt(), (h - 2 * pad).toInt())
                GlucoseCard.draw(
                    Canvas(plot),
                    plot.width.toFloat(),
                    plot.height.toFloat(),
                    model,
                    style,
                    GraphPalette.of(dark),
                    d,
                    CardSurface.NOTIFICATION,
                    CardPart.GRAPH,
                )
                createBitmap(w, h).also { out ->
                    Canvas(out).apply {
                        drawRoundRect(
                            RectF(0f, 0f, w.toFloat(), h.toFloat()),
                            12 * d,
                            12 * d,
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color =
                                    surface
                            },
                        )
                        drawBitmap(plot, pad, pad, null)
                    }
                    plot.recycle()
                }
            }
        return NotificationArt(header(HEADER_DP), header(COLLAPSED_DP), chart)
    }
}
