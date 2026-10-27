// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph.stats

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Time in range, the distribution beside the chart and the report numbers, over time-weighted readings. */
object GlucoseStats {
    const val MAX_GAP_S = 15 * 60L

    /** Densities are computed on a 1 mg/dL grid over this range. */
    const val MIN_MG = 20
    const val MAX_MG = 450
    private const val DEDUP_S = 30L

    /**
     * Readings in [from, to), sorted, deduplicated (±30 s, the later wins). A sorted history is cut by binary search,
     * so a scrolled chart over weeks of readings only touches its window each frame.
     */
    fun window(
        all: List<GlucosePoint>,
        from: Long,
        to: Long,
    ): List<GlucosePoint> {
        val src =
            if (all is RandomAccess && isSorted(all)) {
                all.subList(lowerBound(all, from), lowerBound(all, to + DEDUP_S))
            } else {
                all.filter { it.t in from until to + DEDUP_S }.sortedBy { it.t }
            }
        val out = ArrayList<GlucosePoint>(src.size)
        for (r in src) if (out.isNotEmpty() && r.t - out[out.size - 1].t < DEDUP_S) out[out.size - 1] = r else out += r
        return out
    }

    private fun isSorted(all: List<GlucosePoint>): Boolean = (1 until all.size).none { all[it].t < all[it - 1].t }

    /** The first index with t ≥ [t] in a sorted list. */
    private fun lowerBound(
        all: List<GlucosePoint>,
        t: Long,
    ): Int {
        var lo = 0
        var hi = all.size
        while (lo < hi) {
            val m = (lo + hi) ushr 1
            if (all[m].t < t) lo = m + 1 else hi = m
        }
        return lo
    }

    /** Seconds each reading stands for; the last one the spacing just before it, at most until [to]. */
    fun weights(
        rs: List<GlucosePoint>,
        to: Long,
    ): DoubleArray {
        if (rs.isEmpty()) return DoubleArray(0)
        val spacing = if (rs.size > 1) (rs.last().t - rs[rs.lastIndex - 1].t).coerceIn(1, MAX_GAP_S) else 60L
        return DoubleArray(rs.size) { i ->
            if (i < rs.lastIndex) {
                min(rs[i + 1].t - rs[i].t, MAX_GAP_S).toDouble()
            } else {
                min(spacing, max(0L, to - rs[i].t)).toDouble().coerceAtLeast(1.0)
            }
        }
    }

    fun timeInRange(
        all: List<GlucosePoint>,
        from: Long,
        to: Long,
        b: Bands,
    ): TimeInRange {
        val rs = window(all, from, to)
        val w = weights(rs, to)
        val sum = DoubleArray(Band.entries.size)
        rs.forEachIndexed { i, r -> sum[b.of(r.sgv).ordinal] += w[i] }
        val total = sum.sum()
        if (total <= 0) return TimeInRange.NONE
        return TimeInRange(
            sum[0] / total,
            sum[1] / total,
            sum[2] / total,
            sum[3] / total,
            sum[4] / total,
            sum[5] / total,
            (total / (to - from)).coerceAtMost(1.0),
        )
    }

    /**
     * The distribution beside the chart: relative time at each level (index = mg/dL − [MIN_MG]). Glucose is followed
     * level by level between readings, smoothed by 4 mg/dL, then each band rescaled so its area equals that band's
     * share in [tir]: the shape never disagrees with the numbers beside it. Sums to 1 (empty → all zeros).
     */
    fun profile(
        all: List<GlucosePoint>,
        from: Long,
        to: Long,
        b: Bands,
        tir: TimeInRange,
    ): DoubleArray {
        val n = MAX_MG - MIN_MG + 1
        val rs = window(all, from, to)
        if (rs.isEmpty()) return DoubleArray(n)
        val d = smooth(occupation(rs), 4.0)
        val area = DoubleArray(Band.entries.size)
        for (i in 0 until n) area[b.of(MIN_MG + i).ordinal] += d[i]
        for (i in 0 until n) {
            val band = b.of(MIN_MG + i)
            val a = area[band.ordinal]
            if (a > 0) d[i] *= tir.share(band) / a
        }
        return d
    }

    /** Between two readings ≤ [MAX_GAP_S] apart the time is spread evenly over every level passed; the last keeps a point. */
    private fun occupation(rs: List<GlucosePoint>): DoubleArray {
        val out = DoubleArray(MAX_MG - MIN_MG + 1)
        for (i in 0 until rs.lastIndex) {
            val a = rs[i]
            val c = rs[i + 1]
            val dt = (c.t - a.t).toDouble()
            if (dt <= 0 || dt > MAX_GAP_S) {
                idx(a.sgv)?.let { out[it] += 60.0 }
                continue
            }
            val lo = min(a.sgv, c.sgv)
            val hi = max(a.sgv, c.sgv)
            val levels = hi - lo + 1
            for (g in lo..hi) idx(g)?.let { out[it] += dt / levels }
        }
        idx(rs.last().sgv)?.let { out[it] += 60.0 }
        return out
    }

    private fun idx(mg: Int): Int? = (mg - MIN_MG).takeIf { it in 0..(MAX_MG - MIN_MG) }

    private fun smooth(
        d: DoubleArray,
        s: Double,
    ): DoubleArray {
        val r = (3 * s).toInt()
        val k =
            DoubleArray(2 * r + 1) {
                val z = (it - r) / s
                exp(-0.5 * z * z)
            }
        return DoubleArray(d.size) { i ->
            var acc = 0.0
            var ws = 0.0
            for (j in -r..r) {
                val ii = i + j
                if (ii in d.indices) {
                    acc += d[ii] * k[j + r]
                    ws += k[j + r]
                }
            }
            acc / ws
        }
    }
}

/**
 * The doctor's report numbers (LibreView's AGP page) for one window: time in the five ranges, average, GMI and CV,
 * every reading weighted by the time it stands for, like the rings.
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
    val cv: Double get() = if (mean > 0) sd / mean * 100 else 0.0

    /** Glucose Management Indicator, % (Bergenstal 2018: 3.31 + 0.02392 × mean mg/dL). */
    val gmi: Double get() = 3.31 + 0.02392 * mean

    /** The same GMI in IFCC units, mmol/mol. */
    val gmiMmolMol: Double get() = (gmi - 2.152) * 10.929
    val empty: Boolean get() = readings == 0

    companion object {
        fun of(
            all: List<GlucosePoint>,
            from: Long,
            to: Long,
            b: Bands,
        ): GlucoseMetrics {
            val rs = GlucoseStats.window(all, from, to)
            val w = GlucoseStats.weights(rs, to)
            val total = w.sum()
            val tir = GlucoseStats.timeInRange(all, from, to, b)
            if (total <= 0) return GlucoseMetrics(tir, 0.0, 0.0, 0)
            val mean = rs.indices.sumOf { rs[it].sgv * w[it] } / total
            val variance =
                rs.indices.sumOf {
                    val d = rs[it].sgv - mean
                    d * d * w[it]
                } / total
            return GlucoseMetrics(tir, mean, sqrt(variance), rs.size)
        }
    }
}

/** The international consensus targets (Battelino 2019) LibreView prints as goals; [met] colours a number. */
enum class Goal(
    val text: String,
) {
    VERY_HIGH("< 5%"),
    ABOVE("< 25%"),
    IN_RANGE("> 70%"),
    BELOW("< 4%"),
    VERY_LOW("< 1%"),
    AVERAGE("< 154 mg/dL"),
    GMI("< 7%"),
    CV("≤ 36%"),
    ;

    fun met(m: GlucoseMetrics): Boolean =
        when (this) {
            VERY_HIGH -> m.tir.veryHigh < 0.05
            ABOVE -> m.tir.above < 0.25
            IN_RANGE -> m.tir.inRange > 0.70
            BELOW -> m.tir.below < 0.04
            VERY_LOW -> m.tir.veryLow < 0.01
            AVERAGE -> m.mean < 154
            GMI -> m.gmi < 7.0
            CV -> m.cv <= 36.0
        }

    /** Whether a time share [x] meets this goal (null for the goals that aren't shares). */
    fun metShare(x: Double): Boolean? =
        when (this) {
            VERY_HIGH -> x < 0.05
            ABOVE -> x < 0.25
            IN_RANGE -> x > 0.70
            BELOW -> x < 0.04
            VERY_LOW -> x < 0.01
            else -> null
        }

    companion object {
        /** The report's minimum data in a window (70 %): below it the rings wear no mark. */
        const val MIN_COVERAGE = 0.70
    }
}

/**
 * A time share as the number of a percentage, without the "%": whole percent from 10 %, one decimal under 10 %, and
 * more decimals (up to two) whenever rounding would put the number on the other side of its goal than the value (or
 * turn a share that isn't all into "100"); a non-zero share that rounds to 0 is "<0.1". contracts/graph/share.
 */
object ShareText {
    private fun format(
        x: Double,
        met: ((Double) -> Boolean)?,
        locale: java.util.Locale,
    ): String {
        fun shown(d: Int): Double {
            val k = Math.pow(10.0, d + 2.0)
            return Math.round(x * k) / k
        }
        var d = if (x < 0.095) 1 else 0
        while (d < 2 && ((met != null && met(shown(d)) != met(x)) || (x < 1.0 && shown(d) >= 1.0))) d++
        if (x > 0 && shown(d) == 0.0) return "<" + "%.1f".format(locale, 0.1)
        if (x < 1.0 && shown(d) >= 1.0) return ">" + "%.1f".format(locale, 99.9)
        return "%.${d}f".format(locale, shown(d) * 100)
    }

    fun of(
        x: Double,
        goal: Goal? = null,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ) = format(x, goal?.let { g -> { v: Double -> g.metShare(v) ?: true } }, locale)

    /** Coverage against the report's 70 % (the "only … % has data" note). */
    fun coverage(
        x: Double,
        locale: java.util.Locale = java.util.Locale.getDefault(),
    ) = format(x, { v: Double -> v >= Goal.MIN_COVERAGE }, locale)
}
