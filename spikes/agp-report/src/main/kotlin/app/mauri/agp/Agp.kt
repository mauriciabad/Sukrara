// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

/** One sensor reading: [t] epoch seconds, [sgv] mg/dL. */
data class Reading(val t: Long, val sgv: Int)

/**
 * Sukrara's ranges (investigation 16), mg/dL: very low < [veryLow] ≤ low < [low] ≤ tight ≤ [tight] < target ≤ [high] <
 * high ≤ [veryHigh] < very high. Identical to the AGP's (Target 70–180, High 181–250, Very High > 250, Low 54–69,
 * Very Low < 54) with the 2025 "time in tight range" 70–140 inside the target range.
 */
data class Bands(val veryLow: Int = 54, val low: Int = 70, val tight: Int = 140, val high: Int = 180, val veryHigh: Int = 250) {
    fun of(mg: Double): Range = when {
        mg < veryLow -> Range.VERY_LOW
        mg < low -> Range.LOW
        mg <= tight -> Range.TIGHT
        mg <= high -> Range.TARGET
        mg <= veryHigh -> Range.HIGH
        else -> Range.VERY_HIGH
    }
}

/** [TARGET] = in range above the tight band; in range = [TIGHT] + [TARGET]. */
enum class Range { VERY_LOW, LOW, TIGHT, TARGET, HIGH, VERY_HIGH }

/**
 * How the profile is computed. Defaults = the AGP as implemented by Tidepool's IDC-licensed print view: 1-hour bins
 * of the modal day, linear-interpolated percentiles (R-7, numpy's default), 1-4-1 circular smoothing. Before binning,
 * glucose is sampled on a regular [gridS] grid (the reading in effect at each grid instant, same rule as the time
 * weights), so 1-minute live data can't outweigh 5/15-minute history.
 */
data class AgpOptions(val gridS: Long = 300, val binMinutes: Int = 60, val maxGapS: Long = 900, val smooth: Boolean = true) {
    init { require(1440 % binMinutes == 0) { "bins must tile the day" } }
    val bins get() = 1440 / binMinutes
}

/** A report period: [days] whole local days from [first] in [zone] (midnight to midnight, DST-aware). */
data class Period(val zone: ZoneId, val first: LocalDate, val days: Int) {
    val from: Long get() = first.atStartOfDay(zone).toEpochSecond()
    val to: Long get() = first.plusDays(days.toLong()).atStartOfDay(zone).toEpochSecond()
    val last: LocalDate get() = first.plusDays(days - 1L)

    companion object {
        /** The [days] days ending with the newest reading's local day (LibreView / IDC: the last 14 days). */
        fun ending(newest: Long, zone: ZoneId, days: Int) =
            Period(zone, Instant.ofEpochSecond(newest).atZone(zone).toLocalDate().minusDays(days - 1L), days)
    }
}

/** Time-weighted numbers for the period. Shares are fractions of the time with data (they sum to 1). */
data class Metrics(
    val veryLow: Double, val low: Double, val tight: Double, val target: Double, val high: Double, val veryHigh: Double,
    /** Time CGM active: time with data / period length. */
    val active: Double,
    /** Seconds with data. */
    val seconds: Double,
    val mean: Double, val sd: Double, val readings: Int,
) {
    val inRange get() = tight + target
    val below get() = veryLow + low
    val above get() = high + veryHigh
    val cv get() = if (mean > 0) sd / mean * 100 else 0.0
    /** GMI % (Bergenstal et al. 2018). */
    val gmi get() = 3.31 + 0.02392 * mean
    val gmiMmolMol get() = (gmi - 2.152) * 10.929
}

/** One bin of the modal day: centre [minute] of the local day, percentiles (null = no data), raw grid samples [n]. */
data class AgpBin(val minute: Int, val n: Int, val p5: Double?, val p25: Double?, val p50: Double?, val p75: Double?, val p95: Double?) {
    fun values() = listOf(p5, p25, p50, p75, p95)
}

/** One point of a daily strip: [x] = elapsed fraction of that local day (its true 23/24/25 h length). */
data class DayPoint(val x: Double, val sgv: Int)

data class DayProfile(
    val date: LocalDate, val from: Long, val to: Long,
    /** Fraction of the day with data. */
    val active: Double,
    /** Share in range (70–180) of the time with data, null without data. */
    val inRange: Double?,
    /** Unbroken runs of readings (a gap > maxGap splits them). */
    val segments: List<List<DayPoint>>,
    /** Where local noon falls (≠ 0.5 on DST days). */
    val noon: Double,
) {
    val hours get() = (to - from) / 3600.0
    val weekday: DayOfWeek get() = date.dayOfWeek
}

/**
 * What the page may show, following the AGP's data-sufficiency rules as Tidepool implements them (the IDC rules are
 * licensed, not published): under 24 h of data nothing; the percentile profile needs ≥ 7 days with ≥ 1 h of data each
 * and the best 7 of them ≥ 70 % active on average. [consensus] = the 2019 consensus' "14 days, ≥ 70 % active".
 */
data class Sufficiency(val hours: Double, val daysWithData: Int, val best7Active: Double, val report: Boolean, val profile: Boolean, val consensus: Boolean)

data class AgpReport(
    val period: Period, val bands: Bands, val options: AgpOptions,
    val metrics: Metrics, val sufficiency: Sufficiency,
    /** Smoothed modal-day percentiles, [AgpOptions.bins] entries (the curve drawn). */
    val profile: List<AgpBin>,
    /** The same before smoothing (what the independent check compares first). */
    val rawProfile: List<AgpBin>,
    val days: List<DayProfile>,
)

object Agp {
    const val DEDUP_S = 30L

    /** Readings in [from, to), sorted, a reading within 30 s of the previous one replaces it (as Sukrara does). */
    fun window(all: List<Reading>, from: Long, to: Long): List<Reading> {
        val out = ArrayList<Reading>()
        for (r in all.filter { it.t >= from && it.t < to }.sortedBy { it.t }) {
            if (out.isNotEmpty() && r.t - out.last().t < DEDUP_S) out[out.lastIndex] = r else out += r
        }
        return out
    }

    /** Seconds each reading stands for: until the next one, at most [maxGap]; the last one its previous spacing, at most until [to]. */
    fun weights(rs: List<Reading>, to: Long, maxGap: Long): LongArray {
        if (rs.isEmpty()) return LongArray(0)
        val spacing = if (rs.size > 1) (rs.last().t - rs[rs.lastIndex - 1].t).coerceIn(1, maxGap) else 60L
        return LongArray(rs.size) { i ->
            if (i < rs.lastIndex) min(rs[i + 1].t - rs[i].t, maxGap)
            else max(1L, min(spacing, to - rs[i].t))
        }
    }

    fun metrics(rs: List<Reading>, w: LongArray, from: Long, to: Long, b: Bands): Metrics {
        val share = DoubleArray(Range.entries.size)
        var total = 0.0
        var sum = 0.0
        rs.forEachIndexed { i, r -> share[b.of(r.sgv.toDouble()).ordinal] += w[i].toDouble(); total += w[i]; sum += r.sgv * w[i].toDouble() }
        if (total <= 0) return Metrics(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0)
        val mean = sum / total
        val variance = rs.indices.sumOf { val d = rs[it].sgv - mean; d * d * w[it] } / total
        for (k in share.indices) share[k] /= total
        return Metrics(share[0], share[1], share[2], share[3], share[4], share[5], min(1.0, total / (to - from)), total, mean, sqrt(variance), rs.size)
    }

    /**
     * The regular grid: one sample every [AgpOptions.gridS] from the period's start, each the reading in effect at
     * that instant (the newest reading at or before it that still stands for it, see [weights]). null = no data.
     */
    fun grid(rs: List<Reading>, w: LongArray, from: Long, to: Long, o: AgpOptions): List<Pair<Long, Int?>> {
        val out = ArrayList<Pair<Long, Int?>>()
        var j = -1
        var g = from
        while (g < to) {
            while (j + 1 < rs.size && rs[j + 1].t <= g) j++
            out += g to if (j >= 0 && g - rs[j].t < w[j]) rs[j].sgv else null
            g += o.gridS
        }
        return out
    }

    /** Linear-interpolated quantile of a sorted list (Hyndman–Fan type 7: numpy's default, d3.quantile). */
    fun quantile(sorted: DoubleArray, p: Double): Double {
        require(sorted.isNotEmpty())
        val h = (sorted.size - 1) * p
        val lo = floor(h).toInt()
        val hi = min(lo + 1, sorted.lastIndex)
        return sorted[lo] + (h - lo) * (sorted[hi] - sorted[lo])
    }

    val PERCENTILES = doubleArrayOf(0.05, 0.25, 0.50, 0.75, 0.95)

    fun profile(grid: List<Pair<Long, Int?>>, zone: ZoneId, o: AgpOptions): List<AgpBin> {
        val bins = Array(o.bins) { ArrayList<Double>() }
        for ((t, v) in grid) {
            if (v == null) continue
            val local = Instant.ofEpochSecond(t).atZone(zone).toLocalTime()
            bins[(local.toSecondOfDay() / 60) / o.binMinutes] += v.toDouble()
        }
        return bins.mapIndexed { i, vs ->
            val centre = i * o.binMinutes + o.binMinutes / 2
            if (vs.isEmpty()) AgpBin(centre, 0, null, null, null, null, null)
            else {
                val s = vs.toDoubleArray().also { it.sort() }
                val q = PERCENTILES.map { quantile(s, it) }
                AgpBin(centre, vs.size, q[0], q[1], q[2], q[3], q[4])
            }
        }
    }

    /**
     * 1-4-1 weighted moving average around the (circular) day, per percentile. An empty neighbour drops out
     * (weights 0-4-1 / 1-4-0, ÷5); an empty bin, or one with both neighbours empty, stays as it is.
     */
    fun smooth(bins: List<AgpBin>): List<AgpBin> {
        val n = bins.size
        fun one(i: Int, k: Int): Double? {
            val c = bins[i].values()[k] ?: return null
            val p = bins[(i - 1 + n) % n].values()[k]
            val x = bins[(i + 1) % n].values()[k]
            return when {
                p == null && x == null -> c
                p == null -> (4 * c + x!!) / 5
                x == null -> (p + 4 * c) / 5
                else -> (p + 4 * c + x) / 6
            }
        }
        return bins.indices.map { i -> bins[i].copy(p5 = one(i, 0), p25 = one(i, 1), p50 = one(i, 2), p75 = one(i, 3), p95 = one(i, 4)) }
    }

    fun days(rs: List<Reading>, w: LongArray, p: Period, b: Bands, o: AgpOptions): List<DayProfile> = (0 until p.days).map { k ->
        val date = p.first.plusDays(k.toLong())
        val from = date.atStartOfDay(p.zone).toEpochSecond()
        val to = date.plusDays(1).atStartOfDay(p.zone).toEpochSecond()
        val len = (to - from).toDouble()
        val idx = rs.indices.filter { rs[it].t >= from && rs[it].t < to }
        var secs = 0.0
        var inR = 0.0
        val segments = ArrayList<List<DayPoint>>()
        var cur = ArrayList<DayPoint>()
        var prev: Long? = null
        for (i in idx) {
            val r = rs[i]
            val s = min(w[i], to - r.t).toDouble()
            secs += s
            if (b.of(r.sgv.toDouble()).let { it == Range.TIGHT || it == Range.TARGET }) inR += s
            if (prev != null && r.t - prev > o.maxGapS) { segments += cur; cur = ArrayList() }
            cur += DayPoint((r.t - from) / len, r.sgv)
            prev = r.t
        }
        if (cur.isNotEmpty()) segments += cur
        val noon = (date.atTime(12, 0).atZone(p.zone).toEpochSecond() - from) / len
        DayProfile(date, from, to, secs / len, if (secs > 0) inR / secs else null, segments, noon)
    }

    fun sufficiency(m: Metrics, days: List<DayProfile>, p: Period): Sufficiency {
        val hours = m.seconds / 3600
        val withData = days.filter { it.active * it.hours >= 1.0 }
        val best7 = withData.map { it.active }.sortedDescending().take(7)
        val best7Mean = if (best7.isEmpty()) 0.0 else best7.average()
        val report = hours >= 24
        return Sufficiency(hours, withData.size, best7Mean, report,
            report && best7.size >= 7 && best7Mean >= 0.70, p.days >= 14 && m.active >= 0.70)
    }

    fun report(all: List<Reading>, p: Period, b: Bands = Bands(), o: AgpOptions = AgpOptions()): AgpReport {
        val rs = window(all, p.from, p.to)
        val w = weights(rs, p.to, o.maxGapS)
        val m = metrics(rs, w, p.from, p.to, b)
        val raw = profile(grid(rs, w, p.from, p.to, o), p.zone, o)
        val days = days(rs, w, p, b, o)
        return AgpReport(p, b, o, m, sufficiency(m, days, p), if (o.smooth) smooth(raw) else raw, raw, days)
    }
}
