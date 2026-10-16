// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Reading
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The user's major ranges (investigation 16), mg/dL. Low < [low] ≤ in range ≤ [high] < high, and inside the in-range
 * band the "very good" (tight) band [low]..[tight], where a person without diabetes spends nearly all day.
 */
data class Bands(val low: Int = 70, val tight: Int = 140, val high: Int = 180) {
    fun of(mgdl: Double): Band = when {
        mgdl < low -> Band.LOW
        mgdl <= tight -> Band.TIGHT
        mgdl <= high -> Band.TARGET
        else -> Band.HIGH
    }
    fun of(mgdl: Int) = of(mgdl.toDouble())

    /** Clamped so the order always holds (a settings slider can't produce low > tight > high). */
    fun sane(): Bands {
        val l = low.coerceIn(40, 120)
        val h = high.coerceIn(l + 20, 300)
        return Bands(l, tight.coerceIn(l + 10, h), h)
    }
}

/** [TARGET] = in range but above the tight band; "in range" = [TIGHT] + [TARGET]. */
enum class Band { LOW, TIGHT, TARGET, HIGH }

/** Which band a time-in-range number counts. */
enum class TirRange(val label: String) { IN_RANGE("In range"), TIGHT("Very good") }

/**
 * Share of time per band in a window. Every reading stands for the time until the next one (capped at [MAX_GAP_S]),
 * so 1-minute live readings and 5/15-minute backfilled history count the same per hour and a gap counts as nothing.
 * [coverage] = how much of the window that accounts for.
 */
data class TimeInRange(val low: Double, val tight: Double, val target: Double, val high: Double, val coverage: Double) {
    val inRange get() = tight + target
    fun of(r: TirRange) = if (r == TirRange.TIGHT) tight else inRange
    fun share(b: Band) = when (b) { Band.LOW -> low; Band.TIGHT -> tight; Band.TARGET -> target; Band.HIGH -> high }
    val empty get() = coverage <= 0.0

    companion object { val NONE = TimeInRange(0.0, 0.0, 0.0, 0.0, 0.0) }
}

/** The distribution drawn on the left of the chart (the "side profile"). Options shown to the user in docs/16. */
enum class Profile(val label: String, val blurb: String) {
    NONE("Bands only", "Just the time-in-range rectangles, no profile."),
    HISTOGRAM("Histogram", "Time per 10 mg/dL bin. Blocky, exact, no tuning."),
    KDE("Gaussian (fixed)", "Each reading is a bell 10 mg/dL wide. Smooth; leaks across the thresholds."),
    KDE_LOG("Gaussian (relative)", "Bell width 7 % of the value: sharp at lows, wider at highs."),
    OCCUPATION("Time at level", "The curve between readings is followed level by level, then lightly smoothed."),
    BAND_EXACT("Time at level, band-exact", "As time at level, but each band's area equals its time-in-range share."),
}

object GlucoseStats {
    const val MAX_GAP_S = 15 * 60L
    /** Densities are computed on a 1 mg/dL grid over this range. */
    const val MIN_MG = 20
    const val MAX_MG = 450

    /** Readings in [from, to), sorted, deduplicated (±30 s). */
    fun window(all: List<Reading>, from: Long, to: Long): List<Reading> =
        all.asSequence().filter { it.t in from until to + 30 }.sortedBy { it.t }
            .fold(ArrayList<Reading>()) { acc, r -> if (acc.isNotEmpty() && r.t - acc.last().t < 30) acc[acc.size - 1] = r else acc += r; acc }

    /** Seconds each reading stands for (see [TimeInRange]). The last one: the typical spacing, at most until [to]. */
    fun weights(rs: List<Reading>, to: Long): DoubleArray {
        if (rs.isEmpty()) return DoubleArray(0)
        val gaps = rs.zipWithNext { a, b -> b.t - a.t }.filter { it in 1..MAX_GAP_S }.sorted()
        val typical = gaps.getOrNull(gaps.size / 2) ?: 60L
        return DoubleArray(rs.size) { i ->
            if (i < rs.lastIndex) min(rs[i + 1].t - rs[i].t, MAX_GAP_S).toDouble()
            else min(typical, max(0L, to - rs[i].t) + 1).toDouble()
        }
    }

    fun timeInRange(all: List<Reading>, from: Long, to: Long, b: Bands): TimeInRange {
        val rs = window(all, from, to)
        val w = weights(rs, to)
        val sum = DoubleArray(4)
        rs.forEachIndexed { i, r -> sum[b.of(r.sgv).ordinal] += w[i] }
        val total = sum.sum()
        if (total <= 0) return TimeInRange.NONE
        return TimeInRange(sum[0] / total, sum[1] / total, sum[2] / total, sum[3] / total,
            (total / (to - from)).coerceAtMost(1.0))
    }

    /**
     * Relative time spent at each level, index = mg/dL − [MIN_MG], normalised to sum 1 (empty → all zeros).
     * [tir] is only used by [Profile.BAND_EXACT].
     */
    fun profile(kind: Profile, all: List<Reading>, from: Long, to: Long, b: Bands, tir: TimeInRange): DoubleArray {
        val n = MAX_MG - MIN_MG + 1
        val rs = window(all, from, to)
        if (rs.isEmpty() || kind == Profile.NONE) return DoubleArray(n)
        val w = weights(rs, to)
        val d = when (kind) {
            Profile.HISTOGRAM -> DoubleArray(n).also { out ->
                // 10 mg/dL bins aligned to the thresholds' grid (…, 60–69, 70–79, …); each bin spread flat over its 10 levels.
                val bins = HashMap<Int, Double>()
                rs.forEachIndexed { i, r -> bins.merge(Math.floorDiv(r.sgv, 10), w[i], Double::plus) }
                for ((bin, m) in bins) for (k in 0 until 10) idx(bin * 10 + k)?.let { out[it] += m / 10 }
            }
            Profile.KDE -> DoubleArray(n).also { out ->
                rs.forEachIndexed { i, r -> addGauss(out, r.sgv.toDouble(), 10.0, w[i]) }
            }
            Profile.KDE_LOG -> DoubleArray(n).also { out ->
                // Gaussian in ln(mg/dL): σ = 0.07 ≈ 7 % of the value (5 mg/dL at 70, 13 at 180).
                val s = 0.07
                rs.forEachIndexed { i, r ->
                    val mu = ln(r.sgv.toDouble())
                    for (k in 0 until n) {
                        val g = (MIN_MG + k).toDouble()
                        val z = (ln(g) - mu) / s
                        if (abs(z) < 4) out[k] += w[i] * exp(-0.5 * z * z) / g   // 1/g: density per mg/dL, not per ln unit
                    }
                }
            }
            Profile.OCCUPATION, Profile.BAND_EXACT -> smooth(occupation(rs), 4.0)
            Profile.NONE -> DoubleArray(n)
        }
        normalise(d)
        if (kind == Profile.BAND_EXACT) {
            // Rescale each band so its area is exactly the band's time share: the profile then agrees with the % labels.
            for (band in Band.entries) {
                val ks = (0 until n).filter { b.of(MIN_MG + it) == band }
                val area = ks.sumOf { d[it] }
                if (area > 0) ks.forEach { d[it] *= tir.share(band) / area }
            }
        }
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

    private fun addGauss(out: DoubleArray, mu: Double, s: Double, w: Double) {
        val lo = max(MIN_MG, (mu - 4 * s).toInt()); val hi = min(MAX_MG, (mu + 4 * s).roundToInt())
        for (g in lo..hi) { val z = (g - mu) / s; out[g - MIN_MG] += w * exp(-0.5 * z * z) }
    }

    private fun smooth(d: DoubleArray, s: Double): DoubleArray {
        val r = (3 * s).toInt()
        val k = DoubleArray(2 * r + 1) { val z = (it - r) / s; exp(-0.5 * z * z) }
        return DoubleArray(d.size) { i ->
            var acc = 0.0; var ws = 0.0
            for (j in -r..r) { val ii = i + j; if (ii in d.indices) { acc += d[ii] * k[j + r]; ws += k[j + r] } }
            acc / ws
        }
    }

    private fun normalise(d: DoubleArray) {
        val s = d.sum()
        if (s > 0) for (i in d.indices) d[i] /= s
    }
}
