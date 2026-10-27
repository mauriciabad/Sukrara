// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.settings

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.core.data.settings.SchemaSettings
import app.mauri.sukrara.core.data.settings.Setting
import app.mauri.sukrara.core.model.storage.SettingsRepository
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/**
 * The schema settings these pages show, declared by schema id (store and key come from the generated spec, never
 * typed here). Bool defaults match `GraphConfig`'s.
 */
object SchemaPrefs {
    /** The six graph ranges, lowest first; each must stay between its neighbours. */
    val Bands: List<Setting<Double>> =
        listOf("veryLow", "low", "tight", "high", "veryHigh", "extremelyHigh").map { SchemaSettings.number("graph.band.$it") }

    val GraphHours = SchemaSettings.number("graph.hours")
    val GridHours = SchemaSettings.number("graph.gridHours")
    val RingCount = SchemaSettings.number("graph.ringCount")
    val Side = SchemaSettings.bool("graph.side", default = true)
    val ColumnCrown = SchemaSettings.bool("graph.columnCrown", default = true)
    val RingMarks = SchemaSettings.bool("graph.ringMarks", default = true)

    /** When the wearer confirmed the alert thresholds (ISO instant; empty = never: CHANGES P4). */
    val ThresholdsConfirmedAt = SchemaSettings.text("alerts.thresholdsConfirmedAt", default = "")

    /** The number format of a setting by schema id (e.g. `alerts.threshold.low`). */
    fun spec(id: String): NumberSpec {
        val info = SettingsSpec.SETTINGS.first { it.id == id }
        return SettingsSpec.NUMBERS.getValue(checkNotNull(info.number) { "$id has no number format" })
    }

    fun thresholdSpec(kind: AlertKind): NumberSpec = spec("alerts.threshold.${kind.wire}")

    val RepeatSpec: NumberSpec get() = spec("alerts.kind.repeat")

    /** The format of a band, for its NumberDialog. */
    fun bandSpec(setting: Setting<Double>): NumberSpec = spec(setting.id)

    /** Whether (and when) the alert thresholds were confirmed; home's Finish-setup card asks until they are. */
    fun thresholdsConfirmedAt(settings: SettingsRepository): Flow<Instant?> =
        settings.flow(ThresholdsConfirmedAt).map { text -> text.takeIf { it.isNotEmpty() }?.let { runCatching { Instant.parse(it) }.getOrNull() } }
}
