// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.sharing.setup

import app.mauri.sukrara.alerts.AlertKind
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.settings.NumberSpec
import app.mauri.sukrara.settings.SettingsSpec
import kotlinx.coroutines.flow.Flow

/**
 * The values the alert step shows and the person confirms (P4), the same kinds and order as Settings › Review alert
 * thresholds. Levels in mg/dL, no data in minutes.
 */
enum class ThresholdField(
    val kind: AlertKind,
    val spec: NumberSpec,
) {
    VeryLow(AlertKind.VERY_LOW, SettingsSpec.THRESHOLD_VERY_LOW),
    Low(AlertKind.LOW, SettingsSpec.THRESHOLD_LOW),
    High(AlertKind.HIGH, SettingsSpec.THRESHOLD_HIGH),
    VeryHigh(AlertKind.VERY_HIGH, SettingsSpec.THRESHOLD_VERY_HIGH),
    ExtremelyHigh(AlertKind.EXTREMELY_HIGH, SettingsSpec.THRESHOLD_EXTREMELY_HIGH),
    NoData(AlertKind.NO_DATA, SettingsSpec.THRESHOLD_NO_DATA),
}

/** What the alert step edits: a copy of the alert settings; only the confirmed values are written back. */
data class AlertThresholds(val settings: AlertSettings) {
    fun value(field: ThresholdField): Double = settings.rule(field.kind).threshold

    fun isOn(field: ThresholdField): Boolean = settings.rule(field.kind).on

    /** [value] snapped to the field's format (the number dialog only offers valid values; this guards the rest). */
    fun with(
        field: ThresholdField,
        value: Double,
    ): AlertThresholds =
        AlertThresholds(
            settings.withRule(field.kind, settings.rule(field.kind).copy(threshold = field.spec.coerce(value))),
        )

    /** Levels must stay in order (very low < low < high < very high < extremely high), or a low could ring as a high. */
    val ordered: Boolean
        get() = LEVELS.zipWithNext().all { (lower, higher) -> value(lower) < value(higher) }

    private companion object {
        val LEVELS =
            listOf(ThresholdField.VeryLow, ThresholdField.Low, ThresholdField.High, ThresholdField.VeryHigh, ThresholdField.ExtremelyHigh)
    }
}

/**
 * The alert settings the runtime reads (the alert runtime's store, `alerts:alerts`): `AppGraph` passes its flow and writer, so
 * a confirmed threshold is exactly what the next reading is checked against.
 */
interface AlertSettingsAccess {
    val settings: Flow<AlertSettings>

    suspend fun save(settings: AlertSettings)
}
