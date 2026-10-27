// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.stats.TirRange

/**
 * One home-screen widget's own settings (settings.json `widget.overrides`, Reconfigure); null = follow the Widgets
 * place's settings ([GraphConfig] with [GraphPlace.WIDGETS]).
 */
data class PlaceOverrides(
    val hours: Int? = null,
    val rings: Int? = null,
    val ringRange: TirRange? = null,
    val side: Boolean? = null,
    val sensorTime: Boolean? = null,
)

/**
 * The card style of a home-screen widget surface: the ONE mapping from the settings to what each widget kind shows,
 * used by the widgets and by CardLayoutTest. A value-only card still colours by the user's bands.
 */
fun widgetCardStyle(
    surface: CardSurface,
    config: GraphConfig,
    own: PlaceOverrides = PlaceOverrides(),
): CardStyle {
    val graph = config.graphStyle(own.hours ?: config.hours(GraphPlace.WIDGETS)).copy(side = own.side ?: config.side)
    val rings = config.rings(own.rings ?: config.ringCount, own.ringRange ?: config.ringRange)
    val sensorTime = own.sensorTime ?: config.sensorTime(GraphPlace.WIDGETS)
    return when (surface) {
        CardSurface.WIDGET_VALUE -> {
            CardStyle(
                graph = null,
                rings = rings,
                last = config.lastPlace,
                lastElse = config.lastElseRing(),
                sensorTime = sensorTime,
                bands = config.bands,
            )
        }

        CardSurface.WIDGET_GRAPH -> {
            CardStyle(graph = graph, rings = emptyList(), value = false, sensorTime = sensorTime, bands = config.bands)
        }

        CardSurface.WIDGET_COMBINED -> {
            CardStyle(
                graph = graph,
                rings = rings,
                last = config.lastPlace,
                lastElse = config.lastElseRing(),
                sensorTime = sensorTime,
                bands = config.bands,
            )
        }

        else -> {
            throw IllegalArgumentException("$surface is not a home-screen widget")
        }
    }
}
