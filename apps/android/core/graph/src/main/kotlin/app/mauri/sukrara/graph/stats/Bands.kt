// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph.stats

import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.settings.SettingsSpec

/**
 * One reading as the renderer and the statistics use it: epoch seconds, mg/dL, the trend rate (mg/dL/min) or null.
 * A flat primitive record so a chart scrolled over weeks of readings doesn't allocate `Instant`s every frame.
 */
data class GlucosePoint(
    val t: Long,
    val sgv: Int,
    val rate: Double?,
)

fun Reading.toPoint(): GlucosePoint = GlucosePoint(t.epochSecond, mgdl, rateMgdlPerMin?.toDouble())

/**
 * The person's ranges, mg/dL, as in LibreView's report: very low < [veryLow] ≤ low < [low] ≤ in range ≤ [high] < high
 * ≤ [veryHigh] < very high. Inside in range, the "very good" (tight) band [low]..[tight]. [extremelyHigh] (≥) only
 * colours the line and the number: time in range keeps LibreView's five ranges. Defaults from the settings schema.
 */
data class Bands(
    val veryLow: Int = SettingsSpec.BAND_VERY_LOW.default.toInt(),
    val low: Int = SettingsSpec.BAND_LOW.default.toInt(),
    val tight: Int = SettingsSpec.BAND_TIGHT.default.toInt(),
    val high: Int = SettingsSpec.BAND_HIGH.default.toInt(),
    val veryHigh: Int = SettingsSpec.BAND_VERY_HIGH.default.toInt(),
    val extremelyHigh: Int = SettingsSpec.BAND_EXTREMELY_HIGH.default.toInt(),
) {
    fun of(mgdl: Double): Band =
        when {
            mgdl < veryLow -> Band.VERY_LOW
            mgdl < low -> Band.LOW
            mgdl <= tight -> Band.TIGHT
            mgdl <= high -> Band.TARGET
            mgdl <= veryHigh -> Band.HIGH
            else -> Band.VERY_HIGH
        }

    fun of(mgdl: Int) = of(mgdl.toDouble())

    /** Clamped so the order always holds whatever was stored (low < tight ≤ high < very high < extremely high). */
    fun sane(): Bands {
        val s = SettingsSpec
        val l = low.coerceIn(s.BAND_LOW.min.toInt(), s.BAND_LOW.max.toInt())
        val h = high.coerceIn(l + 20, s.BAND_HIGH.max.toInt())
        val vh = veryHigh.coerceIn(h + 10, s.BAND_VERY_HIGH.max.toInt())
        return Bands(
            veryLow.coerceIn(s.BAND_VERY_LOW.min.toInt(), l - 5),
            l,
            tight.coerceIn(l + 10, h),
            h,
            vh,
            extremelyHigh.coerceIn(vh + 10, s.BAND_EXTREMELY_HIGH.max.toInt()),
        )
    }
}

/** [TARGET] = in range above the tight band; in range = [TIGHT] + [TARGET]. */
enum class Band {
    VERY_LOW,
    LOW,
    TIGHT,
    TARGET,
    HIGH,
    VERY_HIGH,
    ;

    /** -1 below range, 0 in range, 1 above. */
    val group: Int
        get() =
            when (this) {
                VERY_LOW, LOW -> -1
                TIGHT, TARGET -> 0
                HIGH, VERY_HIGH -> 1
            }
}

/** Which band a time-in-range number counts. */
enum class TirRange { IN_RANGE, TIGHT }

/**
 * Share of time per band in a window. Every reading stands for the time until the next one (capped at
 * [GlucoseStats.MAX_GAP_S]), so 1-minute live and 5/15-minute backfilled readings count the same per hour and a gap
 * counts as nothing. [coverage] = how much of the window that accounts for.
 */
data class TimeInRange(
    val veryLow: Double,
    val low: Double,
    val tight: Double,
    val target: Double,
    val high: Double,
    val veryHigh: Double,
    val coverage: Double,
) {
    val empty: Boolean get() = coverage <= 0.0
    val below: Double get() = veryLow + low
    val above: Double get() = high + veryHigh

    /** Exactly 1 when nothing is below or above (the sum of two shares can miss it; 100 % earns the crown). */
    val inRange: Double get() = if (below <= 0.0 && above <= 0.0 && !empty) 1.0 else tight + target

    fun of(r: TirRange): Double =
        when {
            r == TirRange.IN_RANGE -> inRange
            below + above + target <= 0.0 && !empty -> 1.0
            else -> tight
        }

    fun share(b: Band): Double =
        when (b) {
            Band.VERY_LOW -> veryLow
            Band.LOW -> low
            Band.TIGHT -> tight
            Band.TARGET -> target
            Band.HIGH -> high
            Band.VERY_HIGH -> veryHigh
        }

    companion object {
        val NONE = TimeInRange(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
    }
}
