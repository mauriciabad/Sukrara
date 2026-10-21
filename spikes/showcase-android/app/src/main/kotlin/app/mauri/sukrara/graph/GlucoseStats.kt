// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Reading
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * The user's ranges (investigation 16), mg/dL, as in LibreView's report: very low < [veryLow] ≤ low < [low] ≤ in range
 * ≤ [high] < high ≤ [veryHigh] < very high. Inside the in-range band, the "very good" (tight) band [low]..[tight],
 * where a person without diabetes spends nearly all day. [extremelyHigh] (≥, like the extremely high alert) only
 * colours the line and the number: time in range keeps LibreView's five ranges, so it counts as very high there.
 */
data class Bands(val veryLow: Int = 54, val low: Int = 70, val tight: Int = 140, val high: Int = 180, val veryHigh: Int = 250,
                 val extremelyHigh: Int = 300) {
    fun of(mgdl: Double): Band = when {
        mgdl < veryLow -> Band.VERY_LOW
        mgdl < low -> Band.LOW
        mgdl <= tight -> Band.TIGHT
        mgdl <= high -> Band.TARGET
        mgdl <= veryHigh -> Band.HIGH
        else -> Band.VERY_HIGH
    }
    fun of(mgdl: Int) = of(mgdl.toDouble())

    /** Clamped so the order always holds (a settings slider can't produce low > tight > high). */
    fun sane(): Bands {
        val l = low.coerceIn(40, 120)
        val h = high.coerceIn(l + 20, 300)
        val vh = veryHigh.coerceIn(h + 10, 400)
        return Bands(veryLow.coerceIn(30, l - 5), l, tight.coerceIn(l + 10, h), h, vh, extremelyHigh.coerceIn(vh + 10, 450))
    }
}

/** [TARGET] = in range but above the tight band; in range = [TIGHT] + [TARGET]; below = the two lows, above = the two highs. */
enum class Band { VERY_LOW, LOW, TIGHT, TARGET, HIGH, VERY_HIGH;
    /** The three major groups: -1 below range, 0 in range, 1 above. */
    val group get() = when (this) { VERY_LOW, LOW -> -1; TIGHT, TARGET -> 0; HIGH, VERY_HIGH -> 1 }
}

/** Which band a time-in-range number counts. */
enum class TirRange { IN_RANGE, TIGHT }

/**
 * Share of time per band in a window. Every reading stands for the time until the next one (capped at [MAX_GAP_S]),
 * so 1-minute live readings and 5/15-minute backfilled history count the same per hour and a gap counts as nothing.
 * [coverage] = how much of the window that accounts for. [low] / [high] are the low / high bands alone.
 */
data class TimeInRange(
    val veryLow: Double, val low: Double, val tight: Double, val target: Double, val high: Double, val veryHigh: Double,
    val coverage: Double,
) {
    /** Exactly 1 when nothing is below or above (the sum of two shares can miss it; 100 % is the crown, [TirMark]). */
    val inRange get() = if (below <= 0.0 && above <= 0.0 && !empty) 1.0 else tight + target
    val below get() = veryLow + low
    val above get() = high + veryHigh
    fun of(r: TirRange) = if (r == TirRange.IN_RANGE) inRange else if (below + above + target <= 0.0 && !empty) 1.0 else tight
    fun share(b: Band) = when (b) {
        Band.VERY_LOW -> veryLow; Band.LOW -> low; Band.TIGHT -> tight; Band.TARGET -> target; Band.HIGH -> high
        Band.VERY_HIGH -> veryHigh
    }
    val empty get() = coverage <= 0.0

    companion object { val NONE = TimeInRange(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0) }
}

object GlucoseStats {
    const val MAX_GAP_S = 15 * 60L
    /** Densities are computed on a 1 mg/dL grid over this range. */
    const val MIN_MG = 20
    const val MAX_MG = 450

    /**
     * Readings in [from, to), sorted, deduplicated (±30 s). A sorted history (the stores' order) is cut by binary search,
     * so a scrolled chart over weeks of readings only touches its window each frame.
     */
    fun window(all: List<Reading>, from: Long, to: Long): List<Reading> {
        val src = if (all is RandomAccess && sorted(all)) {
            val lo = lowerBound(all, from)
            val hi = lowerBound(all, to + 30)
            all.subList(lo, hi)
        } else all.filter { it.t in from until to + 30 }.sortedBy { it.t }
        val out = ArrayList<Reading>(src.size)
        for (r in src) if (out.isNotEmpty() && r.t - out[out.size - 1].t < 30) out[out.size - 1] = r else out += r
        return out
    }

    private fun sorted(all: List<Reading>): Boolean {
        for (i in 1 until all.size) if (all[i].t < all[i - 1].t) return false
        return true
    }

    /** The first index with t ≥ [t] in a sorted list. */
    private fun lowerBound(all: List<Reading>, t: Long): Int {
        var lo = 0
        var hi = all.size
        while (lo < hi) { val m = (lo + hi) ushr 1; if (all[m].t < t) lo = m + 1 else hi = m }
        return lo
    }

    /**
     * Seconds each reading stands for (see [TimeInRange]). The last one: the spacing just before it (1-minute live or
     * 5/15-minute history), at most until [to].
     */
    fun weights(rs: List<Reading>, to: Long): DoubleArray {
        if (rs.isEmpty()) return DoubleArray(0)
        val spacing = if (rs.size > 1) (rs.last().t - rs[rs.lastIndex - 1].t).coerceIn(1, MAX_GAP_S) else 60L
        return DoubleArray(rs.size) { i ->
            if (i < rs.lastIndex) min(rs[i + 1].t - rs[i].t, MAX_GAP_S).toDouble()
            else min(spacing, max(0L, to - rs[i].t)).toDouble().coerceAtLeast(1.0)
        }
    }

    fun timeInRange(all: List<Reading>, from: Long, to: Long, b: Bands): TimeInRange {
        val rs = window(all, from, to)
        val w = weights(rs, to)
        val sum = DoubleArray(Band.entries.size)
        rs.forEachIndexed { i, r -> sum[b.of(r.sgv).ordinal] += w[i] }
        val total = sum.sum()
        if (total <= 0) return TimeInRange.NONE
        return TimeInRange(sum[0] / total, sum[1] / total, sum[2] / total, sum[3] / total, sum[4] / total, sum[5] / total,
            (total / (to - from)).coerceAtMost(1.0))
    }

    /**
     * The distribution beside the chart (investigation 16, the user's pick of six options): relative time at each
     * level, index = mg/dL − [MIN_MG]. Glucose is followed level by level between readings ([occupation]), smoothed by
     * 4 mg/dL, then each band is rescaled so its area is exactly that band's share in [tir]: the shape can never
     * disagree with the time-in-range numbers next to it. Sums to 1 (empty → all zeros).
     */
    fun profile(all: List<Reading>, from: Long, to: Long, b: Bands, tir: TimeInRange): DoubleArray {
        val n = MAX_MG - MIN_MG + 1
        val rs = window(all, from, to)
        if (rs.isEmpty()) return DoubleArray(n)
        val d = smooth(occupation(rs), 4.0)
        // each band's area, then each level scaled to its band's share (no per-band lists: a scrolled chart does this every frame)
        val area = DoubleArray(Band.entries.size)
        for (i in 0 until n) area[b.of(MIN_MG + i).ordinal] += d[i]
        for (i in 0 until n) { val band = b.of(MIN_MG + i); val a = area[band.ordinal]; if (a > 0) d[i] *= tir.share(band) / a }
        return d
    }

    /**
     * Occupation density: between two readings ≤ [MAX_GAP_S] apart glucose is taken to move linearly, so the time
     * between them is spread evenly over every level it passed through. The last reading keeps a point mass.
     */
    private fun occupation(rs: List<Reading>): DoubleArray {
        val out = DoubleArray(MAX_MG - MIN_MG + 1)
        for (i in 0 until rs.lastIndex) {
            val a = rs[i]; val c = rs[i + 1]
            val dt = (c.t - a.t).toDouble()
            if (dt <= 0 || dt > MAX_GAP_S) { idx(a.sgv)?.let { out[it] += 60.0 }; continue }
            val lo = min(a.sgv, c.sgv); val hi = max(a.sgv, c.sgv)
            val levels = hi - lo + 1
            for (g in lo..hi) idx(g)?.let { out[it] += dt / levels }
        }
        idx(rs.last().sgv)?.let { out[it] += 60.0 }
        return out
    }

    private fun idx(mg: Int): Int? = (mg - MIN_MG).takeIf { it in 0..(MAX_MG - MIN_MG) }


    private fun smooth(d: DoubleArray, s: Double): DoubleArray {
        val r = (3 * s).toInt()
        val k = DoubleArray(2 * r + 1) { val z = (it - r) / s; exp(-0.5 * z * z) }
        return DoubleArray(d.size) { i ->
            var acc = 0.0; var ws = 0.0
            for (j in -r..r) { val ii = i + j; if (ii in d.indices) { acc += d[ii] * k[j + r]; ws += k[j + r] } }
            acc / ws
        }
    }

}
