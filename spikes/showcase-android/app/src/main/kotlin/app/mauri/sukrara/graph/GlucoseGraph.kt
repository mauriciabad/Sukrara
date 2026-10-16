// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treatment
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
    val low: Int, val tight: Int, val target: Int, val high: Int,
) {
    fun of(b: Band) = when (b) { Band.LOW -> low; Band.TIGHT -> tight; Band.TARGET -> target; Band.HIGH -> high }

    companion object {
        val DARK = GraphPalette(true, bg = 0xFF1C1C1E.toInt(), ink = 0xFFF2F2F2.toInt(), inkSoft = 0xFFA3A3A8.toInt(),
            grid = 0xFF3A3A3E.toInt(), low = 0xFFF0524F.toInt(), tight = 0xFF3DD47A.toInt(), target = 0xFF8CC63F.toInt(),
            high = 0xFFF5A524.toInt())
        val LIGHT = GraphPalette(false, bg = 0xFFFFFFFF.toInt(), ink = 0xFF1C1C1E.toInt(), inkSoft = 0xFF6B6B70.toInt(),
            grid = 0xFFDADADF.toInt(), low = 0xFFD92D2A.toInt(), tight = 0xFF17A34A.toInt(), target = 0xFF6E9E1E.toInt(),
            high = 0xFFD97706.toInt())
        fun of(dark: Boolean) = if (dark) DARK else LIGHT
    }
}

/** What one placement of the graph shows. [windowS] = the time span (and the window of the side profile). */
data class GraphStyle(
    val windowS: Long = 12 * 3600L,
    val bands: Bands = Bands(),
    val profile: Profile = Profile.BAND_EXACT,
    /** Profile drawn as rows of dots (like the line) or as a filled area. */
    val profileDots: Boolean = true,
    /** The left overlay: time-in-range rectangles + profile + % labels. */
    val side: Boolean = true,
    val axisLabels: Boolean = true,
    val treatments: Boolean = true,
    val hour24: Boolean = true,
)

/**
 * The glucose chart of investigation 16, drawn on a plain [Canvas] so the app (Compose's native canvas), the widgets
 * and the notification (bitmaps) all show the same picture.
 *
 * Left to right: the % of time in each major band (labels), then, growing rightwards from the plot's left edge, one
 * soft rectangle per band whose width is its share of time (in range with the "very good" rectangle inside it) and the
 * distribution of glucose over the same window ([Profile]). The line underneath fades out towards that edge.
 */
object GlucoseGraph {
    fun draw(
        cv: Canvas, area: RectF, readings: List<Reading>, treatments: List<Treatment>, nowS: Long,
        st: GraphStyle, pal: GraphPalette, d: Float,
    ) {
        val from = nowS - st.windowS
        val b = st.bands.sane()
        val pts = GlucoseStats.window(readings, from - 60, nowS)
        val tir = GlucoseStats.timeInRange(readings, from, nowS, b)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 10 * d }
        val pctBig = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.ink; textSize = 13 * d; typeface = Typeface.create(Typeface.DEFAULT, 700, false)
        }
        val pctSmall = Paint(pctBig).apply { textSize = 8.5f * d; color = pal.inkSoft; typeface = Typeface.DEFAULT }

        val labels = st.axisLabels
        val pctCol = if (st.side && labels && !tir.empty) pctBig.measureText("100") + pctSmall.measureText("%") + 6 * d else 0f
        val left = area.left + pctCol
        val right = area.right - if (labels) text.measureText("180") + 5 * d else 0f
        val top = area.top + 3 * d
        val bottom = area.bottom - if (labels) text.textSize + 4 * d else 0f
        val marks = st.treatments && treatments.any { !it.del && it.t in from..nowS }
        val plotBottom = if (marks) bottom - 12 * d else bottom
        if (right - left < 20 * d || plotBottom - top < 20 * d) return

        val dMin = pts.minOfOrNull { it.sgv } ?: b.low
        val dMax = pts.maxOfOrNull { it.sgv } ?: b.high
        val yMin = min(b.low - 22, dMin - 6).toFloat().coerceAtLeast(GlucoseStats.MIN_MG.toFloat())
        val yMax = max(b.high + 40, dMax + 8).toFloat().coerceAtMost(GlucoseStats.MAX_MG.toFloat())
        fun x(t: Long) = left + (t - from).toFloat() / st.windowS * (right - left)
        fun y(v: Float) = plotBottom - (v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin) * (plotBottom - top)
        val sideW = if (st.side && !tir.empty) min((right - left) * 0.26f, 130 * d) else 0f

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        // Main in-range band across the whole plot.
        fill.color = ColorUtils.setAlphaComponent(pal.tight, if (pal.dark) 0x1E else 0x18)
        cv.drawRect(left, y(b.high.toFloat()), right, y(b.low.toFloat()), fill)

        // Hour grid + time labels: a step that keeps labels ≥ ~44 dp apart.
        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.grid; strokeWidth = 1 * d; pathEffect = DashPathEffect(floatArrayOf(2 * d, 3 * d), 0f)
        }
        val pxPerHour = (right - left) / (st.windowS / 3600f)
        val step = listOf(1, 2, 3, 4, 6, 12, 24).firstOrNull { it * pxPerHour >= 44 * d } ?: 24
        val cal = Calendar.getInstance().apply {
            timeInMillis = from * 1000; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.HOUR_OF_DAY, 1)
            while (get(Calendar.HOUR_OF_DAY) % step != 0) add(Calendar.HOUR_OF_DAY, 1)
        }
        var labelEnd = Float.NEGATIVE_INFINITY
        while (cal.timeInMillis / 1000 <= nowS) {
            val gx = x(cal.timeInMillis / 1000)
            if (gx > left + sideW * 0.6f) {
                cv.drawLine(gx, top, gx, plotBottom, grid)
                if (labels) {
                    val s = hourLabel(cal.get(Calendar.HOUR_OF_DAY), st.hour24)
                    val tw = text.measureText(s)
                    val lx = (gx - tw / 2).coerceIn(left, right - tw)
                    if (lx > labelEnd + 6 * d) {   // a label squeezed against the right edge would touch its neighbour
                        cv.drawText(s, lx, area.bottom - 2 * d, text)
                        labelEnd = lx + tw
                    }
                }
            }
            cal.add(Calendar.HOUR_OF_DAY, step)
        }
        if (labels) for (v in listOf(b.low, b.high)) {
            cv.drawText("$v", right + 4 * d, y(v.toFloat()) + text.textSize / 3, text)
        }

        // Time-in-range rectangles, growing from the left edge.
        if (sideW > 0) {
            val a = if (pal.dark) 0x38 else 0x2C
            fun rect(share: Double, c: Int, y0: Float, y1: Float, alpha: Int = a) {
                if (share <= 0) return
                fill.color = ColorUtils.setAlphaComponent(c, alpha)
                cv.drawRect(left, y0, left + sideW * share.toFloat(), y1, fill)
            }
            rect(tir.high, pal.high, top, y(b.high.toFloat()))
            rect(tir.inRange, pal.target, y(b.high.toFloat()), y(b.low.toFloat()))
            rect(tir.tight, pal.tight, y(b.tight.toFloat()), y(b.low.toFloat()), alpha = if (pal.dark) 0x40 else 0x34)
            rect(tir.low, pal.low, y(b.low.toFloat()), plotBottom)
        }

        // The line: one dot per reading, coloured by band, fading out towards the side overlay.
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val seg = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.6f * d; strokeCap = Paint.Cap.ROUND }
        val spacing = if (pts.size > 1) (right - left) / max(1f, pts.size.toFloat()) else 99f
        val r = (spacing * 0.55f).coerceIn(1.3f * d, 2.2f * d)
        val fadeEnd = left + sideW * 1.25f
        fun fade(px: Float): Int {
            if (sideW <= 0) return 255
            val u = ((px - left) / (fadeEnd - left)).coerceIn(0f, 1f)
            return (255 * (0.10f + 0.90f * u * u * (3 - 2 * u))).roundToInt()
        }
        for (i in pts.indices) {
            val p = pts[i]
            if (p.t < from) continue
            val px = x(p.t); val py = y(p.sgv.toFloat())
            val c = pal.of(b.of(p.sgv))
            if (spacing > 5 * d && i > 0 && p.t - pts[i - 1].t <= GlucoseStats.MAX_GAP_S) {
                seg.color = ColorUtils.setAlphaComponent(c, fade(px) * 3 / 4)
                cv.drawLine(x(pts[i - 1].t), y(pts[i - 1].sgv.toFloat()), px, py, seg)
            }
            dot.color = ColorUtils.setAlphaComponent(c, fade(px))
            if (i == pts.lastIndex) {
                dot.color = pal.bg; cv.drawCircle(px, py, r + 2.6f * d, dot)
                dot.color = c; cv.drawCircle(px, py, r + 1.4f * d, dot)
            } else cv.drawCircle(px, py, r, dot)
        }

        // Profile on top of the rectangles.
        if (sideW > 0 && st.profile != Profile.NONE) {
            val prof = GlucoseStats.profile(st.profile, readings, from, nowS, b, tir)
            drawProfile(cv, prof, b, pal, left, sideW, top, plotBottom, yMin, yMax, st.profileDots, d)
        }

        // % per major band, centred on its band.
        if (pctCol > 0) {
            val (lo, inr, hi) = pct3(tir.low, tir.inRange, tir.high)
            fun label(v: Int, y0: Float, y1: Float) {
                val cy = (y0 + y1) / 2 + pctBig.textSize * 0.36f
                if (y1 - y0 < pctBig.textSize * 0.8f) return
                val s = "$v"
                val w = pctBig.measureText(s) + pctSmall.measureText("%")
                val x0 = left - 4 * d - w
                cv.drawText(s, x0, cy, pctBig)
                cv.drawText("%", x0 + pctBig.measureText(s), cy, pctSmall)
            }
            label(hi, top, y(b.high.toFloat()))
            label(inr, y(b.high.toFloat()), y(b.low.toFloat()))
            label(lo, y(b.low.toFloat()), plotBottom)
        }

        if (marks) TreatmentMarks.draw(cv, treatments.filter { it.t in from..nowS }, ::x, left, right, bottom, d, pal.dark)
    }

    private fun drawProfile(
        cv: Canvas, prof: DoubleArray, b: Bands, pal: GraphPalette, left: Float, sideW: Float,
        top: Float, bottom: Float, yMin: Float, yMax: Float, dots: Boolean, d: Float,
    ) {
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
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (dots) {
            // Rows of dots the size of the line's, one row per pitch; dots per row ∝ the row's share of time.
            val pitch = 3.6f * d
            val r = 1.35f * d
            val rows = ((bottom - top) / pitch).toInt()
            val mass = DoubleArray(rows) { i ->
                val yb = bottom - i * pitch
                massBetween(yMin + (bottom - yb) / pxPerMg, yMin + (bottom - yb + pitch) / pxPerMg)
            }
            val peak = mass.maxOrNull()?.takeIf { it > 0 } ?: return
            val maxDots = floor(sideW * 0.9f / pitch).toInt()
            for (i in 0 until rows) {
                val n = (mass[i] / peak * maxDots).roundToInt().let { if (it == 0 && mass[i] > peak * 0.004) 1 else it }
                if (n == 0) continue
                val cy = bottom - i * pitch - pitch / 2
                val g = yMin + (bottom - cy) / pxPerMg
                p.color = pal.of(b.of(g.toDouble()))
                for (k in 0 until n) cv.drawCircle(left + pitch / 2 + k * pitch, cy, r, p)
            }
        } else {
            // Filled area, one horizontal slice per pixel row, coloured by band.
            val slices = ((bottom - top) / d).toInt()
            val mass = DoubleArray(slices) { i ->
                massBetween(yMin + i * d / pxPerMg, yMin + (i + 1) * d / pxPerMg)
            }
            val peak = mass.maxOrNull()?.takeIf { it > 0 } ?: return
            for (band in Band.entries) {
                val path = Path()
                var open = false
                for (i in 0 until slices) {
                    val g = yMin + (i + 0.5f) * d / pxPerMg
                    val yy = bottom - i * d
                    if (b.of(g.toDouble()) != band) { if (open) { path.lineTo(left, yy); open = false }; continue }
                    val w = (mass[i] / peak).toFloat() * sideW * 0.9f
                    if (!open) { path.moveTo(left, yy); open = true }
                    path.lineTo(left + w, yy)
                    path.lineTo(left + w, yy - d)
                }
                if (open) path.lineTo(left, bottom - slices * d)
                path.close()
                p.color = ColorUtils.setAlphaComponent(pal.of(band), 0xE0)
                cv.drawPath(path, p)
            }
        }
    }

    /** Three shares as whole percentages that add up to 100 (largest remainder). */
    fun pct3(a: Double, b: Double, c: Double): Triple<Int, Int, Int> {
        val raw = doubleArrayOf(a, b, c).map { it * 100 }
        val total = raw.sum()
        if (total <= 0) return Triple(0, 0, 0)
        val base = raw.map { floor(it).toInt() }.toIntArray()
        var left = total.roundToInt() - base.sum()
        raw.indices.sortedByDescending { raw[it] - base[it] }.forEach { if (left > 0) { base[it]++; left-- } }
        return Triple(base[0], base[1], base[2])
    }

    fun hourLabel(h: Int, hour24: Boolean): String =
        if (hour24) "%02d:00".format(Locale.ROOT, h)
        else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"
}
