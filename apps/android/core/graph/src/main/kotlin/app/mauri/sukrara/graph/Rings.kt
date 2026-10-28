// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.GlucoseMetrics
import app.mauri.sukrara.graph.stats.Goal
import app.mauri.sukrara.graph.stats.ShareText
import app.mauri.sukrara.graph.stats.TimeInRange
import app.mauri.sukrara.graph.stats.TirRange
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The mark a time-in-range ring wears, by the share it shows (with the report's 70 % of data): a laurel from 90 %,
 * flowers from 95 %, the crown at 100 % (nothing outside the range). contracts/graph/mark.
 */
enum class TirMark {
    NONE,
    LAUREL,
    FLOWERS,
    CROWN,
    ;

    companion object {
        fun of(
            tir: TimeInRange,
            range: TirRange,
        ): TirMark {
            if (tir.coverage < Goal.MIN_COVERAGE) return NONE
            val x = tir.of(range)
            if (x >= 1.0) return CROWN
            val shown = Math.round(x * 100)
            return when {
                shown >= 95 -> FLOWERS
                shown >= 90 -> LAUREL
                else -> NONE
            }
        }

        /** The % column's 100 wears the crown on the same rule. */
        fun column(tir: TimeInRange) = of(tir, TirRange.IN_RANGE) == CROWN
    }
}

/** The crown (a 12 × 7.4 outline with rounded joins), [w] wide with its bottom centre at ([x], [bottom]). */
internal object Crown {
    private val PTS = floatArrayOf(0.7f, 7.4f, 0f, 1.7f, 3.3f, 4.3f, 6f, 0f, 8.7f, 4.3f, 12f, 1.7f, 11.3f, 7.4f)
    const val H = 7.4f / 12f

    fun path(
        x: Float,
        bottom: Float,
        w: Float,
    ): Path {
        val k = w / 12f
        return Path().apply {
            for (i in PTS.indices step 2) {
                val px = x + (PTS[i] - 6f) * k
                val py = bottom + (PTS[i + 1] - 7.4f) * k
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    fun paint(
        color: Int,
        w: Float,
    ) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE
        strokeJoin = Paint.Join.ROUND
        this.color = color
        strokeWidth = w * 0.8f / 12f
    }

    fun draw(
        cv: Canvas,
        x: Float,
        bottom: Float,
        w: Float,
        color: Int,
    ) = cv.drawPath(path(x, bottom, w), paint(color, w))
}

/**
 * The laurel: two branches of leaves up the sides of a ring of radius r, stems on a circle of 1.25 r. Between two
 * laurelled rings the facing sides ([Side.MERGED]) keep their stem and inner leaves only, so the wreaths meet. A faded
 * colour fades the whole laurel as one layer.
 */
internal object Laurel {
    enum class Side { FULL, MERGED }

    fun draw(
        cv: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        color: Int,
        left: Side,
        right: Side,
    ) {
        val a = color ushr 24
        val layer = if (a < 255) cv.saveLayerAlpha(null, a) else -1
        val c = color or (0xFF shl 24)
        branch(cv, cx, cy, r, c, true, left)
        branch(cv, cx, cy, r, c, false, right)
        if (layer >= 0) cv.restoreToCount(layer)
    }

    private fun branch(
        cv: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        color: Int,
        left: Boolean,
        side: Side,
    ) {
        val sr = r * 1.25f
        cv.drawArc(
            RectF(cx - sr, cy - sr, cx + sr, cy + sr),
            if (left) 131f else -34f,
            83f,
            false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = r * 0.032f
                strokeCap = Paint.Cap.ROUND
                this.color = color
            },
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val shape = RectF(-r * 0.135f, -r * 0.056f, r * 0.135f, r * 0.056f)

        fun leaf(
            rad: Float,
            deg: Float,
            rot: Float,
        ) {
            val a = Math.toRadians(deg.toDouble())
            cv.save()
            cv.translate(cx + rad * Math.cos(a).toFloat(), cy + rad * Math.sin(a).toFloat())
            cv.rotate(rot)
            cv.drawOval(shape, p)
            cv.restore()
        }
        val s = if (left) 1 else -1
        for (k in 0 until 6) {
            val a = if (left) 135f + 15f * k else 45f - 15f * k
            if (side == Side.FULL || k <= 1 || k == 5) leaf(r * 1.35f, a, a + s * 90 - s * 32)
            leaf(r * 1.17f, a + s * 5, a + s * 90 + s * 32)
        }
    }
}

/**
 * The segmented time-in-range ring: the % of [RingSpec.range] in the middle, the window under it, the perimeter split
 * by band clockwise from 12 o'clock in [RingSpec.order]. [emphasis] fades secondary rings; [countdown] = the sensor
 * ring over it. At 100 % the crown in the text colour is set into 12 o'clock, cut out of the ring.
 */
object TirRing {
    internal fun draw(
        cv: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        tir: TimeInRange,
        spec: RingSpec,
        pal: GraphPalette,
        emphasis: Float,
        w: CardWords,
        countdown: Double?,
    ) {
        val mark = mark(tir, spec)
        val sw = r * 0.2f
        val ring =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = sw
                strokeCap = Paint.Cap.BUTT
            }
        val oval = RectF(cx - r + sw / 2, cy - r + sw / 2, cx + r - sw / 2, cy + r - sw / 2)
        val alpha = (255 * emphasis).toInt()
        val crownW = r * 0.74f
        val crownBottom = cy - r * 0.72f
        val layer = if (mark == TirMark.CROWN) cv.saveLayer(null, null) else -1
        val shares = spec.order.map { it to tir.share(it) }.filter { it.second >= 0.004 }
        if (shares.isEmpty()) {
            ring.color = ColorUtils.setAlphaComponent(pal.grid, alpha)
            cv.drawOval(oval, ring)
        } else {
            // gaps between below / in / above range and between very good and in range; the two lows and highs touch
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
        SensorBadge.drawCountdown(cv, cx, cy, r, countdown, pal)
        if (layer >= 0) {
            cv.drawPath(
                Crown.path(cx, crownBottom, crownW),
                Crown.paint(0xFF000000.toInt(), crownW).apply {
                    strokeWidth += 2 * r * 0.06f
                    blendMode = BlendMode.DST_OUT
                },
            )
            cv.restoreToCount(layer)
            // faded as one layer: fill and outline overlapping at partial alpha would show a darker rim
            val fade = if (alpha < 255) cv.saveLayerAlpha(null, alpha) else -1
            Crown.draw(cv, cx, crownBottom, crownW, pal.ink or (0xFF shl 24))
            if (fade >= 0) cv.restoreToCount(fade)
        }
        val num =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(pal.ink, alpha)
                textAlign = Paint.Align.CENTER
                typeface = RoundedFont.typeface(800)
                textSize = r * 0.78f
            }
        val sub =
            Paint(num).apply {
                textSize = r * 0.34f
                typeface = RoundedFont.typeface(600)
                color = ColorUtils.setAlphaComponent(if (spec.range == TirRange.TIGHT) pal.tight else pal.inkSoft, alpha)
            }
        val v = if (tir.empty) "–" else text(tir, spec)
        val base = cy + num.textSize * 0.28f
        if (v.length > 3) num.textSize *= 0.7f // "69.7", "<0.1": the decimals fit inside the ring
        num.textSize = min(num.textSize, num.textSize * (r - sw) * 1.5f / num.measureText(v).coerceAtLeast(1f))
        cv.drawText(v, cx, base, num)
        cv.drawText(w.period(spec.windowS), cx, base + sub.textSize * 1.1f, sub)
    }

    /** The ring's [TirMark] (none without data or with the rewards turned off). */
    fun mark(
        tir: TimeInRange,
        spec: RingSpec,
    ) = if (tir.empty || !spec.marks) TirMark.NONE else TirMark.of(tir, spec.range)

    /** The number in the middle: in range against its > 70 % goal, very good on its own. */
    fun text(
        tir: TimeInRange,
        spec: RingSpec,
        locale: Locale = Locale.getDefault(),
    ): String = ShareText.of(tir.of(spec.range), if (spec.range == TirRange.IN_RANGE) Goal.IN_RANGE else null, locale)

    private fun gapAfter(
        s: List<Pair<Band, Double>>,
        i: Int,
    ): Boolean {
        if (s.size < 2) return false
        val a = s[i].first
        val b = s[(i + 1) % s.size].first
        return a.group != b.group || setOf(a, b) == setOf(Band.TIGHT, Band.TARGET)
    }
}

/**
 * A report number in a slot: the value in the middle (GMI 6.7 %, average 141, CV 37 %), the kind and window under it,
 * an arc on the number's scale (green when the consensus goal is met, orange when not) with a tick at the goal.
 */
internal object MetricGauge {
    /** Scale (min, max) and goal of each kind, in its own unit. */
    private fun scale(k: SlotKind) =
        when (k) {
            SlotKind.GMI -> Triple(5.0, 10.0, 7.0)
            SlotKind.AVERAGE -> Triple(70.0, 250.0, 154.0)
            else -> Triple(0.0, 60.0, 36.0)
        }

    private fun value(
        k: SlotKind,
        m: GlucoseMetrics,
    ) = when (k) {
        SlotKind.GMI -> m.gmi
        SlotKind.AVERAGE -> m.mean
        else -> m.cv
    }

    private fun met(
        k: SlotKind,
        m: GlucoseMetrics,
    ) = when (k) {
        SlotKind.GMI -> Goal.GMI.met(m)
        SlotKind.AVERAGE -> Goal.AVERAGE.met(m)
        else -> Goal.CV.met(m)
    }

    fun text(
        k: SlotKind,
        m: GlucoseMetrics,
    ): Pair<String, String> =
        when (k) {
            SlotKind.GMI -> "%.1f".format(m.gmi) to "%"
            SlotKind.AVERAGE -> "${Math.round(m.mean)}" to ""
            else -> "${Math.round(m.cv)}" to "%"
        }

    fun short(
        k: SlotKind,
        w: CardWords,
    ) = when (k) {
        SlotKind.TIR -> ""
        SlotKind.GMI -> w.slotGmi
        SlotKind.AVERAGE -> w.slotAverage
        SlotKind.CV -> w.slotCv
    }

    fun draw(
        cv: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        m: GlucoseMetrics,
        spec: RingSpec,
        pal: GraphPalette,
        emphasis: Float,
        d: Float,
        w: CardWords,
    ) {
        val alpha = (255 * emphasis).toInt()
        val sw = radius * 0.2f
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val ring =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = sw
                strokeCap = Paint.Cap.ROUND
                color = ColorUtils.setAlphaComponent(pal.grid, alpha)
            }
        cv.drawOval(oval, ring)
        val (lo, hi, goal) = scale(spec.kind)
        if (!m.empty) {
            val f = ((value(spec.kind, m) - lo) / (hi - lo)).coerceIn(0.02, 1.0).toFloat()
            ring.color = ColorUtils.setAlphaComponent(if (met(spec.kind, m)) pal.tight else pal.high, alpha)
            cv.drawArc(oval, -90f, 360f * f, false, ring)
        }
        val ga = Math.toRadians(-90.0 + 360.0 * (goal - lo) / (hi - lo))
        val tick =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(pal.ink, alpha)
                strokeWidth = 1.6f * d
            }
        val r0 = radius - sw * 1.15f
        val r1 = radius + sw * 0.15f
        cv.drawLine(
            cx + (r0 * Math.cos(ga)).toFloat(),
            cy + (r0 * Math.sin(ga)).toFloat(),
            cx + (r1 * Math.cos(ga)).toFloat(),
            cy + (r1 * Math.sin(ga)).toFloat(),
            tick,
        )
        val num =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = ColorUtils.setAlphaComponent(pal.ink, alpha)
                typeface = RoundedFont.typeface(800)
                textSize = radius * 0.62f
            }
        val unit =
            Paint(num).apply {
                textSize = radius * 0.3f
                typeface = RoundedFont.typeface(600)
            }
        val sub =
            Paint(num).apply {
                textAlign = Paint.Align.CENTER
                textSize = radius * 0.3f
                typeface = RoundedFont.typeface(600)
                color = ColorUtils.setAlphaComponent(pal.inkSoft, alpha)
            }
        val (v, u) = if (m.empty) "–" to "" else text(spec.kind, m)
        val tw = num.measureText(v) + unit.measureText(u)
        val base = cy + num.textSize * 0.3f
        cv.drawText(v, cx - tw / 2, base, num)
        cv.drawText(u, cx - tw / 2 + num.measureText(v), base, unit)
        cv.drawText("${short(spec.kind, w)} ${w.period(spec.windowS)}", cx, base + sub.textSize * 1.25f, sub)
    }
}
