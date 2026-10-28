// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.TirRange
import app.mauri.sukrara.settings.SettingsSpec

/** Where a chart is shown; each place can follow the default window or have its own. */
enum class GraphPlace { APP, NOTIFICATION, WIDGETS }

/**
 * Everything the graph and slot settings decide (contracts/settings/settings.json `graph.*`), with the schema's
 * defaults. The settings repository stores each field under its own schema key and builds this; the renderer only
 * reads it ([rings], [graphStyle], [lastElseRing]).
 */
data class GraphConfig(
    val bands: Bands = Bands(),
    val defaultHours: Int = SettingsSpec.GRAPH_HOURS.default.toInt(),
    /** Per place; absent = [defaultHours]. */
    val placeHours: Map<GraphPlace, Int> = emptyMap(),
    /** How many slots show (0–3) of [slots], main first. */
    val ringCount: Int = SettingsSpec.RING_COUNT.default.toInt(),
    val slots: List<RingSpec> = DEFAULT_SLOTS,
    val ringRange: TirRange = TirRange.IN_RANGE,
    val ringOrder: List<Band> = RingSpec.DEFAULT_RING_ORDER,
    /** The distribution and % column beside the chart. */
    val side: Boolean = true,
    val columnCrown: Boolean = true,
    /** The rings' rewards: laurel from 90 %, flowers from 95 %, crown at 100 % ([TirMark]). */
    val ringMarks: Boolean = true,
    val lines: List<Int> = DEFAULT_LINES,
    val gridHours: Int = SettingsSpec.GRID_HOURS.default.toInt(),
    val lastPlace: LastPlace = LastPlace.NEAR_VALUE,
    /** What the last-treatment slot shows while there's no recent treatment (null = nothing). */
    val lastElse: RingSpec? = DEFAULT_LAST_ELSE,
    /** Places whose card leaves out the sensor's time left. */
    val sensorHidden: Set<GraphPlace> = emptySet(),
    val marks: MarkShow = MarkShow.DEFAULT,
) {
    fun sensorTime(p: GraphPlace) = p !in sensorHidden

    /** The place's window; 0 in the schema means "same as the default". */
    fun hours(p: GraphPlace) = placeHours[p]?.takeIf { it > 0 } ?: defaultHours

    /** [lastElse] with the ring settings applied. */
    fun lastElseRing() = lastElse?.copy(range = ringRange, order = ringOrder, marks = ringMarks)

    fun rings(
        count: Int = ringCount,
        range: TirRange = ringRange,
    ) = slots.take(count.coerceIn(0, 3)).map { it.copy(range = range, order = ringOrder, marks = ringMarks) }

    fun graphStyle(hours: Int) =
        GraphStyle(
            windowS = hours * 3600L,
            bands = bands,
            side = side,
            lines = lines,
            gridHours = gridHours,
            crown = columnCrown,
            marks = marks,
        )

    /** The app's home panel: the APP place's window and sensor time, the rings and last-treatment slot. */
    fun appPanelStyle() =
        CardStyle(
            graph = graphStyle(hours(GraphPlace.APP)),
            rings = rings(),
            last = lastPlace,
            lastElse = lastElseRing(),
            sensorTime = sensorTime(GraphPlace.APP),
            bands = bands,
        )

    companion object {
        /** The chart's extra lines: 100 mg/dL unless the user changes them. */
        val DEFAULT_LINES = listOf(SettingsSpec.LINE.default.toInt())

        /** Today and the last week as time in range; the third slot (off by default) the 14-day GMI. */
        val DEFAULT_SLOTS =
            listOf(RingSpec(86_400L), RingSpec(7 * 86_400L), RingSpec(14 * 86_400L, kind = SlotKind.GMI))

        /** The last-treatment slot's fallback: time in range over 30 days, faded like a second slot. */
        val DEFAULT_LAST_ELSE = RingSpec(30 * 86_400L)
    }
}
