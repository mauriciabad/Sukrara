// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.agp

import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The page as a display list: every platform paints the same few primitives (Java2D here, `android.graphics.Canvas`
 * inside `PdfDocument` in the app, CoreGraphics on the Mac), so the layout is written once and can be compared as data
 * (like spikes/graph-fixtures/labels). Units are PDF points, origin top-left, y down. Colours are opaque ARGB.
 */
sealed interface Op {
    data class Rect(val x: Double, val y: Double, val w: Double, val h: Double, val fill: Int, val r: Double = 0.0) : Op
    data class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val color: Int, val width: Double, val dash: Double = 0.0) : Op
    /** [pts] flat x,y pairs; filled when [fill] != null, stroked when [stroke] != null. */
    data class Poly(val pts: DoubleArray, val closed: Boolean, val fill: Int?, val stroke: Int?, val width: Double = 1.0) : Op
    /** [y] is the baseline; [align] -1 left, 0 centre, 1 right of [x]. */
    data class Text(val x: Double, val y: Double, val text: String, val size: Double, val bold: Boolean, val color: Int, val align: Int = -1) : Op
    data class Clip(val x: Double, val y: Double, val w: Double, val h: Double) : Op
    data object Unclip : Op
}

class Page(val w: Double, val h: Double, val ops: List<Op>)

/** Sukrara's light palette (graph/GlucoseGraph.kt LIGHT): paper is always light. */
object Palette {
    const val INK = 0xFF1C1C1E.toInt()
    const val INK_SOFT = 0xFF6B6B70.toInt()
    const val GRID = 0xFFDADADF.toInt()
    const val SECTION = 0xFFF2F2F5.toInt()
    const val VERY_LOW = 0xFFB3261E.toInt()
    const val LOW = 0xFFD92D2A.toInt()
    const val TIGHT = 0xFF17A34A.toInt()
    const val TARGET = 0xFF6E9E1E.toInt()
    const val HIGH = 0xFFD97706.toInt()
    const val VERY_HIGH = 0xFFC2410C.toInt()
    /** Goal not met: the high orange (red is only for errors). */
    const val MISSED = HIGH

    fun of(r: Range) = when (r) {
        Range.VERY_LOW -> VERY_LOW; Range.LOW -> LOW; Range.TIGHT -> TIGHT; Range.TARGET -> TARGET
        Range.HIGH -> HIGH; Range.VERY_HIGH -> VERY_HIGH
    }

    /** [c] mixed with white: [k] = how much of the colour stays. */
    fun tint(c: Int, k: Double): Int {
        fun ch(s: Int) = (255 - (255 - ((c shr s) and 0xFF)) * k).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Texts of the page. English only in the PoC; the app takes them from strings.xml / Localizable. */
object PageText {
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK)
    /**
     * A time-in-range share: whole percent from 10 % up, one decimal below (the goals there are 1 % and 4 %), and one
     * decimal whenever the whole number would land on the other side of the goal than the value ("5 %" for 5.2 %
     * against "< 5 %" would read as met).
     */
    fun share(x: Double, goal: Double? = null, above: Boolean = false): String {
        val whole = (x * 100).roundToInt() / 100.0
        val crosses = goal != null && (if (above) (whole > goal) != (x > goal) else (whole < goal) != (x < goal))
        return pct(x, if (x < 0.10 || crosses) 1 else 0)
    }
    fun pct(x: Double, decimals: Int = 0) = if (decimals == 0) "${(x * 100).roundToInt()} %" else "%.${decimals}f %%".format(Locale.UK, x * 100).replace("%%", "%")
}

object AgpPage {
    const val W = 595.0 // A4 portrait, points
    const val H = 842.0
    const val M = 36.0
    const val Y_MAX = 350.0

    fun layout(r: AgpReport, subtitle: String): Page {
        val ops = ArrayList<Op>()
        val p = r.period
        val m = r.metrics
        val s = r.sufficiency
        var y = M + 18

        // Header: what, when, how much data. No logo, no patient fields beyond the subtitle.
        ops += Op.Text(M, y, "Glucose report", 20.0, true, Palette.INK)
        ops += Op.Text(W - M, y, subtitle, 9.0, false, Palette.INK_SOFT, 1)
        y += 18
        val range = "${p.days} days: ${p.first.format(PageText.DATE)} – ${p.last.format(PageText.DATE)}"
        ops += Op.Text(M, y, range, 10.0, false, Palette.INK)
        ops += Op.Text(W - M, y, "Time CGM active: ${PageText.pct(m.active, 1)}", 10.0, false, Palette.INK, 1)
        y += 14
        if (!s.consensus && s.report) {
            ops += Op.Text(M, y, "The consensus asks for 14 days with the sensor active at least 70 % of the time: read these numbers with care.", 8.5, false, Palette.INK_SOFT)
        }
        y += 12
        if (!s.report) {
            section(ops, y, "Not enough data")
            ops += Op.Text(M, y + 34, "The report needs at least 24 hours of sensor data in the period.", 10.0, false, Palette.INK)
            return Page(W, H, ops)
        }

        // Time in ranges (left) and glucose metrics (right)
        val colW = (W - 2 * M - 16) / 2
        section(ops, y, "Time in ranges", M, colW)
        section(ops, y, "Glucose metrics", M + colW + 16, colW)
        tir(ops, r, M, y + 28, colW)
        metrics(ops, r, M + colW + 16, y + 28, colW)
        y += 200

        // The profile
        section(ops, y, "Ambulatory glucose profile (AGP)")
        ops += Op.Text(M + 8, y + 30, "Median (50 %) and other percentiles of the whole period, shown as if they occurred in a single day.", 8.5, false, Palette.INK_SOFT)
        val chartTop = y + 42
        val chartH = 190.0
        if (s.profile && r.profile.all { it.p50 != null }) profileChart(ops, r, M + 34, chartTop, W - 2 * M - 34 - 30, chartH)
        else {
            ops += Op.Rect(M + 34, chartTop, W - 2 * M - 64, chartH, Palette.SECTION, 6.0)
            ops += Op.Text(W / 2, chartTop + chartH / 2, "Not enough data for the profile: it needs 7 days with the sensor active 70 % of the time.", 9.5, false, Palette.INK_SOFT, 0)
        }
        y = chartTop + chartH + 26

        section(ops, y, "Daily glucose profiles")
        ops += Op.Text(M + 8, y + 30, "Each daily profile represents a midnight-to-midnight period.", 8.5, false, Palette.INK_SOFT)
        daily(ops, r, M + 22, y + 52, W - 2 * M - 22, H - M - 18 - (y + 52))

        ops += Op.Text(M, H - M + 8, "Sukrara · investigation 26 PoC · synthetic data", 7.5, false, Palette.INK_SOFT)
        ops += Op.Text(W - M, H - M + 8, "Goals: international consensus on time in range (Battelino et al., Diabetes Care 2019)", 7.5, false, Palette.INK_SOFT, 1)
        return Page(W, H, ops)
    }

    private fun section(ops: MutableList<Op>, y: Double, title: String, x: Double = M, w: Double = W - 2 * M) {
        ops += Op.Rect(x, y, w, 20.0, Palette.SECTION, 4.0)
        ops += Op.Text(x + 8, y + 14, title, 11.0, true, Palette.INK)
    }

    private fun goalText(ok: Boolean) = if (ok) "✓" else ""

    private fun tir(ops: MutableList<Op>, r: AgpReport, x: Double, y: Double, w: Double) {
        val m = r.metrics
        val b = r.bands
        val barX = x + 30
        val barW = 22.0
        val barH = 150.0
        // Stacked bar, very high on top; every non-zero range at least 2 pt so it stays visible.
        val parts = listOf(Range.VERY_HIGH to m.veryHigh, Range.HIGH to m.high, Range.TARGET to m.target, Range.TIGHT to m.tight,
            Range.LOW to m.low, Range.VERY_LOW to m.veryLow)
        val minH = 2.0
        val raw = parts.map { (_, v) -> if (v > 0) max(minH, v * barH) else 0.0 }
        val k = barH / raw.sum().coerceAtLeast(1e-9)
        var cy = y
        val edges = HashMap<Range, Pair<Double, Double>>()
        parts.forEachIndexed { i, (range, _) ->
            val hh = raw[i] * k
            if (hh > 0) ops += Op.Rect(barX, cy, barW, hh, Palette.of(range))
            edges[range] = cy to cy + hh
            cy += hh
        }
        // Thresholds at the bar's left, where the ranges meet
        // pushed apart (8 pt) where thin ranges would make them collide, from the bottom up
        val ticks = listOf(b.veryLow to edges[Range.LOW]!!.second, b.low to edges[Range.TIGHT]!!.second,
            b.tight to edges[Range.TARGET]!!.second, b.high to edges[Range.HIGH]!!.second, b.veryHigh to edges[Range.VERY_HIGH]!!.second)
        var last = Double.MAX_VALUE
        for ((v, at) in ticks) {
            val ty = min(at + 3, last - 8)
            ops += Op.Text(barX - 4, ty, "$v", 7.5, false, Palette.INK_SOFT, 1)
            last = ty
        }

        val lx = barX + barW + 10
        val vx = x + w * 0.74
        val gx = x + w
        fun row(yy: Double, label: String, value: String, goal: String?, ok: Boolean?, bold: Boolean = false, size: Double = 9.5) {
            ops += Op.Text(lx, yy, label, size, bold, Palette.INK)
            ops += Op.Text(vx, yy, value, size, true, if (ok == false) Palette.MISSED else Palette.INK, 1)
            if (goal != null) ops += Op.Text(gx, yy, "goal $goal ${goalText(ok == true)}".trimEnd(), 8.0, false, if (ok == true) Palette.TIGHT else Palette.INK_SOFT, 1)
        }
        row(y + 8, "Very high  > ${b.veryHigh}", PageText.share(m.veryHigh, 0.05), "< 5 %", m.veryHigh < 0.05)
        row(y + 24, "High  ${b.high + 1}–${b.veryHigh}", PageText.share(m.high), null, null)
        row(y + 40, "Above range", PageText.share(m.above, 0.25), "< 25 %", m.above < 0.25, true)
        row(y + 70, "In range  ${b.low}–${b.high}", PageText.share(m.inRange, 0.70, true), "> 70 %", m.inRange > 0.70, true, 11.0)
        row(y + 86, "In tight range  ${b.low}–${b.tight}", PageText.share(m.tight, 0.50, true), "> 50 %", m.tight > 0.50)
        row(y + 116, "Below range", PageText.share(m.below, 0.04), "< 4 %", m.below < 0.04, true)
        row(y + 132, "Low  ${b.veryLow}–${b.low - 1}", PageText.share(m.low), null, null)
        row(y + 148, "Very low  < ${b.veryLow}", PageText.share(m.veryLow, 0.01), "< 1 %", m.veryLow < 0.01)
        ops += Op.Text(lx, y + 166, "mg/dL · each 1 % of the time is about 15 minutes a day", 7.5, false, Palette.INK_SOFT)
    }

    private fun metrics(ops: MutableList<Op>, r: AgpReport, x: Double, y: Double, w: Double) {
        val m = r.metrics
        fun row(yy: Double, label: String, sub: String, value: String, unit: String, goal: String, ok: Boolean) {
            ops += Op.Text(x + 8, yy, label, 10.0, true, Palette.INK)
            ops += Op.Text(x + 8, yy + 13, sub, 8.0, false, Palette.INK_SOFT)
            ops += Op.Text(x + w - 8 - (if (unit.isEmpty()) 0.0 else 34.0), yy + 2, value, 16.0, true, if (ok) Palette.INK else Palette.MISSED, 1)
            if (unit.isNotEmpty()) ops += Op.Text(x + w - 8, yy + 2, unit, 8.5, false, Palette.INK_SOFT, 1)
            ops += Op.Text(x + w - 8, yy + 16, "goal $goal ${goalText(ok)}".trimEnd(), 8.0, false, if (ok) Palette.TIGHT else Palette.INK_SOFT, 1)
            ops += Op.Line(x + 8, yy + 24, x + w - 8, yy + 24, Palette.GRID, 0.5)
        }
        row(y + 8, "Average glucose", "Time-weighted mean", "${m.mean.roundToInt()}", "mg/dL", "< 154 mg/dL", m.mean < 154)
        row(y + 48, "Glucose management indicator", "GMI, estimates A1C from the average · %.0f mmol/mol".format(Locale.UK, m.gmiMmolMol),
            "%.1f %%".format(Locale.UK, m.gmi), "", "< 7 %", m.gmi < 7.0)
        row(y + 88, "Glucose variability", "Coefficient of variation (CV)", "%.1f %%".format(Locale.UK, m.cv), "", "≤ 36 %", m.cv <= 36.0)
        ops += Op.Text(x + 8, y + 132, "Sensor data", 10.0, true, Palette.INK)
        ops += Op.Text(x + 8, y + 145, "%.0f hours on %d days · %d readings".format(Locale.UK, r.sufficiency.hours, r.sufficiency.daysWithData, m.readings), 8.0, false, Palette.INK_SOFT)
    }

    /** y range boundaries of each [Range], in mg/dL, for clipping. */
    private fun rangeSpans(b: Bands) = listOf(
        Range.VERY_LOW to (0.0 to b.veryLow.toDouble()), Range.LOW to (b.veryLow.toDouble() to b.low.toDouble()),
        Range.TIGHT to (b.low.toDouble() to b.tight.toDouble()), Range.TARGET to (b.tight.toDouble() to b.high.toDouble()),
        Range.HIGH to (b.high.toDouble() to b.veryHigh.toDouble()), Range.VERY_HIGH to (b.veryHigh.toDouble() to Y_MAX))

    /** Uniform Catmull-Rom through the bin centres around the circular day, sampled every [step] minutes over 0..1440. */
    fun curve(bins: List<AgpBin>, k: Int, step: Int = 5): DoubleArray {
        val n = bins.size
        val bw = 1440.0 / n
        fun v(i: Int) = bins[((i % n) + n) % n].values()[k]!!
        val out = DoubleArray(1440 / step + 1)
        for (j in out.indices) {
            val minute = j * step.toDouble()
            val u = (minute - bw / 2) / bw // knot index space
            val i = kotlin.math.floor(u).toInt()
            val t = u - i
            val p0 = v(i - 1); val p1 = v(i); val p2 = v(i + 1); val p3 = v(i + 2)
            out[j] = 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t * t + (-p0 + 3 * p1 - 3 * p2 + p3) * t * t * t)
        }
        return out
    }

    private fun profileChart(ops: MutableList<Op>, r: AgpReport, x: Double, y: Double, w: Double, h: Double) {
        val b = r.bands
        fun yy(mg: Double) = y + h - (mg.coerceIn(0.0, Y_MAX) / Y_MAX) * h
        fun xx(minute: Double) = x + minute / 1440.0 * w
        ops += Op.Rect(x, y, w, h, 0xFFFFFFFF.toInt())
        for (hr in 0..24 step 3) {
            ops += Op.Line(xx(hr * 60.0), y, xx(hr * 60.0), y + h, Palette.GRID, if (hr % 12 == 0) 0.8 else 0.4)
            ops += Op.Text(xx(hr * 60.0), y + h + 11, "%02d:00".format(hr), 7.5, hr % 12 == 0, Palette.INK_SOFT, 0)
        }
        // Percentiles, monotone at every sample (a spline can't cross its neighbour)
        val c = Array(5) { curve(r.profile, it) }
        for (j in c[0].indices) {
            c[1][j] = max(c[1][j], c[0][j]); c[2][j] = max(c[2][j], c[1][j]); c[3][j] = max(c[3][j], c[2][j]); c[4][j] = max(c[4][j], c[3][j])
        }
        val step = 1440.0 / (c[0].size - 1)
        fun band(lo: DoubleArray, hi: DoubleArray): DoubleArray {
            val pts = DoubleArray(lo.size * 4)
            for (j in hi.indices) { pts[2 * j] = xx(j * step); pts[2 * j + 1] = yy(hi[j]) }
            for (j in lo.indices) { val q = lo.lastIndex - j; pts[2 * (hi.size + j)] = xx(q * step); pts[2 * (hi.size + j) + 1] = yy(lo[q]) }
            return pts
        }
        val outer = band(c[0], c[4])
        val iqr = band(c[1], c[3])
        val med = DoubleArray(c[2].size * 2) { if (it % 2 == 0) xx((it / 2) * step) else yy(c[2][it / 2]) }
        for ((range, span) in rangeSpans(b)) {
            val top = yy(span.second); val bottom = yy(span.first)
            ops += Op.Clip(x, top, w, bottom - top)
            val col = Palette.of(range)
            ops += Op.Poly(outer, true, Palette.tint(col, 0.18), null)
            ops += Op.Poly(iqr, true, Palette.tint(col, 0.42), null)
            ops += Op.Poly(med, false, null, col, 2.0)
            ops += Op.Unclip
        }
        // Range lines over the bands: the target range in green, the others thin grey
        for (v in listOf(b.veryLow, b.veryHigh)) ops += Op.Line(x, yy(v.toDouble()), x + w, yy(v.toDouble()), Palette.GRID, 0.6)
        ops += Op.Line(x, yy(b.tight.toDouble()), x + w, yy(b.tight.toDouble()), Palette.tint(Palette.TIGHT, 0.5), 0.5, 2.0)
        for (v in listOf(b.low, b.high)) ops += Op.Line(x, yy(v.toDouble()), x + w, yy(v.toDouble()), Palette.TIGHT, 1.0)
        ops += Op.Line(x, y + h, x + w, y + h, Palette.INK_SOFT, 0.6)
        for (v in listOf(0, b.veryLow, b.low, b.tight, b.high, b.veryHigh, 350)) {
            ops += Op.Text(x - 4, yy(v.toDouble()) + 3, "$v", 7.5, v == b.low || v == b.high, if (v == b.low || v == b.high) Palette.TIGHT else Palette.INK_SOFT, 1)
        }
        ops += Op.Text(x - 4, y - 4, "mg/dL", 7.0, false, Palette.INK_SOFT, 1)
        // Percentile labels at the right edge, nudged apart so they never overlap
        val ends = c.map { yy(it.last()) + 3 }.toMutableList()
        for (i in 1 until 5) ends[i] = min(ends[i], ends[i - 1] - 9)
        for (i in 3 downTo 0) ends[i] = max(ends[i], ends[i + 1] + 9)
        listOf("5 %", "25 %", "50 %", "75 %", "95 %").forEachIndexed { i, t ->
            ops += Op.Text(x + w + 4, ends[i], t, 7.5, i == 2, if (i == 2) Palette.INK else Palette.INK_SOFT)
        }
    }

    private fun daily(ops: MutableList<Op>, r: AgpReport, x: Double, y: Double, w: Double, hAll: Double) {
        val b = r.bands
        val days = r.days.takeLast(14)
        val cols = 7
        val rows = (days.size + cols - 1) / cols
        val cw = w / cols
        val head = 12.0
        val ch = min(80.0, (hAll - rows * (head + 6)) / rows)
        val top = 300.0 // the strip's scale, mg/dL (the profile's 350 would flatten the strips)
        days.forEachIndexed { i, d ->
            val cx = x + (i % cols) * cw
            val rowY = y + (i / cols) * (ch + head + 6)
            if (i < cols) ops += Op.Text(cx + cw / 2, rowY - 2, d.weekday.getDisplayName(TextStyle.FULL, Locale.UK), 8.0, false, Palette.INK_SOFT, 0)
            val cy = rowY + 2
            fun yy(mg: Double) = cy + ch - (mg.coerceIn(0.0, top) / top) * ch
            fun xx(f: Double) = cx + f * cw
            ops += Op.Rect(cx, cy, cw, ch, 0xFFFFFFFF.toInt())
            ops += Op.Rect(cx, yy(b.high.toDouble()), cw, yy(b.low.toDouble()) - yy(b.high.toDouble()), Palette.SECTION)
            ops += Op.Line(cx + d.noon * cw, cy, cx + d.noon * cw, cy + ch, Palette.GRID, 0.4)
            for (seg in d.segments) {
                val line = DoubleArray(seg.size * 2) { if (it % 2 == 0) xx(seg[it / 2].x) else yy(seg[it / 2].sgv.toDouble()) }
                fun area(base: Double): DoubleArray = line + doubleArrayOf(xx(seg.last().x), yy(base), xx(seg.first().x), yy(base))
                // Highs filled down to the high line, lows up to the low line; the line itself coloured by range
                ops += Op.Clip(cx, cy, cw, yy(b.high.toDouble()) - cy)
                ops += Op.Poly(area(b.high.toDouble()), true, Palette.tint(Palette.HIGH, 0.35), null)
                ops += Op.Unclip
                ops += Op.Clip(cx, yy(b.low.toDouble()), cw, cy + ch - yy(b.low.toDouble()))
                ops += Op.Poly(area(b.low.toDouble()), true, Palette.tint(Palette.LOW, 0.35), null)
                ops += Op.Unclip
                for ((range, span) in rangeSpans(b)) {
                    val t = yy(min(span.second, top)); val bt = yy(span.first)
                    if (bt - t <= 0) continue
                    ops += Op.Clip(cx, t, cw, bt - t)
                    ops += Op.Poly(line, false, null, Palette.of(range), 0.8)
                    ops += Op.Unclip
                }
            }
            ops += Op.Line(cx, cy, cx, cy + ch, Palette.GRID, 0.6)
            if (i % cols == cols - 1 || i == days.lastIndex) ops += Op.Line(cx + cw, cy, cx + cw, cy + ch, Palette.GRID, 0.6)
            ops += Op.Line(cx, cy, cx + cw, cy, Palette.GRID, 0.6)
            ops += Op.Line(cx, cy + ch, cx + cw, cy + ch, Palette.GRID, 0.6)
            ops += Op.Text(cx + 3, cy + 9, "${d.date.dayOfMonth}", 7.5, true, Palette.INK_SOFT)
            if (d.segments.isEmpty()) ops += Op.Text(cx + cw / 2, cy + ch / 2 + 3, "no data", 7.0, false, Palette.INK_SOFT, 0)
            if (i % cols == 0) {
                ops += Op.Text(cx - 3, yy(b.high.toDouble()) + 3, "${b.high}", 6.5, false, Palette.INK_SOFT, 1)
                ops += Op.Text(cx - 3, yy(b.low.toDouble()) + 3, "${b.low}", 6.5, false, Palette.INK_SOFT, 1)
            }
            if (i >= days.size - cols) ops += Op.Text(cx + d.noon * cw, cy + ch + 9, "12:00", 6.5, false, Palette.INK_SOFT, 0)
        }
    }
}
