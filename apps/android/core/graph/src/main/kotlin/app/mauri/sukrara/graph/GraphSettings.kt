// Sukrara — https://github.com/mauriciabad/Sukrara
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
 * these same [Setting]s.
 *
 * Read here: the keys V1 lets the person change (ranges, time shown, vertical lines, rings, distribution, crown,
 * rewards) plus the two enums (`ringRange`, `lastPlace`). The structured keys (`slots`, `ringOrder`, `marks`, `lines`,
 * `lastElse`, per-place hours / sensor time) have no Android writer yet, so their schema defaults are the person's
 * values; they join here with the page that writes them.
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
    val LastPlace = SchemaSettings.text("graph.lastPlace", GraphConfig().lastPlace.name, app.mauri.sukrara.graph.LastPlace.entries.map { it.name }.toSet())

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
        return combine(bands, numbers, switches, enums) { b, (hours, grid, rings), (side, crown, marks), (range, last) ->
            GraphConfig(
                bands = b,
                defaultHours = hours,
                gridHours = grid,
                ringCount = rings,
                side = side,
                columnCrown = crown,
                ringMarks = marks,
                ringRange = TirRange.valueOf(range),
                lastPlace = app.mauri.sukrara.graph.LastPlace.valueOf(last),
            )
        }.distinctUntilChanged()
    }

    private fun bandsOf(v: List<Int>) = Bands(v[0], v[1], v[2], v[3], v[4], v[5]).sane()
}
