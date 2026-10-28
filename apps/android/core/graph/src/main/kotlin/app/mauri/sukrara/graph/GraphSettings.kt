// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.data.settings.Setting
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.TirRange
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * THE source of [GraphConfig]: the `graph.*` settings of the schema, read by schema id (store and key from the
 * generated `SettingsSpec`). Home, widgets and every other card surface draw from [config]; the settings pages write
 * these same [Setting]s. The structured ones are stored in their wire text form ([GraphText]); a stored value that
 * doesn't parse reads as the default.
 */
object GraphSettings {
    /** The six ranges, lowest first (veryLow, low, tight, high, veryHigh, extremelyHigh). */
    val Bands: List<Setting<Double>> =
        listOf("veryLow", "low", "tight", "high", "veryHigh", "extremelyHigh").map { SchemaSettings.number("graph.band.$it") }

    val Hours = SchemaSettings.number("graph.hours")
    val GridHours = SchemaSettings.number("graph.gridHours")
    val RingCount = SchemaSettings.number("graph.ringCount")
    val Side = SchemaSettings.bool("graph.side", default = GraphConfig().side)
    val ColumnCrown = SchemaSettings.bool("graph.columnCrown", default = GraphConfig().columnCrown)
    val RingMarks = SchemaSettings.bool("graph.ringMarks", default = GraphConfig().ringMarks)
    val RingRange = SchemaSettings.text("graph.ringRange", GraphConfig().ringRange.name, TirRange.entries.map { it.name }.toSet())
    val LastPlace =
        SchemaSettings.text(
            "graph.lastPlace",
            GraphConfig().lastPlace.name,
            app.mauri.sukrara.graph.LastPlace.entries
                .map { it.name }
                .toSet(),
        )

    /** The three slots beside the value (what, period, fainter), [GraphText.slotsText]. */
    val Slots = SchemaSettings.text("graph.slots", GraphText.slotsText(GraphConfig.DEFAULT_SLOTS))

    /** The ring's segments clockwise from 12 o'clock, [GraphText.ringOrderText]. */
    val RingOrder = SchemaSettings.text("graph.ringOrder", GraphText.ringOrderText(RingSpec.DEFAULT_RING_ORDER))

    /** The extra lines across the chart (mg/dL), [GraphText.linesText]. */
    val Lines = SchemaSettings.text("graph.lines", GraphText.linesText(GraphConfig.DEFAULT_LINES))

    /** The last-treatment slot's fallback, one slot or "" = nothing. */
    val LastElse = SchemaSettings.text("graph.lastElse", GraphText.slotText(GraphConfig.DEFAULT_LAST_ELSE))

    /** Treatments on the graph, [MarkShow.text]. */
    val Marks = SchemaSettings.text("graph.marks", MarkShow.DEFAULT.text)

    /** Each place's own window in hours (0 = the default's). */
    val PlaceHours: Map<GraphPlace, Setting<Double>> =
        GraphPlace.entries.associateWith { SchemaSettings.numberAt("graph.hours.place", it.ordinal) }

    /** Whether each place's card shows the sensor's time left. */
    val SensorTime: Map<GraphPlace, Setting<Boolean>> =
        GraphPlace.entries.associateWith { SchemaSettings.boolAt("graph.sensorTime.place", it.ordinal, default = true) }

    /** The person's graph settings, a new [GraphConfig] on every change (ranges always in order: [Bands.sane]). */
    fun config(settings: SettingsRepository): Flow<GraphConfig> {
        val bands = combine(Bands.map(settings::flow)) { v -> bandsOf(v.map(Double::toInt)) }
        val numbers =
            combine(settings.flow(Hours), settings.flow(GridHours), settings.flow(RingCount)) { h, g, r ->
                Triple(h.toInt(), g.toInt(), r.toInt())
            }
        val switches =
            combine(settings.flow(Side), settings.flow(ColumnCrown), settings.flow(RingMarks)) { side, crown, marks ->
                Triple(side, crown, marks)
            }
        val enums = combine(settings.flow(RingRange), settings.flow(LastPlace)) { range, last -> range to last }
        val texts = combine(listOf(Slots, RingOrder, Lines, LastElse, Marks).map(settings::flow)) { it.toList() }
        val hours =
            combine(GraphPlace.entries.map { settings.flow(PlaceHours.getValue(it)) }) { v ->
                GraphPlace.entries
                    .zip(v.map(Double::toInt))
                    .filter { it.second > 0 }
                    .toMap()
            }
        val hidden =
            combine(GraphPlace.entries.map { settings.flow(SensorTime.getValue(it)) }) { v ->
                GraphPlace.entries.filterIndexed { i, _ -> !v[i] }.toSet()
            }
        val places = combine(hours, hidden) { h, s -> h to s }
        return combine(bands, numbers, switches, combine(enums, texts) { e, t -> e to t }, places) {
            b,
            (defaultHours, grid, rings),
            (side, crown, marks),
            (e, t),
            (placeHours, sensorHidden),
            ->
            GraphConfig(
                bands = b,
                defaultHours = defaultHours,
                placeHours = placeHours,
                gridHours = grid,
                ringCount = rings,
                slots = GraphText.parseSlots(t[0]) ?: GraphConfig.DEFAULT_SLOTS,
                ringOrder = GraphText.parseRingOrder(t[1]) ?: RingSpec.DEFAULT_RING_ORDER,
                side = side,
                columnCrown = crown,
                ringMarks = marks,
                lines = GraphText.parseLines(t[2]),
                ringRange = TirRange.valueOf(e.first),
                lastPlace =
                    app.mauri.sukrara.graph.LastPlace
                        .valueOf(e.second),
                lastElse = GraphText.parseSlot(t[3]),
                sensorHidden = sensorHidden,
                marks = MarkShow.parse(t[4]),
            )
        }.distinctUntilChanged()
    }

    private fun bandsOf(v: List<Int>) = Bands(v[0], v[1], v[2], v[3], v[4], v[5]).sane()
}
