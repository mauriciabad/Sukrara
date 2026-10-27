// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.core.model.Treatment
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.GlucosePoint
import app.mauri.sukrara.graph.stats.GlucoseStats
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The glucose chart, on a plain [Canvas] so Compose, widgets and the notification (bitmaps) show the same picture.
 * Left to right: the % column (below / in / above range), the distribution growing from the plot's left edge (rows of
 * dots; their outline is erased from the lines under them, DST_OUT, so transparent surfaces stay transparent), the
 * plot (bands, grid, level lines, the glucose line, treatment marks) and the values column. Only [GlucoseCard] calls it.
 */
object GlucoseGraph {
    /** Narrowest chart (dp) that still gets the % column. */
    const val PCT_MIN_WIDTH_DP = 240f

    /** The outline erased around the sensor badge (dp). */
    const val BADGE_CUT_DP = 2f

    /** The longest window (s) whose treatment marks get labels (layout/rules.json "treatmentLabels"). */
    const val LABELS_MAX_S = 24 * 3600L

    /** Readings per dp of plot from which the line is drawn as columns. */
    const val DENSE_PER_DP = 6f

    /** The Now pill's height and its inset from the plot's top-right corner (dp). */
    const val NOW_PILL_DP = 22f
    const val NOW_INSET_DP = 4f

    /** Height of the sensor badge in the hour-label row (dp). */
    const val SENSOR_DP = 10f

    /** The side columns' margin (dp) on each side of their widest label (layout/rules.json "gaps"). */
    const val SIDE_GAP_DP = 3f

    /** The distribution: row pitch, dot radius, erased outline (layout/rules.json "distribution"). */
    const val PROFILE_PITCH = 3.6f
    const val PROFILE_R = 1.35f
    const val PROFILE_CUT = 3f

    /** The chart's inputs beyond the style: what to draw and for whom. */
    internal class Input(
        val readings: List<GlucosePoint>,
        val treatments: List<Treatment>,
        val nowS: Long,
        val muteUntil: ((Treatment) -> Long?)?,
        val words: CardWords,
        val latestT: Long?,
    )

    /** The plot's geometry for one candidate values-column width (the values shown depend on the plot's height). */
    private class Geo(
        val left: Float,
        val right: Float,
        val layout: TreatmentMarks.Layout?,
        val lane1: Float,
        val textRow: Boolean,
        val top: Float,
        val bottom: Float,
        val plotBottom: Float,
        val values: List<Pair<Int, Float>>,
        val valW: Float,
    )

    internal fun draw(
        cv: Canvas,
        area: RectF,
        input: Input,
        st: GraphStyle,
        pal: GraphPalette,
        d: Float,
        sensor: SensorBadge.Look?,
        rec: CardLayout,
        view: ChartView?,
    ) {
        val nowS = input.nowS
        // the window drawn: the view's (scrolled) or the last windowS up to now; everything is about it only
        val end = view?.endS?.coerceAtMost(nowS) ?: nowS
        val from = end - st.windowS
        val b = st.bands.sane()
        val pts = GlucoseStats.window(input.readings, from - 60, end)
        val tir = GlucoseStats.timeInRange(pts, from, end, b)
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.inkSoft
                textSize = 10 * d
            }
        val pctBig =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.ink
                textSize = 13 * d
                typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            }
        val pctSmall =
            Paint(pctBig).apply {
                textSize = 8.5f * d
                color = pal.inkSoft
                typeface = Typeface.DEFAULT
            }
        val labels = st.axisLabels
        val (lo, inr, hi) = pct3(tir.below, tir.inRange, tir.above)

        fun pctW(v: Int) = pctBig.measureText("$v") + pctSmall.measureText("%")
        val pctLabelW = maxOf(pctW(lo), pctW(inr), pctW(hi))
        // labels only up to LABELS_MAX_S (zoomed far out they'd be crowds); points and mute lines follow the settings
        val show = if (st.windowS > LABELS_MAX_S) st.marks.withoutLabels() else st.marks
        val muteUntil = input.muteUntil
        val shown =
            if (!st.treatments) {
                emptyList()
            } else {
                input.treatments.filter {
                    !it.deleted && it.t in from..end && show.any(it.k) &&
                        (
                            show.has(it.k, MarkShow.POINT) || (show.label(it.k) && TreatmentMarks.amount(it) != null) ||
                                (show.has(it.k, MarkShow.LINE) && muteUntil?.invoke(it) != null)
                        )
                }
            }
        val marks = shown.isNotEmpty()
        val lane = shown.any { show.has(it.k, MarkShow.POINT) || (show.has(it.k, MarkShow.LINE) && muteUntil?.invoke(it) != null) }
        val natural = yRange(pts, from, b)
        val (yMin, yMax) = view?.y ?: natural
        rec.add("chart.y", info = "${natural.first.roundToInt()}-${natural.second.roundToInt()}", exact = true)
        // the values on the right: low and high always, then very good, very low / very high and the extra lines,
        // each only where it doesn't touch one already written
        val candidates =
            if (!labels) {
                emptyList()
            } else {
                (
                    listOf(b.low, b.high) + listOfNotNull(b.tight.takeIf { it < b.high }) + listOf(b.veryLow, b.veryHigh, b.extremelyHigh) +
                        st.lines
                ).filter { it > yMin && it < yMax }
            }

        fun axisValues(
            top: Float,
            plotBottom: Float,
        ): List<Pair<Int, Float>> {
            val out = ArrayList<Pair<Int, Float>>()
            for (v in candidates) {
                val ly = plotBottom - (v - yMin) / (yMax - yMin) * (plotBottom - top)
                if (out.none { abs(it.second - ly) < text.textSize * 1.2f }) out += v to ly
            }
            return out
        }

        fun valuesW(vs: List<Int>) = vs.maxOfOrNull { text.measureText("$it") } ?: 0f

        fun geo(valW: Float): Geo {
            val (left, right) = plotX(area.left, area.right, st.side && !tir.empty, labels, d, pctLabelW, valW)
            val layout =
                if (marks) {
                    TreatmentMarks.layout(
                        shown,
                        { t -> left + (t - from).toFloat() / st.windowS * (right - left) },
                        nowS,
                        area.left,
                        area.right,
                        lanes(area.height(), d),
                        d,
                        input.words,
                        show,
                    )
                } else {
                    null
                }
            val lane1 = if (layout?.twoLanes == true) TreatmentMarks.LANE_DP * d else 0f
            val textRow = labels || layout?.labels?.isNotEmpty() == true
            val top = area.top + 3 * d
            val bottom = area.bottom - (if (textRow) 14 * d else 0f) - lane1
            val plotBottom = if (lane) bottom - 12 * d else bottom
            return Geo(left, right, layout, lane1, textRow, top, bottom, plotBottom, axisValues(top, plotBottom), valW)
        }
        // settle on the values actually written (one or two more rounds at most)
        var g = geo(valuesW(candidates))
        repeat(3) {
            val w = valuesW(g.values.map { it.first })
            if (w != g.valW) g = geo(w)
        }
        val left = g.left
        val right = g.right
        val pctCol = left - area.left

        fun x(t: Long) = left + (t - from).toFloat() / st.windowS * (right - left)
        val layout = g.layout
        val top = g.top
        // a scrolling surface eases the plot's bottom instead of letting it jump; lanes and label rows follow it
        val plotBottom = view?.plotInset?.let { (area.bottom - it).coerceIn(top + 20 * d, area.bottom) } ?: g.plotBottom
        val eased = view?.plotInset != null
        val lane1 =
            if (!eased) {
                g.lane1
            } else {
                (area.bottom - (if (g.textRow) 14 * d else 0f) - (if (lane) 12 * d else 0f) - plotBottom).coerceIn(
                    0f,
                    TreatmentMarks.LANE_DP * d,
                )
            }
        val bottom =
            if (!eased) {
                g.bottom
            } else if (lane) {
                plotBottom + 12 * d
            } else {
                plotBottom
            }
        val values = if (plotBottom == g.plotBottom) g.values else axisValues(top, plotBottom)
        rec.add("chart", area, "${st.windowS / 3600}h", exact = true)
        if (end < nowS) rec.add("chart.window", info = "${(from - nowS) / 60}m..${(end - nowS) / 60}m", exact = true)
        if (right - left < 20 * d || plotBottom - top < 20 * d) {
            rec.add("chart.tooSmall", area, exact = true)
            sensor?.let { rec.add("chart.sensor", info = "${it.label} (no room)", exact = true) }
            return
        }
        rec.add("chart.plot", RectF(left, top, right, plotBottom), exact = true)
        rec.add("chart.plot.natural", RectF(left, top, right, g.plotBottom), exact = true)
        if (lane1 > 0) rec.add("chart.lane2", exact = true)
        // the whole chart in one layer: the label halos are erased from it (on any background)
        val chartLayer = cv.saveLayer(area.left - 6 * d, area.top, area.right + 6 * d, area.bottom, null)

        fun y(v: Float) = plotBottom - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (plotBottom - top)
        val sideW = if (st.side && !tir.empty) min((right - left) * 0.26f, 130 * d) else 0f
        drawBands(cv, b, pal, left, right, ::y)
        val grid =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = pal.grid
                strokeWidth = 1 * d
                pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
            }
        drawHours(cv, from, end, st, left, right, top, plotBottom, sideW, area.bottom - 2 * d - lane1, d, text, grid, layout, rec, ::x)
        // the values on the right, left-aligned SIDE_GAP_DP after the plot
        for ((v, ly) in values) {
            val s = "$v"
            val base = ly + text.textSize / 3
            val lx = right + SIDE_GAP_DP * d
            cv.drawText(s, lx, base, text)
            rec.add("chart.values.label", RectF(lx, base - text.textSize * 0.72f, lx + text.measureText(s), base), s, exact = true)
        }
        if (values.isNotEmpty()) {
            rec.add(
                "chart.values",
                RectF(right, top, area.right, plotBottom),
                values.joinToString(",") { "${it.first}" } + " [w" + CardLayout.dp(area.right - right, d) + "]",
                exact = true,
            )
        }
        // everything the distribution cuts goes into one layer: the dashed level lines and the glucose line
        val layer = cv.saveLayer(area, null)
        for (v in listOf(b.veryLow, b.veryHigh, b.extremelyHigh)) {
            if (v > yMin &&
                v < yMax
            ) {
                cv.drawLine(left, y(v.toFloat()), right, y(v.toFloat()), grid)
            }
        }
        val userLine = Paint(grid).apply { color = ColorUtils.setAlphaComponent(pal.inkSoft, 0x66) }
        val lines = st.lines.filter { it > yMin && it < yMax }
        for (v in lines) cv.drawLine(left, y(v.toFloat()), right, y(v.toFloat()), userLine)
        if (lines.isNotEmpty()) rec.add("chart.lines", info = lines.joinToString(","), exact = true)
        drawLine(cv, pts, from, b, pal, d, left, right, input.latestT, rec, ::x, ::y)
        val rows =
            if (sideW >
                0
            ) {
                profileRows(GlucoseStats.profile(pts, from, end, b, tir), b, pal, left, sideW, top, plotBottom, yMin, yMax, d)
            } else {
                emptyList()
            }
        if (rows.isNotEmpty()) {
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_OUT }
            val h = PROFILE_R * d + PROFILE_CUT * d
            for (row in rows) cv.drawRoundRect(area.left, row.cy - h, row.endX + PROFILE_CUT * d, row.cy + h, h, h, cut)
            rec.add(
                "chart.distribution.cut",
                RectF(area.left, rows.minOf { it.cy } - h, rows.maxOf { it.endX } + PROFILE_CUT * d, rows.maxOf { it.cy } + h),
                exact = true,
            )
            rec.add(
                "chart.distribution.dots",
                RectF(left, rows.minOf { it.cy } - PROFILE_R * d, rows.maxOf { it.endX }, rows.maxOf { it.cy } + PROFILE_R * d),
                exact = true,
            )
        }
        cv.restoreToCount(layer)
        if (rows.isNotEmpty()) rec.add("chart.distribution", RectF(left, top, left + sideW, plotBottom), exact = true)
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        for (row in rows) {
            dot.color = row.color
            for (k in 0 until row.n) cv.drawCircle(left + PROFILE_PITCH * d * (k + 0.5f), row.cy, PROFILE_R * d, dot)
        }
        if (pctCol >
            0
        ) {
            drawPct(
                cv,
                lo,
                inr,
                hi,
                tir.let { TirMark.column(it) } && st.crown,
                b,
                pctBig,
                pctSmall,
                pal,
                area,
                left,
                top,
                plotBottom,
                d,
                rec,
                ::y,
            )
        }
        if (marks) {
            rec.add("chart.markers", info = "${shown.count { show.has(it.k, MarkShow.POINT) }}", exact = true)
            rec.add(
                "chart.marks",
                info =
                    shown.sortedBy { it.id }.joinToString(" ") { tr ->
                        tr.id + ":" +
                            MarkShow
                                .parts(tr.k)
                                .filter { p ->
                                    show.has(tr.k, p) && (p != MarkShow.LINE || muteUntil?.invoke(tr) != null) &&
                                        ((p != MarkShow.AMOUNT && p != MarkShow.TIME) || TreatmentMarks.amount(tr) != null)
                                }.joinToString("+")
                    },
                exact = true,
            )
            layout?.labels?.forEach { l ->
                val labelBottom = if (l.lane == 0) area.bottom - lane1 else area.bottom
                rec.add(
                    "chart.label",
                    RectF(l.left, labelBottom - 14 * d, l.left + l.w, labelBottom),
                    "${l.text} lane${l.lane}",
                    exact = true,
                )
            }
            TreatmentMarks.draw(
                cv,
                shown,
                ::x,
                left,
                right,
                bottom,
                d,
                pal.dark,
                layout,
                base0 = area.bottom - 2 * d - lane1,
                base1 = area.bottom - 2 * d,
                sep = pal.inkSoft,
                nowS = nowS,
                muteUntil = muteUntil,
                show = show,
            )
        }
        if (view != null && view.now > 0f) drawNowPill(cv, view.now, right, top, pal, d, input.words, rec)
        // the sensor badge at the start of the hour labels' row, drawn last over what passes under it (its outline is
        // erased), so it never moves a label (labels are laid out as if it weren't there)
        if (sensor != null && labels) {
            val sy = sensorTop(area.bottom, lane1, d)
            val box = RectF(area.left, sy, area.left + sensorWidth(sensor.label, d), sy + SENSOR_DP * d)
            val c = BADGE_CUT_DP * d
            cv.drawRoundRect(
                RectF(box.left - c, box.top - c, box.right + c, box.bottom + c),
                box.height() / 2 + c,
                box.height() / 2 + c,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_OUT },
            )
            SensorBadge.draw(cv, area.left, sy, SENSOR_DP * d, sensor, pal)
            rec.add("chart.sensor", box, sensor.label, exact = true)
        }
        cv.restoreToCount(chartLayer)
    }

    /** The in-range band across the plot; its very good part (low…tight) a little stronger, in its own colour. */
    private fun drawBands(
        cv: Canvas,
        b: Bands,
        pal: GraphPalette,
        left: Float,
        right: Float,
        y: (Float) -> Float,
    ) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val split = b.tight < b.high
        val tightTop = y((if (split) b.tight else b.high).toFloat())
        fill.color = ColorUtils.setAlphaComponent(pal.tight, if (split) (if (pal.dark) 0x2A else 0x24) else (if (pal.dark) 0x1E else 0x18))
        cv.drawRect(left, tightTop, right, y(b.low.toFloat()), fill)
        if (split) {
            fill.color = ColorUtils.setAlphaComponent(pal.target, if (pal.dark) 0x16 else 0x14)
            cv.drawRect(left, y(b.high.toFloat()), right, tightTop, fill)
        }
    }

    /** Hour grid + time labels: the user's step, or a longer one where labels would come closer than ~44 dp. */
    private fun drawHours(
        cv: Canvas,
        from: Long,
        end: Long,
        st: GraphStyle,
        left: Float,
        right: Float,
        top: Float,
        plotBottom: Float,
        sideW: Float,
        baseline: Float,
        d: Float,
        text: Paint,
        grid: Paint,
        layout: TreatmentMarks.Layout?,
        rec: CardLayout,
        x: (Long) -> Float,
    ) {
        val step = gridStep((right - left) / (st.windowS / 3600f), st.gridHours, d)
        // from a day up the lines fall on midnights and the labels are dates, fixed to the calendar so they don't shift
        val cal =
            Calendar.getInstance().apply {
                timeInMillis = from * 1000
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                add(Calendar.HOUR_OF_DAY, 1)
                while (!onStep(this, step)) add(Calendar.HOUR_OF_DAY, 1)
            }
        var labelEnd = Float.NEGATIVE_INFINITY
        var anyLabel = false
        while (cal.timeInMillis / 1000 <= end) {
            val gx = x(cal.timeInMillis / 1000)
            if (gx > left + sideW * 0.6f) {
                if (st.gridHours > 0) cv.drawLine(gx, top, gx, plotBottom, grid)
                if (st.axisLabels) {
                    val s = if (step >= 24) Clock.dayLabel(cal.timeInMillis / 1000) else Clock.hourLabel(cal.get(Calendar.HOUR_OF_DAY))
                    val tw = text.measureText(s)
                    val lx = (gx - tw / 2).coerceIn(left, right - tw)
                    // a label squeezed against its neighbour is left out; a treatment label wins
                    if (lx > labelEnd + 6 * d && layout?.blocks(lx - 3 * d, lx + tw + 3 * d) != true) {
                        cv.drawText(s, lx, baseline, text)
                        rec.add("time.text", info = s)
                        if (!anyLabel) rec.add("chart.hours")
                        anyLabel = true
                        labelEnd = lx + tw
                    }
                }
            }
            if (step >= 24) {
                cal.add(Calendar.DAY_OF_MONTH, 1)
                while (!onStep(cal, step)) cal.add(Calendar.DAY_OF_MONTH, 1)
            } else {
                cal.add(Calendar.HOUR_OF_DAY, step)
            }
        }
        if (st.axisLabels && !anyLabel) rec.add("chart.hours", info = "(no room)")
    }

    /**
     * The line: one dot per reading in its band's colour, joined where they're spaced out. Zoomed far out (more than
     * [DENSE_PER_DP] readings per dp) each 1 dp column draws one stroke from its lowest to its highest reading instead,
     * split where it crosses a band: the same picture in a few hundred strokes.
     */
    private fun drawLine(
        cv: Canvas,
        pts: List<GlucosePoint>,
        from: Long,
        b: Bands,
        pal: GraphPalette,
        d: Float,
        left: Float,
        right: Float,
        latestT: Long?,
        rec: CardLayout,
        x: (Long) -> Float,
        y: (Float) -> Float,
    ) {
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val seg =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                strokeWidth = 1.6f * d
                strokeCap = Paint.Cap.ROUND
            }
        val spacing = if (pts.size > 1) (right - left) / max(1f, pts.size.toFloat()) else 99f
        val r = (spacing * 0.55f).coerceIn(1.3f * d, 2.2f * d)
        val dense = pts.size > DENSE_PER_DP * (right - left) / d
        if (dense) {
            rec.add("chart.line", info = "columns", exact = true)
            seg.strokeWidth = 1.2f * d // the columns just touch: an envelope, not bars
            val edges = intArrayOf(b.veryLow, b.low, b.tight, b.high, b.veryHigh, b.extremelyHigh)
            var k = pts.indexOfFirst { it.t >= from }.coerceAtLeast(0)
            while (k < pts.size) {
                val col = floor((x(pts[k].t) - left) / d)
                var lo = pts[k].sgv
                var hi = lo
                var j = k + 1
                while (j < pts.size && floor((x(pts[j].t) - left) / d) == col && pts[j].t - pts[j - 1].t <= GlucoseStats.MAX_GAP_S) {
                    lo = min(lo, pts[j].sgv)
                    hi = max(hi, pts[j].sgv)
                    j++
                }
                val cx = left + (col + 0.5f) * d
                var a = lo
                for (e in edges) {
                    if (e in (a + 1)..hi) {
                        seg.color = pal.at(b, a)
                        cv.drawLine(cx, y(a.toFloat()), cx, y(e.toFloat()), seg)
                        a = e
                    }
                }
                seg.color = pal.at(b, a)
                cv.drawLine(cx, y(a.toFloat()), cx, y(hi.toFloat()), seg)
                k = j
            }
        }
        for (i in pts.indices) {
            if (dense && i != pts.lastIndex) continue
            val p = pts[i]
            if (p.t < from) continue
            val px = x(p.t)
            val py = y(p.sgv.toFloat())
            val c = pal.at(b, p.sgv)
            if (spacing > 5 * d && i > 0 && p.t - pts[i - 1].t <= GlucoseStats.MAX_GAP_S) {
                seg.color = ColorUtils.setAlphaComponent(c, 0xBF)
                cv.drawLine(x(pts[i - 1].t), y(pts[i - 1].sgv.toFloat()), px, py, seg)
            }
            if (i == pts.lastIndex && (latestT == null || p.t >= latestT)) {
                // the newest reading: a bigger dot with a background halo
                dot.color = pal.bg
                cv.drawCircle(px, py, r + 2.6f * d, dot)
                dot.color = c
                cv.drawCircle(px, py, r + 1.4f * d, dot)
            } else {
                dot.color = c
                cv.drawCircle(px, py, r, dot)
            }
        }
    }

    /** The % per major band, centred on its band, pushed apart when too close; the crown over a perfect 100. */
    private fun drawPct(
        cv: Canvas,
        lo: Int,
        inr: Int,
        hi: Int,
        crown: Boolean,
        b: Bands,
        pctBig: Paint,
        pctSmall: Paint,
        pal: GraphPalette,
        area: RectF,
        left: Float,
        top: Float,
        plotBottom: Float,
        d: Float,
        rec: CardLayout,
        y: (Float) -> Float,
    ) {
        val gap = pctBig.textSize * 1.05f
        val cIn = (y(b.high.toFloat()) + y(b.low.toFloat())) / 2
        val cHi = min((top + y(b.high.toFloat())) / 2, cIn - gap)
        val cLo = max((y(b.low.toFloat()) + plotBottom) / 2, cIn + gap)

        fun label(
            v: Int,
            cy: Float,
        ) {
            val s = "$v"
            val w = pctBig.measureText(s) + pctSmall.measureText("%")
            val x0 = left - SIDE_GAP_DP * d - w
            val base = cy + pctBig.textSize * 0.36f
            cv.drawText(s, x0, base, pctBig)
            cv.drawText("%", x0 + pctBig.measureText(s), base, pctSmall)
            rec.add("chart.pct.label", RectF(x0, base - pctBig.textSize * 0.72f, x0 + w, base), s, exact = true)
        }
        label(hi, cHi)
        label(inr, cIn)
        label(lo, cLo)
        rec.add(
            "chart.pct",
            RectF(area.left, top, left, plotBottom),
            "$lo/$inr/$hi [w" + CardLayout.dp(left - area.left, d) + "]",
            exact = true,
        )
        if (!crown) return
        val numW = pctBig.measureText("100")
        val cw = numW * 0.62f
        val bottom = cIn + pctBig.textSize * (0.36f - 0.72f) - 1.5f * d
        if (bottom - cw * Crown.H >= cHi + pctBig.textSize * 0.36f + 2 * d) {
            Crown.draw(cv, left - SIDE_GAP_DP * d - pctSmall.measureText("%") - numW / 2, bottom, cw, pal.tight)
            rec.add("chart.pct.crown")
        } else {
            rec.add("chart.pct.crown", info = "(no room)")
        }
    }

    /** The "Now" pill of a scrolled chart: top right in the plot, fading with [visible]; the surface makes it tappable. */
    private fun drawNowPill(
        cv: Canvas,
        visible: Float,
        right: Float,
        top: Float,
        pal: GraphPalette,
        d: Float,
        w: CardWords,
        rec: CardLayout,
    ) {
        val ph = NOW_PILL_DP * d
        val look = StatusBadge.backToNow(w)
        val pw = StatusBadge.width(look, ph, pal.dark)
        val px = right - pw - NOW_INSET_DP * d
        val py = top + NOW_INSET_DP * d
        val a = cv.saveLayerAlpha(px, py, px + pw, py + ph, (visible.coerceIn(0f, 1f) * 255).roundToInt())
        StatusBadge.draw(cv, px, py, ph, pw, look, pal.dark)
        cv.restoreToCount(a)
        rec.add("chart.now", RectF(px, py, px + pw, py + ph), look.text, exact = true)
    }

    /** The step (hours) between grid lines: the user's at least, else the first ≥ 44 dp apart (1 h … a week). */
    fun gridStep(
        pxPerHour: Float,
        gridHours: Int,
        d: Float,
    ): Int = listOf(1, 2, 3, 4, 6, 12, 24, 48, 72, 168).filter { it >= gridHours }.firstOrNull { it * pxPerHour >= 44 * d } ?: 168

    /** Is [c] (on the hour) a line of [step]? Hours divide; days: midnights whose day number divides; a week: Mondays. */
    private fun onStep(
        c: Calendar,
        step: Int,
    ): Boolean =
        when {
            step < 24 -> {
                c.get(Calendar.HOUR_OF_DAY) % step == 0
            }

            c.get(Calendar.HOUR_OF_DAY) != 0 -> {
                false
            }

            step == 168 -> {
                c.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
            }

            step > 24 -> {
                Math.floorDiv(c.timeInMillis + c.get(Calendar.ZONE_OFFSET) + c.get(Calendar.DST_OFFSET), 86_400_000L) % (step / 24) == 0L
            }

            else -> {
                true
            }
        }

    /**
     * The y range (mg/dL) of a window from [from]: always reaching the very low / very high limits (their lines show)
     * and every reading with a margin; the user's lines don't change it.
     */
    fun yRange(
        pts: List<GlucosePoint>,
        from: Long,
        b: Bands,
    ): Pair<Float, Float> {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (p in pts) {
            if (p.t >= from - 60) {
                lo = min(lo, p.sgv)
                hi = max(hi, p.sgv)
            }
        }
        if (lo == Int.MAX_VALUE) {
            lo = b.low
            hi = b.high
        }
        return minOf(b.low - 22, b.veryLow - 6, lo - 6).toFloat().coerceAtLeast(GlucoseStats.MIN_MG.toFloat()) to
            maxOf(b.high + 40, b.veryHigh + 8, hi + 8).toFloat().coerceAtMost(GlucoseStats.MAX_MG.toFloat())
    }

    /** The plot's edges: the % column ([pct], from [PCT_MIN_WIDTH_DP]) and the values column, each widest label + 2 gaps. */
    fun plotX(
        areaLeft: Float,
        areaRight: Float,
        pct: Boolean,
        labels: Boolean,
        d: Float,
        pctW: Float,
        valW: Float,
    ): Pair<Float, Float> {
        val pctCol = if (pct && labels && areaRight - areaLeft >= PCT_MIN_WIDTH_DP * d) pctW + 2 * SIDE_GAP_DP * d else 0f
        return areaLeft + pctCol to areaRight - if (labels) valW + 2 * SIDE_GAP_DP * d else 0f
    }

    /** The top of the sensor badge, centred on the hour labels' x-height (contracts/graph/place). */
    fun sensorTop(
        bottom: Float,
        lane1: Float,
        d: Float,
    ): Float = bottom - 2 * d - lane1 - 3.6f * d - SENSOR_DP * d / 2

    /** The badge's width in the [TextModel] (the room kept free; the same on every platform). */
    fun sensorWidth(
        label: String,
        d: Float,
    ): Float = SENSOR_DP * d * 1.3f + TextModel.width(label, SENSOR_DP * d * 0.95f)

    /** Treatment label lanes for a chart [h] px tall: a second one from [TreatmentMarks.TWO_LANES_DP]. */
    fun lanes(
        h: Float,
        d: Float,
    ) = if (h >= TreatmentMarks.TWO_LANES_DP * d) 2 else 1

    private class ProfileRow(
        val cy: Float,
        val n: Int,
        val color: Int,
        val endX: Float,
    )

    /** The distribution's rows: one per pitch, dots ∝ the row's share of time. */
    private fun profileRows(
        prof: DoubleArray,
        b: Bands,
        pal: GraphPalette,
        left: Float,
        sideW: Float,
        top: Float,
        bottom: Float,
        yMin: Float,
        yMax: Float,
        d: Float,
    ): List<ProfileRow> {
        val pxPerMg = (bottom - top) / (yMax - yMin)

        fun massBetween(
            g0: Float,
            g1: Float,
        ): Double {
            var s = 0.0
            var g = floor(g0).toInt()
            while (g < g1) {
                val k = g - GlucoseStats.MIN_MG
                val overlap = min(g + 1f, g1) - max(g.toFloat(), g0)
                if (k in prof.indices && overlap > 0) s += prof[k] * overlap
                g++
            }
            return s
        }
        val pitch = PROFILE_PITCH * d
        val r = PROFILE_R * d
        val rows = ((bottom - top) / pitch).toInt()
        val mass =
            DoubleArray(rows) { i ->
                val yb = bottom - i * pitch
                massBetween(yMin + (bottom - yb) / pxPerMg, yMin + (bottom - yb + pitch) / pxPerMg)
            }
        val peak = mass.maxOrNull()?.takeIf { it > 0 } ?: return emptyList()
        val maxDots = floor(sideW * 0.9f / pitch).toInt()
        return (0 until rows).mapNotNull { i ->
            val n = (mass[i] / peak * maxDots).roundToInt().let { if (it == 0 && mass[i] > peak * 0.004) 1 else it }
            if (n == 0) return@mapNotNull null
            val cy = bottom - i * pitch - pitch / 2
            val g = yMin + (bottom - cy) / pxPerMg
            ProfileRow(cy, n, pal.at(b, g.toDouble()), left + pitch / 2 + (n - 1) * pitch + r)
        }
    }

    /** Three shares as whole percentages adding up to 100 (largest remainder); the middle is 100 only when it is all. */
    fun pct3(
        a: Double,
        b: Double,
        c: Double,
    ): Triple<Int, Int, Int> {
        val raw = doubleArrayOf(a, b, c).map { it * 100 }
        val total = raw.sum()
        if (total <= 0) return Triple(0, 0, 0)
        val base = raw.map { floor(it).toInt() }.toIntArray()
        var left = total.roundToInt() - base.sum()
        raw.indices.sortedByDescending { raw[it] - base[it] }.forEach {
            if (left > 0) {
                base[it]++
                left--
            }
        }
        if (base[1] == 100 && (a > 0 || c > 0)) {
            base[1]--
            base[if (a >= c) 0 else 2]++
        }
        return Triple(base[0], base[1], base[2])
    }
}
