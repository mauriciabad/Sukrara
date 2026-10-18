// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Reading
import kotlin.math.sqrt

/**
 * The numbers of the doctor's report (LibreView's AGP page, investigation 18) for one window: time in the five
 * ranges, average glucose, GMI and glucose variability (CV). Every reading is weighted by the time it stands for
 * ([GlucoseStats.weights]), like the rings, so 1- and 5-minute data and gaps count correctly.
 */
data class GlucoseMetrics(
    val tir: TimeInRange,
    /** Time-weighted mean, mg/dL. */
    val mean: Double,
    /** Time-weighted standard deviation, mg/dL. */
    val sd: Double,
    val readings: Int,
) {
    /** Coefficient of variation, % (goal ≤ 36). */
    val cv get() = if (mean > 0) sd / mean * 100 else 0.0
    /** Glucose Management Indicator, % (Bergenstal 2018: 3.31 + 0.02392 × mean mg/dL). */
    val gmi get() = 3.31 + 0.02392 * mean
    /** The same GMI in IFCC units, mmol/mol. */
    val gmiMmolMol get() = (gmi - 2.152) * 10.929
    val empty get() = readings == 0

    companion object {
        fun of(all: List<Reading>, from: Long, to: Long, b: Bands): GlucoseMetrics {
            val rs = GlucoseStats.window(all, from, to)
            val w = GlucoseStats.weights(rs, to)
            val total = w.sum()
            val tir = GlucoseStats.timeInRange(all, from, to, b)
            if (total <= 0) return GlucoseMetrics(tir, 0.0, 0.0, 0)
            val mean = rs.indices.sumOf { rs[it].sgv * w[it] } / total
            val variance = rs.indices.sumOf { val d = rs[it].sgv - mean; d * d * w[it] } / total
            return GlucoseMetrics(tir, mean, sqrt(variance), rs.size)
        }
    }
}

/**
 * The international consensus targets (Battelino et al., Diabetes Care 2019) that LibreView prints as "Goals for
 * type 1 and type 2 diabetes". [met] is how the report and the gauges colour a number.
 */
enum class Goal(val text: String) {
    VERY_HIGH("< 5%"), ABOVE("< 25%"), IN_RANGE("> 70%"), BELOW("< 4%"), VERY_LOW("< 1%"),
    AVERAGE("< 154 mg/dL"), GMI("< 7%"), CV("≤ 36%");

    fun met(m: GlucoseMetrics): Boolean = when (this) {
        VERY_HIGH -> m.tir.veryHigh < 0.05
        ABOVE -> m.tir.above < 0.25
        IN_RANGE -> m.tir.inRange > 0.70
        BELOW -> m.tir.below < 0.04
        VERY_LOW -> m.tir.veryLow < 0.01
        AVERAGE -> m.mean < 154
        GMI -> m.gmi < 7.0
        CV -> m.cv <= 36.0
    }

    /** For the time-in-range goals: whether a share (0..1) meets it; null for the goals that aren't a share. */
    fun metShare(x: Double): Boolean? = when (this) {
        VERY_HIGH -> x < 0.05
        ABOVE -> x < 0.25
        IN_RANGE -> x > 0.70
        BELOW -> x < 0.04
        VERY_LOW -> x < 0.01
        else -> null
    }

    companion object {
        /** The report is meant for 14 days with the sensor active ≥ 70 % of the time. */
        const val MIN_COVERAGE = 0.70
    }
}

/**
 * A time share as the number of a percentage, without the "%" (the callers' layouts differ): "64", "5.2", "70.2".
 * Whole percent from 10 % up; one decimal under 10 % (the low ranges' goals are 1 % and 4 %); and more decimals (up to
 * two) whenever rounding would put the number on the other side of its goal than the value itself, so 5.2 % against
 * "< 5 %" never reads "5" (met) and 69.7 % against the 70 % coverage never reads "70". A non-zero share too small to
 * show is "<0.1". The same rule and cases as the Mac (`ShareText` in SukraraGraph.swift; spikes/graph-fixtures/share).
 */
object ShareText {
    private fun format(x: Double, met: ((Double) -> Boolean)?, locale: java.util.Locale): String {
        fun shown(d: Int): Double { val k = Math.pow(10.0, d + 2.0); return Math.round(x * k) / k }
        var d = if (x < 0.095) 1 else 0
        while (d < 2 && met != null && met(shown(d)) != met(x)) d++
        if (x > 0 && shown(d) == 0.0) return "<" + "%.1f".format(locale, 0.1)
        return "%.${d}f".format(locale, shown(d) * 100)
    }

    fun of(x: Double, goal: Goal? = null, locale: java.util.Locale = java.util.Locale.getDefault()) =
        format(x, goal?.let { g -> { v: Double -> g.metShare(v) ?: true } }, locale)

    /** Coverage against the report's 70 % (the "only … % has data" note). */
    fun coverage(x: Double, locale: java.util.Locale = java.util.Locale.getDefault()) = format(x, { v: Double -> v >= Goal.MIN_COVERAGE }, locale)
}
