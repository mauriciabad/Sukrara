// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
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
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treatment
import app.mauri.sukrara.treatments.MarkShow
import app.mauri.sukrara.treatments.TreatmentMarks
import java.util.Calendar
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Colours of every graph surface; [dark] picks the set. Range colours are the same hue family in both. */
data class GraphPalette(
    val dark: Boolean, val bg: Int, val ink: Int, val inkSoft: Int, val grid: Int,
    val veryLow: Int, val low: Int, val tight: Int, val target: Int, val high: Int, val veryHigh: Int, val extremelyHigh: Int,
) {
    /** The colour of a glucose value: its band's, or [extremelyHigh] from [Bands.extremelyHigh] up. */
    fun at(b: Bands, mgdl: Double) = if (mgdl >= b.extremelyHigh) extremelyHigh else of(b.of(mgdl))
    fun at(b: Bands, mgdl: Int) = at(b, mgdl.toDouble())

    fun of(b: Band) = when (b) {
        Band.VERY_LOW -> veryLow; Band.LOW -> low; Band.TIGHT -> tight; Band.TARGET -> target; Band.HIGH -> high
        Band.VERY_HIGH -> veryHigh
    }

    companion object {
        val DARK = GraphPalette(true, bg = 0xFF1C1C1E.toInt(), ink = 0xFFF2F2F2.toInt(), inkSoft = 0xFFA3A3A8.toInt(),
            grid = 0xFF3A3A3E.toInt(), veryLow = 0xFFC2413B.toInt(), low = 0xFFF0524F.toInt(), tight = 0xFF3DD47A.toInt(),
            target = 0xFF8CC63F.toInt(), high = 0xFFF5A524.toInt(), veryHigh = 0xFFFF7A45.toInt(),
            extremelyHigh = 0xFFE35BD0.toInt())
        val LIGHT = GraphPalette(false, bg = 0xFFFFFFFF.toInt(), ink = 0xFF1C1C1E.toInt(), inkSoft = 0xFF6B6B70.toInt(),
            grid = 0xFFDADADF.toInt(), veryLow = 0xFFB3261E.toInt(), low = 0xFFD92D2A.toInt(), tight = 0xFF17A34A.toInt(),
            target = 0xFF6E9E1E.toInt(), high = 0xFFD97706.toInt(), veryHigh = 0xFFC2410C.toInt(),
            extremelyHigh = 0xFFA3238F.toInt())
        fun of(dark: Boolean) = if (dark) DARK else LIGHT
    }
}

/** What one placement of the graph shows. [windowS] = the time span (and the window of the side profile). */
data class GraphStyle(
    val windowS: Long = 12 * 3600L,
    val bands: Bands = Bands(),
    /** The left overlay: the distribution and the % labels. */
    val side: Boolean = true,
    val axisLabels: Boolean = true,
    val treatments: Boolean = true,
    /** Extra dashed lines, mg/dL ([GraphConfig.lines]). */
    val lines: List<Int> = DEFAULT_LINES,
    /** Hours between the vertical lines, at least ([GraphConfig.gridHours]); 0 = none (the time labels stay). */
    val gridHours: Int = 1,
    /** The crown over a perfect 100 in the % column ([GraphConfig.columnCrown]). */
    val crown: Boolean = true,
    /** Which parts of each treatment kind show: point, mute line, amount, time ([GraphConfig.marks]). */
    val marks: MarkShow = MarkShow.DEFAULT,
)

/**
 * A chart scrolled through time (the app panel, the Mac dropdown; the other surfaces can't scroll): the window ends at
 * [endS] (epoch s, at most now) instead of now; [y] = the y range to draw while the surface animates it towards the
 * window's own ([GlucoseCard.yRange]; null = the window's own); [now] = the "Now" pill's visibility 0..1 (0 = none);
 * [plotInset] = the plot's bottom as px above the chart's bottom while the surface eases it (the treatment lane comes and
 * goes with the marks in the window; null = where the layout puts it, "chart.plot.natural").
 */
data class ChartView(val endS: Long, val y: Pair<Float, Float>? = null, val now: Float = 0f, val plotInset: Float? = null)

/**
 * The glucose chart of investigation 16, drawn on a plain [Canvas] so the app (Compose's native canvas), the widgets
 * and the notification (bitmaps) all show the same picture.
 *
 * Left to right: the % of time below / in / above range (labels), then, growing rightwards from the plot's left edge,
 * the distribution of glucose over the same window ([GlucoseStats.profile], rows of dots like the line). The lines
 * underneath (the glucose line, the dashed level lines) are cut around each row of dots, so a row with little time
 * barely touches them and a long one cuts them further in. The cut is erased from the lines' own layer
 * ([BlendMode.DST_OUT]), not painted over in a background colour: widgets and the lock screen are transparent.
 * (The time-in-range rectangles behind the dots were removed: the user found they cluttered the chart.)
 */
object GlucoseGraph {
    /** Narrowest chart (dp) that still gets the % labels beside it. */
    const val PCT_MIN_WIDTH_DP = 240f

    /**
     * [alerts] = the rules behind the treatments' mute lines (null = no lines). [sensor] = the life badge to put in the
     * hour-label row ([sensorTop]); only [GlucoseCard] decides that ([GlucoseCard.sensorSpot]), so only it calls this
     * (tools/check `card-api`). [rec] gets what was laid out.
     */
    fun draw(
        cv: Canvas, area: RectF, readings: List<Reading>, treatments: List<Treatment>, nowS: Long,
        st: GraphStyle, pal: GraphPalette, d: Float, alerts: AlertSettings?,
        sensor: SensorBadge.Look?, rec: CardLayout, view: ChartView? = null, latestT: Long? = null,
    ) {
        // The window drawn: the [view]'s (a scrolled chart) or the last [GraphStyle.windowS] up to now. Everything the
        // chart shows (line, distribution, %, y range, treatments, hour labels) is about this window only.
        val end = view?.endS?.coerceAtMost(nowS) ?: nowS
        val from = end - st.windowS
        val b = st.bands.sane()
        val pts = GlucoseStats.window(readings, from - 60, end)
        val tir = GlucoseStats.timeInRange(pts, from, end, b)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 10 * d }
        val pctBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.ink; textSize = 13 * d; typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        }
        val pctSmall = Paint(pctBig).apply { textSize = 8.5f * d; color = pal.inkSoft; typeface = Typeface.DEFAULT }

        val labels = st.axisLabels
        val (lo, inr, hi) = pct3(tir.below, tir.inRange, tir.above)
        // the % labels as drawn (real font): the column is the widest of them plus SIDE_GAP_DP on each side
        fun pctW(v: Int) = pctBig.measureText("$v") + pctSmall.measureText("%")
        val pctLabelW = maxOf(pctW(lo), pctW(inr), pctW(hi))
        // Treatment labels go under their markers, in the hour labels' row and, when needed, a second row under it.
        // Only the parts the settings show ([GraphStyle.marks]): an entry with nothing shown is left out, and the
        // marker lane is there only when a point or a mute line is drawn in it.
        val show = st.marks
        val muteUntil = alerts?.let { a -> { t: Treatment -> AlertStatus.muteUntil(t, a) } }
        val shown = if (st.treatments) treatments.filter { !it.del && it.t in from..end && show.any(it.k) &&
            (show.has(it.k, MarkShow.POINT) || show.label(it.k) && TreatmentMarks.amount(it) != null ||
                show.has(it.k, MarkShow.LINE) && muteUntil?.invoke(it) != null) } else emptyList()
        val marks = shown.isNotEmpty()
        val lane = shown.any { show.has(it.k, MarkShow.POINT) || show.has(it.k, MarkShow.LINE) && muteUntil?.invoke(it) != null }
        // the sensor badge's place in the hour-label row is kept free of treatment labels
        val badgeRoom = sensor?.takeIf { labels }?.let { area.left to area.left + sensorWidth(it.label, d) + 3 * d }
        // The y range of the window ([yRange]); a scrolling surface animates it towards that and passes the current one.
        val (yMin, yMax) = view?.y ?: yRange(pts, from, b)
        rec.add("chart.y", info = "${yRange(pts, from, b).let { "${it.first.roundToInt()}-${it.second.roundToInt()}" }}", exact = true)
        // The values on the right: low and high always, then very good, very low / very high and the extra lines,
        // each only where it doesn't touch one already written (in the plot from [top] to [plotBottom]).
        val candidates = if (!labels) emptyList() else
            (listOf(b.low, b.high) + listOfNotNull(b.tight.takeIf { it < b.high }) + listOf(b.veryLow, b.veryHigh, b.extremelyHigh) + st.lines)
                .filter { it > yMin && it < yMax }
        fun axisValues(top: Float, plotBottom: Float): List<Pair<Int, Float>> {
            val out = ArrayList<Pair<Int, Float>>()
            for (v in candidates) {
                val ly = plotBottom - (v - yMin) / (yMax - yMin) * (plotBottom - top)
                if (out.none { kotlin.math.abs(it.second - ly) < text.textSize * 1.2f }) out += v to ly
            }
            return out
        }
        fun valuesW(vs: List<Int>) = vs.maxOfOrNull { text.measureText("$it") } ?: 0f
        // The plot's sides: the % column and the values column, each its widest drawn label + SIDE_GAP_DP on each side.
        // Which values show depends on the plot's height, which depends (second treatment lane) on its width: start from
        // every candidate and settle on the values actually written (one or two more rounds at most).
        class Geo(val valW: Float) {
            val lr = plotX(area.left, area.right, st.side && !tir.empty, labels, d, pctLabelW, valW)
            val left = lr.first
            val right = lr.second
            val layout = if (marks) TreatmentMarks.layout(shown, { t -> left + (t - from).toFloat() / st.windowS * (right - left) }, nowS,
                area.left, area.right, lanes(area.height(), d), d, badgeRoom, show) else null
            val lane1 = if (layout?.twoLanes == true) TreatmentMarks.LANE_DP * d else 0f
            val textRow = labels || layout?.labels?.isNotEmpty() == true
            val top = area.top + 3 * d
            val bottom = area.bottom - (if (textRow) 10 * d + 4 * d else 0f) - lane1
            val plotBottom = if (lane) bottom - 12 * d else bottom
            val values = axisValues(top, plotBottom)
        }
        var g = Geo(valuesW(candidates))
        for (round in 0 until 3) {
            val w = valuesW(g.values.map { it.first })
            if (w == g.valW) break
            g = Geo(w)
        }
        val left = g.left
        val right = g.right
        val pctCol = left - area.left
        fun x(t: Long) = left + (t - from).toFloat() / st.windowS * (right - left)
        val layout = g.layout
        val lane1 = g.lane1
        val top = g.top
        val bottom = g.bottom
        // A scrolling surface eases the plot's bottom (the treatment lane comes and goes with the marks in the window)
        // instead of letting the plot jump: [ChartView.plotInset] is the distance from the area's bottom it draws at.
        val plotBottom = view?.plotInset?.let { (area.bottom - it).coerceIn(top + 20 * d, area.bottom) } ?: g.plotBottom
        val values = if (plotBottom == g.plotBottom) g.values else axisValues(top, plotBottom)
        rec.add("chart", area, "${st.windowS / 3600}h", exact = true)
        if (end < nowS) rec.add("chart.window", info = "${(from - nowS) / 60}m..${(end - nowS) / 60}m", exact = true)
        if (right - left < 20 * d || plotBottom - top < 20 * d) {
            rec.add("chart.tooSmall", area, exact = true)
            sensor?.let { rec.add("chart.sensor", info = "${it.label} (no room)", exact = true) }
            return
        }
        rec.add("chart.plot", RectF(left, top, right, plotBottom), exact = true)
        // where the plot ends without easing (what a scrolling surface eases [ChartView.plotInset] towards)
        rec.add("chart.plot.natural", RectF(left, top, right, g.plotBottom), exact = true)
        if (lane1 > 0) rec.add("chart.lane2", exact = true)
        // The whole chart in one layer: the treatment labels' halos are erased from it (on any background). A little
        // wider than the area: a marker at the plot's edge may stick out, as it always could.
        val chartLayer = cv.saveLayer(area.left - 6 * d, area.top, area.right + 6 * d, area.bottom, null)

        fun y(v: Float) = plotBottom - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (plotBottom - top)
        val sideW = if (st.side && !tir.empty) min((right - left) * 0.26f, 130 * d) else 0f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // The in-range band across the whole plot; its very good part (low…tight) a little stronger, in its own colour.
        val split = b.tight < b.high
        val tightTop = y((if (split) b.tight else b.high).toFloat())
        fill.color = ColorUtils.setAlphaComponent(pal.tight, if (split) (if (pal.dark) 0x2A else 0x24) else (if (pal.dark) 0x1E else 0x18))
        cv.drawRect(left, tightTop, right, y(b.low.toFloat()), fill)
        if (split) {
            fill.color = ColorUtils.setAlphaComponent(pal.target, if (pal.dark) 0x16 else 0x14)
            cv.drawRect(left, y(b.high.toFloat()), right, tightTop, fill)
        }

        // Hour grid + time labels: the user's step, or a longer one where labels would come closer than ~44 dp.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.grid; strokeWidth = 1 * d; pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
        }
        val pxPerHour = (right - left) / (st.windowS / 3600f)
        val step = listOf(1, 2, 3, 4, 6, 12, 24).filter { it >= st.gridHours }.firstOrNull { it * pxPerHour >= 44 * d } ?: 24
        val cal = Calendar.getInstance().apply {
            timeInMillis = from * 1000; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
            while (get(Calendar.HOUR_OF_DAY) % step != 0) add(Calendar.HOUR_OF_DAY, 1)
        }
        // The sensor life badge at the start of the hour labels' row, under the % column and the distribution (the user,
        // 2026-10-19/21); hour labels start after it, treatment labels keep clear of it (badgeRoom).
        var labelEnd = Float.NEGATIVE_INFINITY
        if (sensor != null && badgeRoom != null) {
            val sy = sensorTop(area.bottom, lane1, d)
            SensorBadge.draw(cv, area.left, sy, SENSOR_DP * d, sensor, pal)
            rec.add("chart.sensor", RectF(area.left, sy, area.left + sensorWidth(sensor.label, d), sy + SENSOR_DP * d), sensor.label, exact = true)
            labelEnd = badgeRoom.second
        }
        while (cal.timeInMillis / 1000 <= end) {
            val gx = x(cal.timeInMillis / 1000)
            if (gx > left + sideW * 0.6f) {
                if (st.gridHours > 0) cv.drawLine(gx, top, gx, plotBottom, grid)
                if (labels) {
                    val s = Clock.hourLabel(cal.get(Calendar.HOUR_OF_DAY))
                    val tw = text.measureText(s)
                    val lx = (gx - tw / 2).coerceIn(left, right - tw)
                    // a label squeezed against the right edge would touch its neighbour; a treatment label wins
                    if (lx > labelEnd + 6 * d && layout?.blocks(lx - 3 * d, lx + tw + 3 * d) != true) {
                        cv.drawText(s, lx, area.bottom - 2 * d - lane1, text)
                        rec.add("time.text", info = s)
                        if (rec.first("chart.hours") == null) rec.add("chart.hours")
                        labelEnd = lx + tw
                    }
                }
            }
            cal.add(Calendar.HOUR_OF_DAY, step)
        }
        if (labels && rec.first("chart.hours") == null) rec.add("chart.hours", info = "(no room)")
        // The values on the right (axisValues), left-aligned SIDE_GAP_DP after the plot; the widest ends SIDE_GAP_DP
        // before the area's edge.
        for ((v, ly) in values) {
            val s = "$v"
            val base = ly + text.textSize / 3
            cv.drawText(s, right + SIDE_GAP_DP * d, base, text)
            // the drawn label's box (CardLayoutTest checks the column's gaps against it: layout/rules.json "gaps")
            rec.add("chart.values.label", RectF(right + SIDE_GAP_DP * d, base - text.textSize * 0.72f, right + SIDE_GAP_DP * d + text.measureText(s), base), s, exact = true)
        }
        if (values.isNotEmpty()) rec.add("chart.values", RectF(right, top, area.right, plotBottom),
            values.joinToString(",") { "${it.first}" } + " [w" + CardLayout.dp(area.right - right, d) + "]", exact = true)

        // Everything the distribution cuts goes into one layer: the dashed level lines and the glucose line.
        val layer = cv.saveLayer(area, null)
        // Very low / very high / extremely high: faint dashed lines where the y range reaches them. The user's lines (100 by default) a
        // little stronger, so they still show on the in-range band.
        for (v in listOf(b.veryLow, b.veryHigh, b.extremelyHigh)) if (v > yMin && v < yMax) cv.drawLine(left, y(v.toFloat()), right, y(v.toFloat()), grid)
        val userLine = Paint(grid).apply { color = ColorUtils.setAlphaComponent(pal.inkSoft, 0x66) }
        for (v in st.lines) if (v > yMin && v < yMax) cv.drawLine(left, y(v.toFloat()), right, y(v.toFloat()), userLine)
        st.lines.filter { it > yMin && it < yMax }.takeIf { it.isNotEmpty() }?.let { rec.add("chart.lines", info = it.joinToString(","), exact = true) }

        // The line: one dot per reading, coloured by band.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
        val spacing = if (pts.size > 1) (right - left) / max(1f, pts.size.toFloat()) else 99f
        val r = (spacing * 0.55f).coerceIn(1.3f * d, 2.2f * d)
        for (i in pts.indices) {
            val p = pts[i]
            if (p.t < from) continue
            val px = x(p.t); val py = y(p.sgv.toFloat())
            val c = pal.at(b, p.sgv)
            if (spacing > 5 * d && i > 0 && p.t - pts[i - 1].t <= GlucoseStats.MAX_GAP_S) {
                seg.color = ColorUtils.setAlphaComponent(c, 0xBF)
                cv.drawLine(x(pts[i - 1].t), y(pts[i - 1].sgv.toFloat()), px, py, seg)
            }
            dot.color = c
            if (i == pts.lastIndex && (latestT == null || p.t >= latestT)) {
                dot.color = pal.bg; cv.drawCircle(px, py, r + 2.6f * d, dot)
                dot.color = c; cv.drawCircle(px, py, r + 1.4f * d, dot)
            } else cv.drawCircle(px, py, r, dot)
        }

        // The distribution: its outline erased from the lines, then its dots.
        val rows = if (sideW > 0) profileRows(GlucoseStats.profile(pts, from, end, b, tir), b, pal, left, sideW,
            top, plotBottom, yMin, yMax, d) else emptyList()
        if (rows.isNotEmpty()) {
            val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.DST_OUT }
            val h = PROFILE_R * d + PROFILE_CUT * d
            for (row in rows) cv.drawRoundRect(area.left, row.cy - h, row.endX + PROFILE_CUT * d, row.cy + h, h, h, cut)
            // the outline as erased and the dots it surrounds (CardLayoutTest checks the margin: layout/rules.json "distribution")
            rec.add("chart.distribution.cut", RectF(area.left, rows.minOf { it.cy } - h, rows.maxOf { it.endX } + PROFILE_CUT * d,
                rows.maxOf { it.cy } + h), exact = true)
            rec.add("chart.distribution.dots", RectF(left, rows.minOf { it.cy } - PROFILE_R * d, rows.maxOf { it.endX },
                rows.maxOf { it.cy } + PROFILE_R * d), exact = true)
        }
        cv.restoreToCount(layer)
        if (rows.isNotEmpty()) rec.add("chart.distribution", RectF(left, top, left + sideW, plotBottom), exact = true)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (row in rows) {
            p.color = row.color
            for (k in 0 until row.n) cv.drawCircle(left + PROFILE_PITCH * d * (k + 0.5f), row.cy, PROFILE_R * d, p)
        }

        // % per major band, centred on its band; labels too close for their bands are pushed apart (all three show).
        if (pctCol > 0) {
            val gap = pctBig.textSize * 1.05f
            val cIn = (y(b.high.toFloat()) + y(b.low.toFloat())) / 2
            val cHi = min((top + y(b.high.toFloat())) / 2, cIn - gap)
            val cLo = max((y(b.low.toFloat()) + plotBottom) / 2, cIn + gap)
            fun label(v: Int, cy: Float) {
                val s = "$v"
                val w = pctW(v)
                val x0 = left - SIDE_GAP_DP * d - w
                val base = cy + pctBig.textSize * 0.36f
                cv.drawText(s, x0, base, pctBig)
                cv.drawText("%", x0 + pctBig.measureText(s), base, pctSmall)
                // the drawn label's box (CardLayoutTest checks the column's gaps against it: layout/rules.json "gaps")
                rec.add("chart.pct.label", RectF(x0, base - pctBig.textSize * 0.72f, x0 + w, base), s, exact = true)
            }
            label(hi, cHi)
            label(inr, cIn)
            label(lo, cLo)
            rec.add("chart.pct", RectF(area.left, top, left, plotBottom), "$lo/$inr/$hi [w" + CardLayout.dp(pctCol, d) + "]", exact = true)
            // The crown over a perfect 100 (TirMark.column), where it clears the label above.
            if (st.crown && TirMark.column(tir)) {
                val numW = pctBig.measureText("100")
                val cw = numW * 0.62f
                val bottom = cIn + pctBig.textSize * (0.36f - 0.72f) - 1.5f * d
                if (bottom - cw * Crown.H >= cHi + pctBig.textSize * 0.36f + 2 * d) {
                    Crown.draw(cv, left - SIDE_GAP_DP * d - pctSmall.measureText("%") - numW / 2, bottom, cw, pal.tight)
                    rec.add("chart.pct.crown")
                } else rec.add("chart.pct.crown", info = "(no room)")
            }
        }

        if (marks) {
            rec.add("chart.markers", info = "${shown.count { it.k in setOf("rapid", "long", "carbs", "note") && show.has(it.k, MarkShow.POINT) }}", exact = true)
            // what each entry shows (layout/rules.json "marks": the same on every surface)
            rec.add("chart.marks", info = shown.sortedBy { it.id }.joinToString(" ") { tr ->
                tr.id + ":" + MarkShow.parts(tr.k).filter { p -> show.has(tr.k, p) && (p != MarkShow.LINE || muteUntil?.invoke(tr) != null) &&
                    (p != MarkShow.AMOUNT && p != MarkShow.TIME || TreatmentMarks.amount(tr) != null) }.joinToString("+")
            }, exact = true)
            layout?.labels?.forEach { l ->
                rec.add("chart.label", RectF(l.left, if (l.lane == 0) area.bottom - 14 * d - lane1 else area.bottom - 14 * d, l.left + l.w,
                    if (l.lane == 0) area.bottom - lane1 else area.bottom), "${l.text} lane${l.lane}", exact = true)
            }
        }
        if (marks) TreatmentMarks.draw(cv, shown, ::x, left, right, bottom, d, pal.dark, layout,
            base0 = area.bottom - 2 * d - lane1, base1 = area.bottom - 2 * d, sep = pal.inkSoft,
            nowS = nowS, muteUntil = muteUntil, show = show)
        // The "Now" pill of a scrolled chart (the action pill, StatusBadge): top right in the plot, fading in and out
        // with [ChartView.now]; the surface makes its box (chart.now) tappable.
        if (view != null && view.now > 0f) {
            val ph = NOW_PILL_DP * d
            val look = StatusBadge.backToNow()
            val pw = StatusBadge.width(look, ph, pal.dark)
            val px = right - pw - NOW_INSET_DP * d
            val py = top + NOW_INSET_DP * d
            val a = cv.saveLayerAlpha(px, py, px + pw, py + ph, (view.now.coerceIn(0f, 1f) * 255).roundToInt())
            StatusBadge.draw(cv, px, py, ph, pw, look, pal.dark)
            cv.restoreToCount(a)
            rec.add("chart.now", RectF(px, py, px + pw, py + ph), look.text, exact = true)
        }
        cv.restoreToCount(chartLayer)
    }

    /** The Now pill's height and its distance from the plot's top-right corner (dp; Mac `nowPill`, `nowInset`). */
    const val NOW_PILL_DP = 22f
    const val NOW_INSET_DP = 4f

    /**
     * The y range (mg/dL) of a window from [from] with the readings [pts] in it: it always reaches the very low / very
     * high limits, so their lines always show, and every reading with a margin. The user's own lines don't change it:
     * one outside the range isn't drawn. Mac `GlucoseGraph.yRange`.
     */
    fun yRange(pts: List<Reading>, from: Long, b: Bands): Pair<Float, Float> {
        var lo = Int.MAX_VALUE
        var hi = Int.MIN_VALUE
        for (p in pts) if (p.t >= from - 60) { if (p.sgv < lo) lo = p.sgv; if (p.sgv > hi) hi = p.sgv }
        if (lo == Int.MAX_VALUE) { lo = b.low; hi = b.high }
        return minOf(b.low - 22, b.veryLow - 6, lo - 6).toFloat().coerceAtLeast(GlucoseStats.MIN_MG.toFloat()) to
            maxOf(b.high + 40, b.veryHigh + 8, hi + 8).toFloat().coerceAtMost(GlucoseStats.MAX_MG.toFloat())
    }

    /**
     * The plot's left and right edge in an area [areaLeft]..[areaRight]: the % column on the left ([pct]: the time in
     * range is shown and has data; only from [PCT_MIN_WIDTH_DP]) and the values on the right ([labels]). Each column is
     * its widest label actually drawn ([pctW], [valW]: measured with the paints that draw them) plus [SIDE_GAP_DP] on
     * each side, whatever the surface, size or font (the user, 2026-10-21: "it should depend on the longest text
     * width"; the TextModel's worst-case widths had left up to ~10 dp of extra margin beside the labels).
     */
    fun plotX(areaLeft: Float, areaRight: Float, pct: Boolean, labels: Boolean, d: Float, pctW: Float, valW: Float): Pair<Float, Float> {
        val pctCol = if (pct && labels && areaRight - areaLeft >= PCT_MIN_WIDTH_DP * d) pctW + 2 * SIDE_GAP_DP * d else 0f
        return areaLeft + pctCol to areaRight - if (labels) valW + 2 * SIDE_GAP_DP * d else 0f
    }

    /** Height of the sensor badge in the hour-label row (dp). */
    const val SENSOR_DP = 10f

    /**
     * The top of the sensor badge (at the chart's left edge, [SENSOR_DP] tall) in a chart whose area ends at [bottom],
     * with [lane1] px of second label lane: centred on the hour labels' x-height (fixtures spikes/graph-fixtures/place;
     * Mac `sensorTop`).
     */
    fun sensorTop(bottom: Float, lane1: Float, d: Float): Float = bottom - 2 * d - lane1 - 3.6f * d - SENSOR_DP * d / 2

    /** The badge's width in the [TextModel] (the room kept free of treatment labels; the same on every platform). */
    fun sensorWidth(label: String, d: Float): Float = SENSOR_DP * d * 1.3f + TextModel.width(label, SENSOR_DP * d * 0.95f)

    /** The side columns' margin (dp) on each side of their widest label: one constant (layout/rules.json "gaps"; Mac `sideGap`). */
    const val SIDE_GAP_DP = 3f

    /** Treatment label lanes for a chart [h] px tall: a second one from [TreatmentMarks.TWO_LANES_DP]. */
    fun lanes(h: Float, d: Float) = if (h >= TreatmentMarks.TWO_LANES_DP * d) 2 else 1

    /**
     * The distribution's dots: rows [PROFILE_PITCH] apart, dots of radius [PROFILE_R]; the outline erased around them is
     * [PROFILE_CUT] wider (the user, 2026-10-21: "more outline so it masks out more of the content behind"; was 1.6).
     * The same numbers as layout/rules.json "distribution" (CardLayoutTest, Mac -graphselftest).
     */
    const val PROFILE_PITCH = 3.6f
    const val PROFILE_R = 1.35f
    const val PROFILE_CUT = 3f

    /** One row of the distribution: [n] dots centred on [cy], the last one ending at [endX]. */
    private class ProfileRow(val cy: Float, val n: Int, val color: Int, val endX: Float)

    private fun profileRows(
        prof: DoubleArray, b: Bands, pal: GraphPalette, left: Float, sideW: Float,
        top: Float, bottom: Float, yMin: Float, yMax: Float, d: Float,
    ): List<ProfileRow> {
        val pxPerMg = (bottom - top) / (yMax - yMin)
        fun massBetween(g0: Float, g1: Float): Double {   // profile mass in [g0, g1) mg/dL, fractional at the ends
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
        // Rows of dots the size of the line's, one row per pitch; dots per row ∝ the row's share of time.
        val pitch = PROFILE_PITCH * d
        val r = PROFILE_R * d
        val rows = ((bottom - top) / pitch).toInt()
        val mass = DoubleArray(rows) { i ->
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

    /**
     * Three shares as whole percentages that add up to 100 (largest remainder). The middle one (in range) is 100 only
     * when the others are nothing: that 100 wears the crown ([TirMark.column]).
     */
    fun pct3(a: Double, b: Double, c: Double): Triple<Int, Int, Int> {
        val raw = doubleArrayOf(a, b, c).map { it * 100 }
        val total = raw.sum()
        if (total <= 0) return Triple(0, 0, 0)
        val base = raw.map { floor(it).toInt() }.toIntArray()
        var left = total.roundToInt() - base.sum()
        raw.indices.sortedByDescending { raw[it] - base[it] }.forEach { if (left > 0) { base[it]++; left-- } }
        if (base[1] == 100 && (a > 0 || c > 0)) { base[1]--; base[if (a >= c) 0 else 2]++ }
        return Triple(base[0], base[1], base[2])
    }

}
