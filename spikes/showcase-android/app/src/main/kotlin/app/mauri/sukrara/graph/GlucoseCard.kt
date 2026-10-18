// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.Trend
import app.mauri.sukrara.status.GlucoseNotification
import app.mauri.sukrara.treatments.LastTreatment
import app.mauri.sukrara.treatments.Treatment
import kotlin.math.max
import kotlin.math.min

/**
 * The ring's default segment order clockwise from 12 o'clock (the user, 0.11.9): in range starts at the top (very good
 * first), the lows follow, then the highs, very high last (just before 12).
 */
val DEFAULT_RING_ORDER = listOf(Band.TIGHT, Band.TARGET, Band.LOW, Band.VERY_LOW, Band.HIGH, Band.VERY_HIGH)

/** What a slot beside the value shows (investigation 18): a time-in-range ring or one of the report's numbers. */
enum class SlotKind(val label: String, val short: String) {
    TIR("Time in range", ""), GMI("GMI", "GMI"), AVERAGE("Average glucose", "avg"), CV("Variability (CV)", "CV")
}

/**
 * One slot beside the value: its [kind], window and, for a ring, which range its centre number counts. [faded] =
 * the second and third slots are drawn fainter than the main one (off = full strength wherever it sits).
 */
data class RingSpec(
    val windowS: Long, val range: TirRange = TirRange.IN_RANGE, val kind: SlotKind = SlotKind.TIR,
    val faded: Boolean = true,
    /** A ring's segments clockwise from 12 o'clock (the user's setting). */
    val order: List<Band> = DEFAULT_RING_ORDER,
) {
    val period: String get() = periodLabel(windowS)
}

fun periodLabel(s: Long): String = when {
    s % 86400 == 0L -> "${s / 86400}d"
    else -> "${s / 3600}h"
}

/**
 * The segmented time-in-range ring: the % of [RingSpec.range] in the middle, the window under it, and the perimeter
 * split by band, clockwise from 12 o'clock in [RingSpec.order] (default: very good, in range, low, very low, high,
 * very high). [emphasis] fades the
 * secondary rings (1 = main).
 */
object TirRing {
    fun draw(cv: Canvas, cx: Float, cy: Float, radius: Float, tir: TimeInRange, spec: RingSpec,
             pal: GraphPalette, emphasis: Float, d: Float) {
        val sw = radius * 0.2f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.BUTT }
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val alpha = (255 * emphasis).toInt()
        val shares = spec.order.map { it to tir.share(it) }.filter { it.second >= 0.004 }
        if (shares.isEmpty()) {
            ring.color = ColorUtils.setAlphaComponent(pal.grid, alpha); cv.drawOval(oval, ring)
        } else {
            // Gaps between below / in / above range and between very good and in range; the two lows and two highs touch.
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
        val v = if (tir.empty) "–" else "${Math.round(tir.of(spec.range) * 100)}"
        cv.drawText(v, cx, cy + num.textSize * 0.28f, num)
        cv.drawText(spec.period, cx, cy + num.textSize * 0.28f + sub.textSize * 1.1f, sub)
    }

    /** A gap between groups and between very good and the rest of in range; very low/low and high/very high touch. */
    private fun gapAfter(s: List<Pair<Band, Double>>, i: Int): Boolean {
        if (s.size < 2) return false
        val a = s[i].first; val b = s[(i + 1) % s.size].first
        return a.group != b.group || setOf(a, b) == setOf(Band.TIGHT, Band.TARGET)
    }
}

/**
 * A report number in a slot (investigation 18): the value in the middle (GMI 6.7 %, average 141, CV 37 %), the kind
 * and window under it, and around it an arc on the number's scale, green when the consensus goal is met and orange
 * when not, with a tick at the goal.
 */
object MetricGauge {
    /** Scale (min, max) and goal of each kind, in its own unit. */
    private fun scale(k: SlotKind) = when (k) {
        SlotKind.GMI -> Triple(5.0, 10.0, 7.0)
        SlotKind.AVERAGE -> Triple(70.0, 250.0, 154.0)
        else -> Triple(0.0, 60.0, 36.0)
    }

    fun value(k: SlotKind, m: GlucoseMetrics) = when (k) { SlotKind.GMI -> m.gmi; SlotKind.AVERAGE -> m.mean; else -> m.cv }

    fun met(k: SlotKind, m: GlucoseMetrics) = when (k) {
        SlotKind.GMI -> Goal.GMI.met(m); SlotKind.AVERAGE -> Goal.AVERAGE.met(m); else -> Goal.CV.met(m)
    }

    fun text(k: SlotKind, m: GlucoseMetrics): Pair<String, String> = when (k) {
        SlotKind.GMI -> "%.1f".format(m.gmi) to "%"
        SlotKind.AVERAGE -> "${Math.round(m.mean)}" to ""
        else -> "${Math.round(m.cv)}" to "%"
    }

    fun draw(cv: Canvas, cx: Float, cy: Float, radius: Float, m: GlucoseMetrics, spec: RingSpec,
             pal: GraphPalette, emphasis: Float, d: Float) {
        val alpha = (255 * emphasis).toInt()
        val sw = radius * 0.2f
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        ring.color = ColorUtils.setAlphaComponent(pal.grid, alpha)
        cv.drawOval(oval, ring)
        val (lo, hi, goal) = scale(spec.kind)
        if (!m.empty) {
            val f = ((value(spec.kind, m) - lo) / (hi - lo)).coerceIn(0.02, 1.0).toFloat()
            ring.color = ColorUtils.setAlphaComponent(if (met(spec.kind, m)) pal.tight else pal.high, alpha)
            cv.drawArc(oval, -90f, 360f * f, false, ring)
        }
        // goal tick across the track
        val ga = Math.toRadians(-90.0 + 360.0 * (goal - lo) / (hi - lo))
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(pal.ink, alpha); strokeWidth = 1.6f * d }
        val r0 = radius - sw * 1.15f; val r1 = radius + sw * 0.15f
        cv.drawLine(cx + (r0 * Math.cos(ga)).toFloat(), cy + (r0 * Math.sin(ga)).toFloat(),
            cx + (r1 * Math.cos(ga)).toFloat(), cy + (r1 * Math.sin(ga)).toFloat(), tick)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(pal.ink, alpha); textAlign = Paint.Align.LEFT
            typeface = Typeface.create(Typeface.DEFAULT, 800, false); textSize = radius * 0.62f
        }
        val unit = Paint(num).apply { textSize = radius * 0.3f; typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
        val sub = Paint(num).apply {
            textAlign = Paint.Align.CENTER; textSize = radius * 0.3f; typeface = Typeface.create(Typeface.DEFAULT, 600, false)
            color = ColorUtils.setAlphaComponent(pal.inkSoft, alpha)
        }
        val (v, u) = if (m.empty) "–" to "" else text(spec.kind, m)
        val w = num.measureText(v) + unit.measureText(u)
        val base = cy + num.textSize * 0.3f
        cv.drawText(v, cx - w / 2, base, num)
        cv.drawText(u, cx - w / 2 + num.measureText(v), base, unit)
        cv.drawText("${spec.kind.short} ${spec.period}", cx, base + sub.textSize * 1.25f, sub)
    }
}

/**
 * What a whole glucose card shows: value header and/or chart, up to three rings (main first), the last-treatment slot
 * ([last], with [lastElse] while there's none) and the status badge under the value ([badge]).
 */
data class CardStyle(
    val graph: GraphStyle? = GraphStyle(),
    val rings: List<RingSpec> = listOf(RingSpec(86400), RingSpec(7 * 86400L)),
    val value: Boolean = true,
    val last: LastPlace = LastPlace.OFF,
    val lastElse: RingSpec? = null,
    val badge: Boolean = true,
)

/**
 * Value + trend arrow + time-in-range rings + chart in one drawing (investigation 16's mock-up): the widgets and the
 * notification draw this into a bitmap; the gallery renders it for the report. The layout adapts to the size:
 * rings that don't fit are dropped (last first), short wide cards put the value left of the chart.
 */
object GlucoseCard {
    /** A ring is only drawn when its window has at least this much data. */
    const val MIN_COVERAGE = 0.25

    /**
     * [alerts] = the alert rules behind the badge, the mute lines on the chart and the last-treatment ring (null = none
     * of them); every caller gets the app's rules unless it passes its own.
     */
    fun draw(
        cv: Canvas, w: Float, h: Float, readings: List<Reading>, treatments: List<Treatment>, nowS: Long,
        st: CardStyle, pal: GraphPalette, d: Float,
        metricsOf: (RingSpec, Bands) -> GlucoseMetrics = { r, b -> GlucoseMetrics.of(readings, nowS - r.windowS, nowS, b) },
        alerts: AlertSettings? = Alerts.settings.value,
    ) {
        val latest = readings.maxByOrNull { it.t }
        val status = alerts?.let { AlertStatus.of(latest, it, treatments, nowS) } ?: AlertStatus.None
        val last = if (st.last != LastPlace.OFF && alerts != null) LastTreatment.of(treatments, alerts, status, nowS) else null
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
            val ringsW = if (sideBySide) 0f else drawRings(cv, box, st, last, nowS, bands, pal, d, metricsOf)
            val look = if (st.badge) StatusBadge.look(status, nowS) else null
            drawValue(cv, RectF(box.left, box.top, box.right - ringsW, box.bottom), latest, fr, nowS, bands, pal, d, look)
        }
        val chart = if (sideBySide) RectF(valueRight + 6 * d, 0f, w, h) else RectF(0f, headerH + if (st.value) 6 * d else 0f, w, h)
        when {
            graph != null -> GlucoseGraph.draw(cv, chart, readings, treatments, nowS, graph, pal, d, alerts)
            st.graph != null && chart.height() > 24 * d -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 12 * d; textAlign = Paint.Align.CENTER }
                val msg = latest?.let { "No readings since ${Freshness.timeText(it.t, nowS)}" } ?: "No readings yet"
                cv.drawText(msg, chart.centerX(), chart.centerY(), p)
            }
        }
    }

    /** Slots ([Slots.arrange]) right-aligned in [box] (main rightmost); returns the width used. */
    private fun drawRings(cv: Canvas, box: RectF, st: CardStyle, last: LastTreatment.Info?, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float, metricsOf: (RingSpec, Bands) -> GlucoseMetrics): Float {
        val extra = st.last != LastPlace.OFF && (last != null || st.lastElse != null)
        if (st.rings.isEmpty() && !extra) return 0f
        val size = min(box.height() * 0.96f, 84 * d)
        if (size < 30 * d) return 0f
        val gap = size * 0.14f
        val fit = ((box.width() * 0.55f + gap) / (size + gap)).toInt().coerceAtMost(4)
        if (fit < 1) return 0f
        val rings = st.rings.take(3).mapIndexedNotNull { i, spec ->
            metricsOf(spec, bands).takeIf { it.tir.coverage >= MIN_COVERAGE }?.let { Triple(i, spec, it) }
        }
        val fallback = if (st.last != LastPlace.OFF && last == null) st.lastElse?.let { s ->
            metricsOf(s, bands).takeIf { it.tir.coverage >= MIN_COVERAGE }?.let { s to it }
        } else null
        var x = box.right
        var used = 0f
        for (item in Slots.arrange(rings, st.last, last, fallback, fit)) {
            Slots.draw(cv, item, x - size / 2, box.centerY(), size / 2, nowS, pal, d)
            x -= size + gap
            used += size + gap
        }
        return used
    }

    /**
     * The number + arrow, and under it the late reading's time and/or the status badge ([look]). The badge takes a row
     * under the number when the box is tall enough to keep the number at least 26 dp; otherwise it shrinks to its icon
     * beside the arrow, or is left out when there's no room there either.
     */
    private fun drawValue(cv: Canvas, box: RectF, r: Reading?, fr: Freshness, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float, look: StatusBadge.Look? = null) {
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = max(11 * d, box.height() * 0.17f) }
        if (r == null || !fr.showValue) {
            val big = Paint(small).apply { textSize = min(box.height() * 0.26f, 20 * d); typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
            val line1 = if (r == null) "No readings yet" else "No reading since"
            val y = box.centerY() - if (r == null) -big.textSize / 3 else big.textSize * 0.15f
            cv.drawText(line1, box.left, y, big)
            if (r != null) cv.drawText(Freshness.timeText(r.t, nowS), box.left, y + big.textSize * 1.25f, big)
            return
        }
        val color = if (fr.strike) pal.inkSoft else pal.at(bands, r.sgv)
        // A late reading's time goes under the number (the user: beside it, it took the rings' room).
        val badgeH = (box.height() * 0.24f).coerceIn(14 * d, 24 * d)
        val badgeRow = look != null && box.height() - badgeH * 1.25f - (if (fr.showTime) small.textSize * 1.25f else 0f) >= 26 * d
        // the late time shares the badge's row (after the badge) when there is one
        val timeH = if (badgeRow) badgeH * 1.25f else if (fr.showTime) small.textSize * 1.25f else 0f
        val numBox = RectF(box.left, box.top, box.right, box.bottom - timeH)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = Typeface.create(Typeface.DEFAULT, 700, false)
            textSize = numBox.height() * 1.18f
            isStrikeThruText = fr.strike
        }
        val s = "${r.sgv}"
        // Fit the width as well: number + arrow must stay left of the rings.
        val arrowFactor = 0.62f
        val need = num.measureText(s) + num.textSize * arrowFactor * 1.1f
        if (need > box.width()) num.textSize *= box.width() / need
        // no row for the badge: its icon goes beside the arrow if there's room
        val iconBadge = look != null && !badgeRow &&
            box.width() - (num.measureText(s) + num.textSize * arrowFactor * 1.2f) >= min(numBox.height() * 0.5f, 24 * d)
        val capH = num.textSize * 0.72f
        val cy = numBox.centerY()
        val baseY = cy + capH / 2
        cv.drawText(s, box.left - num.textSize * 0.04f, baseY, num)
        var x = box.left + num.measureText(s) + num.textSize * 0.08f
        val arrowSize = num.textSize * arrowFactor
        if (!fr.strike) r.rate?.let { rate ->
            cv.save()
            cv.translate(x + arrowSize / 2, cy)
            cv.rotate(Trend.angleDeg(rate))
            GlucoseNotification.drawArrow(cv, arrowSize * 1.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
                double = Trend.isDouble(rate))
            cv.restore()
        }
        if (badgeRow && look != null) {
            val top = numBox.bottom + badgeH * 0.25f
            val bw = StatusBadge.draw(cv, box.left, top, badgeH, box.width(), look, pal.dark)
            if (fr.showTime) {
                val t = Paint(small).apply { textSize = badgeH * 0.56f }
                val tx = box.left + bw + badgeH * 0.35f
                val text = Freshness.timeText(r.t, nowS)
                if (tx + t.measureText(text) <= box.right) cv.drawText(text, tx, top + badgeH / 2 + t.textSize * 0.36f, t)
            }
        } else {
            if (iconBadge && look != null) {
                val ih = min(numBox.height() * 0.5f, 24 * d)
                StatusBadge.draw(cv, x + arrowSize * 1.15f, cy - ih / 2, ih, ih, look, pal.dark)
            }
            if (fr.showTime) cv.drawText(Freshness.timeText(r.t, nowS), box.left, baseY + small.textSize * 1.25f, small)
        }
    }
}
