// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One number format of contracts/settings/settings.json (`numbers`), the range a decoded alert setting is coerced into.
 * Pure copy of the schema's values so this JVM module needs no Android settings code; `AlertSettingsCodecTest.limitsMatchTheSettingsSchema` fails when they
 * drift from the schema. [specials]: values outside the range that mean something (0 = once / until turned off).
 */
data class NumberLimit(
    val min: Double,
    val max: Double,
    val step: Double,
    val default: Double,
    val presets: List<Double>,
    val specials: Set<Double> = emptySet(),
) {
    val presetMinutes: List<Int> get() = presets.map { it.toInt() }

    fun valid(v: Double): Boolean = v in specials ||
        (v in min..max && abs((v - min) / step - ((v - min) / step).roundToLong()) < 1e-6)

    /** [v] if valid, else the nearest value on the step inside the range (NaN → default), like the settings UI. */
    fun coerce(v: Double): Double = when {
        valid(v) -> v
        v.isNaN() -> default
        else -> (min + ((v.coerceIn(min, max) - min) / step).roundToLong() * step).coerceIn(min, max)
    }

    fun coerce(v: Int): Int = coerce(v.toDouble()).roundToLong().toInt()
}

/** The alert settings' number formats (contracts/settings/settings.json `numbers`). */
object AlertLimits {
    val THRESHOLD_VERY_LOW = NumberLimit(40.0, 80.0, 1.0, 55.0, listOf(50.0, 54.0, 55.0, 60.0, 65.0))
    val THRESHOLD_LOW = NumberLimit(50.0, 120.0, 1.0, 70.0, listOf(60.0, 65.0, 70.0, 75.0, 80.0))
    val THRESHOLD_HIGH = NumberLimit(120.0, 300.0, 1.0, 180.0, listOf(160.0, 170.0, 180.0, 185.0, 200.0))
    val THRESHOLD_VERY_HIGH = NumberLimit(150.0, 400.0, 1.0, 250.0, listOf(200.0, 220.0, 250.0, 270.0, 300.0))
    val THRESHOLD_EXTREMELY_HIGH = NumberLimit(200.0, 450.0, 1.0, 300.0, listOf(220.0, 250.0, 270.0, 300.0, 350.0))
    val THRESHOLD_RATE = NumberLimit(0.5, 5.0, 0.1, 2.0, listOf(1.0, 1.5, 2.0, 2.5, 3.0))
    val THRESHOLD_NO_DATA = NumberLimit(5.0, 240.0, 1.0, 20.0, listOf(10.0, 15.0, 20.0, 30.0, 60.0))
    val ALERT_REPEAT = NumberLimit(1.0, 240.0, 1.0, 5.0, listOf(0.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0), setOf(0.0))
    val MUTE_INSULIN = NumberLimit(10.0, 360.0, 1.0, 60.0, listOf(30.0, 45.0, 60.0, 90.0, 120.0, 180.0))
    val MUTE_FALLING = NumberLimit(5.0, 120.0, 1.0, 20.0, listOf(10.0, 15.0, 20.0, 30.0, 45.0, 60.0))
    val MUTE_CARBS = NumberLimit(5.0, 120.0, 1.0, 15.0, listOf(10.0, 15.0, 20.0, 30.0, 45.0))
    val ACTION_MUTE = NumberLimit(1.0, 720.0, 1.0, 30.0, listOf(15.0, 30.0, 60.0, 120.0))
    val ACTION_RELAX = NumberLimit(5.0, 120.0, 1.0, 30.0, listOf(15.0, 20.0, 30.0, 45.0, 60.0, 90.0, 120.0))
    val BACK_MGDL = NumberLimit(0.0, 50.0, 1.0, 10.0, listOf(0.0, 5.0, 10.0, 15.0, 20.0))
    val BACK_MIN = NumberLimit(0.0, 60.0, 1.0, 10.0, listOf(0.0, 5.0, 10.0, 15.0, 30.0))

    /** *Mute all*: a custom length's minutes; preset 0 = until turned off. */
    val MUTE_ALL = NumberLimit(5.0, 1440.0, 1.0, 60.0, listOf(30.0, 60.0, 120.0, 240.0, 480.0, 0.0), setOf(0.0))

    /** The schema's name of each format above (for the drift test). */
    val BY_SCHEMA_NAME: Map<String, NumberLimit> = mapOf(
        "thresholdVeryLow" to THRESHOLD_VERY_LOW, "thresholdLow" to THRESHOLD_LOW, "thresholdHigh" to THRESHOLD_HIGH,
        "thresholdVeryHigh" to THRESHOLD_VERY_HIGH, "thresholdExtremelyHigh" to THRESHOLD_EXTREMELY_HIGH,
        "thresholdRate" to THRESHOLD_RATE, "thresholdNoData" to THRESHOLD_NO_DATA, "alertRepeat" to ALERT_REPEAT,
        "muteInsulin" to MUTE_INSULIN, "muteFalling" to MUTE_FALLING, "muteCarbs" to MUTE_CARBS,
        "actionMute" to ACTION_MUTE, "actionRelax" to ACTION_RELAX, "backMgdl" to BACK_MGDL, "backMin" to BACK_MIN,
        "muteAll" to MUTE_ALL,
    )

    fun threshold(kind: AlertKind): NumberLimit = when (kind) {
        AlertKind.VERY_LOW -> THRESHOLD_VERY_LOW
        AlertKind.LOW -> THRESHOLD_LOW
        AlertKind.HIGH -> THRESHOLD_HIGH
        AlertKind.VERY_HIGH -> THRESHOLD_VERY_HIGH
        AlertKind.EXTREMELY_HIGH -> THRESHOLD_EXTREMELY_HIGH
        AlertKind.FALLING_FAST, AlertKind.RISING_FAST -> THRESHOLD_RATE
        AlertKind.NO_DATA -> THRESHOLD_NO_DATA
    }
}
