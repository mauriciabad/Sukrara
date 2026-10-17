// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.treatments.Treatment
import kotlin.math.max
import kotlin.math.min

/** One time-in-range ring: its window and which range its centre number counts. */
data class RingSpec(val windowS: Long, val range: TirRange = TirRange.IN_RANGE) {
    val period: String get() = periodLabel(windowS)
}

fun periodLabel(s: Long): String = when {
    s % 86400 == 0L -> "${s / 86400}d"
    else -> "${s / 3600}h"
}

/**
 * The segmented time-in-range ring: the % of [RingSpec.range] in the middle, the window under it, and the perimeter
 * split by band, clockwise from 12 o'clock in glucose order (very low, low, very good, in range, high, very high). [emphasis] fades the
 * secondary rings (1 = main).
 */
object TirRing {
    fun draw(cv: Canvas, cx: Float, cy: Float, radius: Float, tir: TimeInRange, spec: RingSpec,
             pal: GraphPalette, emphasis: Float, d: Float) {
        val sw = radius * 0.2f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.BUTT }
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val alpha = (255 * emphasis).toInt()
        val shares = Band.entries.map { it to tir.share(it) }.filter { it.second >= 0.004 }
        if (shares.isEmpty()) {
            ring.color = ColorUtils.setAlphaComponent(pal.grid, alpha); cv.drawOval(oval, ring)
        } else {
            // Gaps only between the groups below / in / above range; bands of one group touch (very good is in range).
            val gap = if (shares.size > 1) 7f else 0f
            val gaps = shares.indices.count { i -> gapAfter(shares, i) } * gap
            var a = -90f
            for ((i, s) in shares.withIndex()) {
                val sweep = max(1.5f, (360f - gaps) * s.second.toFloat())
                ring.color = ColorUtils.setAlphaComponent(pal.of(s.first), alpha)
                cv.drawArc(oval, a, sweep, false, ring)
                a += sweep + if (gapAfter(shares, i)) gap else 0f
            }
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(pal.ink, alpha); textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, 800, false); textSize = radius * 0.78f
        }
        val sub = Paint(num).apply {
            textSize = radius * 0.34f; typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            color = ColorUtils.setAlphaComponent(if (spec.range == TirRange.TIGHT) pal.tight else pal.inkSoft, alpha)
        }
        val v = if (tir.empty) "–" else "${(tir.of(spec.range) * 100).let { Math.round(it) }}"
        cv.drawText(v, cx, cy + num.textSize * 0.28f, num)
        cv.drawText(spec.period, cx, cy + num.textSize * 0.28f + sub.textSize * 1.1f, sub)
    }

    private fun gapAfter(s: List<Pair<Band, Double>>, i: Int): Boolean =
        s.size > 1 && s[i].first.group != s[(i + 1) % s.size].first.group
}

/** What a whole glucose card shows: value header and/or chart, and up to three rings (main first). */
data class CardStyle(
    val graph: GraphStyle? = GraphStyle(),
    val rings: List<RingSpec> = listOf(RingSpec(86400), RingSpec(7 * 86400L)),
    val value: Boolean = true,
)

/**
 * Value + trend arrow + time-in-range rings + chart in one drawing (investigation 16's mock-up): the widgets and the
 * notification draw this into a bitmap; the gallery renders it for the report. The layout adapts to the size:
 * rings that don't fit are dropped (last first), short wide cards put the value left of the chart.
 */
object GlucoseCard {
    private val RING_EMPHASIS = floatArrayOf(1f, 0.55f, 0.35f)
    /** A ring is only drawn when its window has at least this much data. */
    const val MIN_COVERAGE = 0.25

    fun draw(
        cv: Canvas, w: Float, h: Float, readings: List<Reading>, treatments: List<Treatment>, nowS: Long,
        st: CardStyle, pal: GraphPalette, d: Float,
        tirOf: (RingSpec, Bands) -> TimeInRange = { r, b -> GlucoseStats.timeInRange(readings, nowS - r.windowS, nowS, b) },
    ) {
        val latest = readings.maxByOrNull { it.t }
        val fr = Freshness.of(latest?.let { nowS - it.t })
        val graph = st.graph?.takeIf { fr.showGraph }
        val bands = (st.graph?.bands ?: Bands()).sane()
        val sideBySide = graph != null && st.value && h < 100 * d && w > 2.6f * h
        val headerH = when {
            !st.value -> 0f
            graph == null -> h
            sideBySide -> h
            else -> (h * 0.34f).coerceIn(30 * d, 80 * d)
        }
        val valueRight = if (sideBySide) min(w * 0.32f, 120 * d) else w

        if (st.value) {
            val box = RectF(0f, 0f, valueRight, if (sideBySide) h else headerH)
            val ringsW = if (sideBySide) 0f else drawRings(cv, box, st.rings, bands, pal, d, tirOf)
            drawValue(cv, RectF(box.left, box.top, box.right - ringsW, box.bottom), latest, fr, nowS, bands, pal, d)
        }
        val chart = if (sideBySide) RectF(valueRight + 6 * d, 0f, w, h) else RectF(0f, headerH + if (st.value) 6 * d else 0f, w, h)
        when {
            graph != null -> GlucoseGraph.draw(cv, chart, readings, treatments, nowS, graph, pal, d)
            st.graph != null && chart.height() > 24 * d -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 12 * d; textAlign = Paint.Align.CENTER }
                val msg = latest?.let { "No readings since ${Freshness.timeText(it.t, nowS)}" } ?: "No readings yet"
                cv.drawText(msg, chart.centerX(), chart.centerY(), p)
            }
        }
    }

    /** Rings right-aligned in [box] (main rightmost); returns the width used. */
    private fun drawRings(cv: Canvas, box: RectF, rings: List<RingSpec>, bands: Bands, pal: GraphPalette, d: Float,
                          tirOf: (RingSpec, Bands) -> TimeInRange): Float {
        if (rings.isEmpty()) return 0f
        val size = min(box.height() * 0.96f, 84 * d)
        if (size < 30 * d) return 0f
        val gap = size * 0.14f
        val fit = ((box.width() * 0.55f + gap) / (size + gap)).toInt().coerceAtMost(min(3, rings.size))
        var x = box.right
        var used = 0f
        for ((i, spec) in rings.take(fit).withIndex()) {
            val tir = tirOf(spec, bands)
            if (tir.coverage < MIN_COVERAGE) continue
            TirRing.draw(cv, x - size / 2, box.centerY(), size / 2, tir, spec, pal, RING_EMPHASIS[i], d)
            x -= size + gap
            used += size + gap
        }
        return used
    }

    private fun drawValue(cv: Canvas, box: RectF, r: Reading?, fr: Freshness, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float) {
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = max(11 * d, box.height() * 0.17f) }
        if (r == null || !fr.showValue) {
            val big = Paint(small).apply { textSize = min(box.height() * 0.26f, 20 * d); typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
            val line1 = if (r == null) "No readings yet" else "No reading since"
            val y = box.centerY() - if (r == null) -big.textSize / 3 else big.textSize * 0.15f
            cv.drawText(line1, box.left, y, big)
            if (r != null) cv.drawText(Freshness.timeText(r.t, nowS), box.left, y + big.textSize * 1.25f, big)
            return
        }
        val color = if (fr.strike) pal.inkSoft else pal.of(bands.of(r.sgv))
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            textSize = box.height() * 1.18f
            isStrikeThruText = fr.strike
        }
        val s = "${r.sgv}"
        // Fit the width as well: number + arrow + (time) must stay left of the rings.
        val arrowFactor = 0.62f
        val timeW = if (fr.showTime) small.measureText(Freshness.timeText(r.t, nowS)) + 8 * d else 0f
        val need = num.measureText(s) + num.textSize * arrowFactor * 1.1f + timeW
        if (need > box.width()) num.textSize *= box.width() / need
        val capH = num.textSize * 0.72f
        val baseY = box.centerY() + capH / 2
        cv.drawText(s, box.left - num.textSize * 0.04f, baseY, num)
        var x = box.left + num.measureText(s) + num.textSize * 0.08f
        val arrowSize = num.textSize * arrowFactor
        if (!fr.strike) r.rate?.let { rate ->
            cv.save()
            cv.translate(x + arrowSize / 2, box.centerY())
            cv.rotate(Trend.angleDeg(rate))
            GlucoseNotification.drawArrow(cv, arrowSize * 1.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
                double = Trend.isDouble(rate))
            cv.restore()
        }
        x += arrowSize + 6 * d
        if (fr.showTime) cv.drawText(Freshness.timeText(r.t, nowS), x, box.centerY() + small.textSize / 3, small)
    }
}
