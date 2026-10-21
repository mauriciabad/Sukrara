// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Words
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Alerts
import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.SensorLife
import app.mauri.sukrara.core.Trend
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
enum class SlotKind {
    TIR, GMI, AVERAGE, CV;

    /** The word under a number slot (none under a ring). */
    val short: String get() = when (this) {
        TIR -> ""; GMI -> Words.current.slotGmi; AVERAGE -> Words.current.slotAverage; CV -> Words.current.slotCv
    }
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
    /** Whether the ring wears its [TirMark] ([GraphConfig.ringMarks]). */
    val marks: Boolean = true,
) {
    val period: String get() = periodLabel(windowS)
}

fun periodLabel(s: Long): String = when {
    s % 86400 == 0L -> Words.current.periodDays.format(s / 86400)
    else -> Words.current.periodHours.format(s / 3600)
}

/**
 * The mark a time-in-range ring wears (the user, 2026-10-19), by the share the ring shows: a laurel from 90 %, a laurel
 * with a flower on each branch tip from 95 %, and at 100 % (no reading in the window outside the ring's range) the
 * crown with the flowered laurel, by that share alone (the user, 2026-10-19: lows don't take it away), every mark the report's 70 % of data ([Goal.MIN_COVERAGE]). The 100 in the % column beside
 * the chart wears the crown on the same rule ([column]). The Mac decides the same (`TirMark` in SukraraGraph.swift;
 * spikes/graph-fixtures/mark).
 */
enum class TirMark {
    NONE, LAUREL, FLOWERS, CROWN;

    companion object {
        fun of(tir: TimeInRange, range: TirRange): TirMark {
            if (tir.coverage < Goal.MIN_COVERAGE) return NONE
            val x = tir.of(range)
            if (x >= 1.0) return CROWN
            val shown = Math.round(x * 100)
            return when { shown >= 95 -> FLOWERS; shown >= 90 -> LAUREL; else -> NONE }
        }

        fun column(tir: TimeInRange) = of(tir, TirRange.IN_RANGE) == CROWN
    }
}

/** The crown (a 12 × 7.4 outline with rounded joins), drawn [w] wide with its bottom centre at ([x], [bottom]). */
object Crown {
    private val PTS = floatArrayOf(0.7f, 7.4f, 0f, 1.7f, 3.3f, 4.3f, 6f, 0f, 8.7f, 4.3f, 12f, 1.7f, 11.3f, 7.4f)
    const val H = 7.4f / 12f

    fun path(x: Float, bottom: Float, w: Float): Path {
        val k = w / 12f
        return Path().apply {
            for (i in PTS.indices step 2) {
                val px = x + (PTS[i] - 6f) * k; val py = bottom + (PTS[i + 1] - 7.4f) * k
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    fun paint(color: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL_AND_STROKE; strokeJoin = Paint.Join.ROUND; this.color = color; strokeWidth = w * 0.8f / 12f
    }

    fun draw(cv: Canvas, x: Float, bottom: Float, w: Float, color: Int) = cv.drawPath(path(x, bottom, w), paint(color, w))
}

/**
 * The laurel: two branches of leaves up the sides of a ring of outer radius [r], open at the top and bottom, the stems
 * on a circle of 1.25 [r]. Next to a slot without a laurel a side stays whole (the slots move apart, [Slots.extraGap],
 * and the neighbour covers what still reaches it). Between two laurelled rings the slots sit so their facing stems
 * touch in the middle of the gap, so the two wreaths meet there; those sides ([Side.MERGED]) keep their stem and inner
 * leaves but leave out the outer leaves that would cross into the other branch. A faded colour fades the whole laurel
 * at once (a layer), so overlapping leaves don't show darker.
 */
object Laurel {
    enum class Side { FULL, MERGED }

    fun draw(cv: Canvas, cx: Float, cy: Float, r: Float, color: Int, left: Side = Side.FULL, right: Side = Side.FULL) {
        val a = color ushr 24
        val layer = if (a < 255) cv.saveLayerAlpha(null, a) else -1
        val c = color or (0xFF shl 24)
        branch(cv, cx, cy, r, c, true, left)
        branch(cv, cx, cy, r, c, false, right)
        if (layer >= 0) cv.restoreToCount(layer)
    }

    /**
     * One side's branch: climbing from below the middle (135° / 45°) to above it (210° / -30°), an outer and an inner
     * leaf per step leaning up it, the inner ones clear of the ring; a merged side keeps only its two lowest and its
     * highest outer leaves. The flowers are [Blooms]'.
     */
    private fun branch(cv: Canvas, cx: Float, cy: Float, r: Float, color: Int, left: Boolean, side: Side) {
        val sr = r * 1.25f
        cv.drawArc(RectF(cx - sr, cy - sr, cx + sr, cy + sr), if (left) 131f else -34f, 83f, false,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeWidth = r * 0.032f; strokeCap = Paint.Cap.ROUND; this.color = color
            })
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val shape = RectF(-r * 0.135f, -r * 0.056f, r * 0.135f, r * 0.056f)
        fun at(rad: Float, deg: Float, rot: Float) {
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
            if (side == Side.FULL || k <= 1 || k == 5) at(r * 1.35f, a, a + s * 90 - s * 32)
            at(r * 1.17f, a + s * 5, a + s * 90 + s * 32)
        }
    }
}

/**
 * The segmented time-in-range ring: the % of [RingSpec.range] in the middle, the window under it, and the perimeter
 * split by band, clockwise from 12 o'clock in [RingSpec.order] (default: very good, in range, low, very low, high,
 * very high). [emphasis] fades the secondary rings (1 = main). [countdown]: the sensor countdown ring over it
 * ([SensorBadge.drawCountdown]). A [TirMark] leaves the ring its full size and may overflow the slot: the laurel up its
 * sides (from 90 %, flowered from 95 % and at 100 %; [Slots.drawUnder], drawn for every slot before the rings, so what is next to it covers
 * it), and at 100 % the crown in the text colour set into 12 o'clock like a cap, cut out of the ring and the countdown.
 */
object TirRing {
    fun draw(cv: Canvas, cx: Float, cy: Float, r: Float, tir: TimeInRange, spec: RingSpec,
             pal: GraphPalette, emphasis: Float, d: Float, countdown: Double? = null) {
        val mark = mark(tir, spec)
        val sw = r * 0.2f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.BUTT }
        val oval = RectF(cx - r + sw / 2, cy - r + sw / 2, cx + r - sw / 2, cy + r - sw / 2)
        val alpha = (255 * emphasis).toInt()
        val crownW = r * 0.74f
        val crownBottom = cy - r * 0.72f
        val layer = if (mark == TirMark.CROWN) cv.saveLayer(null, null) else -1
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
        SensorBadge.drawCountdown(cv, cx, cy, r, countdown, pal)
        if (layer >= 0) {
            cv.drawPath(Crown.path(cx, crownBottom, crownW), Crown.paint(0xFF000000.toInt(), crownW).apply {
                strokeWidth += 2 * r * 0.06f; blendMode = BlendMode.DST_OUT
            })
            cv.restoreToCount(layer)
            // faded as one layer: fill and outline overlapping at partial alpha would show a darker rim
            val fade = if (alpha < 255) cv.saveLayerAlpha(null, alpha) else -1
            Crown.draw(cv, cx, crownBottom, crownW, pal.ink or (0xFF shl 24))
            if (fade >= 0) cv.restoreToCount(fade)
        }
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ColorUtils.setAlphaComponent(pal.ink, alpha); textAlign = Paint.Align.CENTER
            typeface = RoundedFont.typeface(800); textSize = r * 0.78f
        }
        val sub = Paint(num).apply {
            textSize = r * 0.34f; typeface = RoundedFont.typeface(600)
            color = ColorUtils.setAlphaComponent(if (spec.range == TirRange.TIGHT) pal.tight else pal.inkSoft, alpha)
        }
        val v = if (tir.empty) "–" else text(tir, spec)
        val base = cy + num.textSize * 0.28f
        if (v.length > 3) num.textSize *= 0.7f // "69.7", "<0.1": the decimals fit inside the ring
        num.textSize = min(num.textSize, num.textSize * (r - sw) * 1.5f / num.measureText(v).coerceAtLeast(1f)) // "100" too
        cv.drawText(v, cx, base, num)
        cv.drawText(spec.period, cx, base + sub.textSize * 1.1f, sub)
    }

    /** The ring's [TirMark] (none without data or with the rewards turned off). */
    fun mark(tir: TimeInRange, spec: RingSpec) = if (tir.empty || !spec.marks) TirMark.NONE else TirMark.of(tir, spec.range)

    /** The number in the middle: in range against its > 70 % goal, very good on its own (ShareText's rule). */
    fun text(tir: TimeInRange, spec: RingSpec): String =
        ShareText.of(tir.of(spec.range), if (spec.range == TirRange.IN_RANGE) Goal.IN_RANGE else null)

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
            typeface = RoundedFont.typeface(800); textSize = radius * 0.62f
        }
        val unit = Paint(num).apply { textSize = radius * 0.3f; typeface = RoundedFont.typeface(600) }
        val sub = Paint(num).apply {
            textAlign = Paint.Align.CENTER; textSize = radius * 0.3f; typeface = RoundedFont.typeface(600)
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
 * ([last], with [lastElse] while there's none) and the status badge under the value ([badge]). A surface builds it
 * from its place's settings only; [sensorTime] has no default so every place passes its own.
 */
data class CardStyle(
    val graph: GraphStyle? = GraphStyle(),
    val rings: List<RingSpec> = listOf(RingSpec(86400), RingSpec(7 * 86400L)),
    val value: Boolean = true,
    val last: LastPlace = LastPlace.OFF,
    val lastElse: RingSpec? = null,
    val badge: Boolean = true,
    /** The phone's 24-hour setting for the card's clock times (the graph's when it has one). */
    val hour24: Boolean = graph?.hour24 ?: Clock.hour24,
    /** The sensor's time left ([SensorBadge]) on this card ([GraphConfig.sensorTime]); warm-up and ended show regardless. */
    val sensorTime: Boolean,
    /** Slots drawn as discs of this colour ([CardPart.SLOTS] on a coloured surface: the notification); null = none. */
    val slotDisc: Int? = null,
)

/**
 * Which part of a card one [GlucoseCard.draw] call paints. A surface that puts parts of the card in boxes of its own
 * (the app panel, the lock screen, the notification) draws each part with the SAME whole-card [CardStyle], so every
 * decision that depends on the whole card (where the sensor badge goes, which slots show) is still made once, here.
 * [ALL] = the whole card in one box; [HEADER] = the value and the slots beside it; [VALUE] = the value alone;
 * [SLOTS] = the slots alone (right-aligned in the box); [GRAPH] = the chart alone.
 */
enum class CardPart { ALL, HEADER, VALUE, SLOTS, GRAPH }

/** Where the sensor's time left goes on a card ([GlucoseCard.sensorSpot]). */
enum class SensorSpot {
    /** In the chart's hour-label row, at its left edge, under the % column and the distribution (hour labels start after it). */
    GRAPH,
    /** Right of the trend arrow, top-aligned with the number's caps (a card without a labelled chart). */
    VALUE,
    NONE,
}

/**
 * Value + trend arrow + time-in-range rings + chart in one drawing (investigation 16's mock-up), the ONLY renderer of
 * the card: every surface ([CardSurface]) draws through [draw]. The layout adapts to the size: rings that don't fit
 * are dropped (last first), short wide cards put the value left of the chart.
 */
object GlucoseCard {
    /** A ring is only drawn when its window has at least this much data. */
    const val MIN_COVERAGE = 0.25

    /** Tests: every [CardLayout] drawn and every [GlucoseLook] decided ([face]), as it happens (null in the app). */
    @Volatile var observer: ((CardLayout) -> Unit)? = null
    @Volatile var faceObserver: ((CardSurface, GlucoseLook) -> Unit)? = null

    /**
     * Draw [part] of the card [st] for [surface] into ([w] × [h]) at the canvas origin and return what was laid out.
     * [alerts] = the alert rules behind the badge, the mute lines on the chart and the last-treatment ring (null = none
     * of them); every caller gets the app's rules unless it passes its own. [motion]: the app panel's animation
     * (paint only).
     */
    fun draw(
        cv: Canvas, w: Float, h: Float, readings: List<Reading>, treatments: List<Treatment>, nowS: Long,
        st: CardStyle, pal: GraphPalette, d: Float, surface: CardSurface, part: CardPart,
        metricsOf: (RingSpec, Bands) -> GlucoseMetrics = { r, b -> GlucoseMetrics.of(readings, nowS - r.windowS, nowS, b) },
        alerts: AlertSettings? = Alerts.settings.value,
        sensor: SensorInfo? = SensorLife.current.value,
        countdownH: Int = SensorLife.countdown.value,
        paused: Map<String, Long> = Alerts.paused.value,
        motion: CardMotion? = null,
    ): CardLayout {
        val rec = CardLayout(surface, part, st, w, h, d)
        val latest = readings.maxByOrNull { it.t }
        val status = alerts?.let { AlertStatus.of(latest, it, treatments, nowS, paused) } ?: AlertStatus.None
        val last = if (st.last != LastPlace.OFF && alerts != null) LastTreatment.of(treatments, alerts, status, nowS) else null
        val fr = Freshness.of(latest?.let { nowS - it.t })
        val sl = SensorBadge.look(sensor, nowS, latest?.t)
        val graph = st.graph?.takeIf { fr.showGraph }
        val bands = (st.graph?.bands ?: Bands()).sane()
        val spot = sensorSpot(st, sl, fr)
        val countdown = SensorBadge.ring(sl, sensor, nowS, countdownH)
        if (part == CardPart.SLOTS) {
            drawRings(cv, RectF(0f, 0f, w, h), st, last, nowS, bands, pal, d, metricsOf, countdown, rec, dedicated = true)
            return rec.also { observer?.invoke(it) }
        }
        // one part in a box of its own: that box is the part's whole area
        val sideBySide = part == CardPart.ALL && graph != null && st.value && h < 100 * d && w > 2.6f * h
        val headerH = when {
            part == CardPart.VALUE || part == CardPart.HEADER -> h
            part == CardPart.GRAPH || !st.value -> 0f
            graph == null -> h
            sideBySide -> h
            else -> (h * 0.34f).coerceIn(30 * d, 80 * d)
        }
        val valueRight = if (sideBySide) min(w * 0.32f, 120 * d) else w

        val chart = when {
            sideBySide -> RectF(valueRight + 6 * d, 0f, w, h)
            part == CardPart.GRAPH -> RectF(0f, 0f, w, h)
            else -> RectF(0f, headerH + if (st.value) 6 * d else 0f, w, h)
        }
        if (part == CardPart.ALL || part == CardPart.GRAPH) when {
            graph != null -> GlucoseGraph.draw(cv, chart, readings, treatments, nowS, graph, pal, d, alerts,
                sensor = sl.takeIf { spot == SensorSpot.GRAPH }, rec = rec)
            st.graph != null && chart.height() > 24 * d -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 12 * d; textAlign = Paint.Align.CENTER }
                val face = face(latest, nowS, sl, surface)
                val msg = when (face.state) {
                    GlucoseLook.State.WARMING_UP -> Words.current.warmingUp
                    GlucoseLook.State.ENDED -> Words.current.sensorEnded
                    else -> latest?.let { Words.current.noReadingsSince.format(Freshness.timeText(it.t, nowS, st.hour24)) } ?: Words.current.noReadingsYet
                }
                cv.drawText(msg, chart.centerX(), chart.centerY(), p)
                rec.add("chart.message", chart, face.state.name, exact = true)
            }
        }
        if (st.value && part != CardPart.GRAPH) {
            val box = RectF(0f, 0f, valueRight, if (sideBySide) h else headerH)
            val ringsW = if (sideBySide || part == CardPart.VALUE) 0f
                else drawRings(cv, box, st, last, nowS, bands, pal, d, metricsOf, countdown, rec, dedicated = false)
            val look = if (st.badge) StatusBadge.look(status, nowS) else null
            drawValue(cv, RectF(box.left, box.top, box.right - ringsW, box.bottom), latest, fr, nowS, bands, pal, d, look,
                sl, spot == SensorSpot.VALUE, st.hour24, rec, motion)
        }
        return rec.also { observer?.invoke(it) }
    }

    /**
     * The value's decision for the newest reading [latest] for [surface]: the card's number and every glyph surface
     * ([CardSurface.Kind.GLYPH]) take it from here ([Freshness.look]: a warming-up or ended sensor first, then the
     * reading's freshness steps), so the observer sees every surface's decision.
     */
    fun face(latest: Reading?, nowS: Long, sensor: SensorBadge.Look?, surface: CardSurface): GlucoseLook =
        Freshness.look(latest?.sgv, latest?.t, latest?.rate, nowS, ended = sensor?.ended == true, warmup = sensor?.warmup)
            .also { f -> faceObserver?.invoke(surface, f) }

    /**
     * Where the sensor's time left goes on a card [st] (the WHOLE card, also when its parts are drawn in separate boxes):
     * the one decision every surface uses (widgets of every kind and size, the app's panel, the lock screen, the
     * notification; the Mac's `GlucoseCard.sensorSpot` is the same rule, both checked by spikes/graph-fixtures/place).
     * In the chart's hour-label row whenever the card shows a chart with hour labels (the user, 2026-10-19/21: "left of
     * the x axis, under the distribution"), with or without the % column; beside the arrow on a card without one; nowhere
     * when the place's setting hides it ([CardStyle.sensorTime]), nothing is known, or the sensor warms up / has ended
     * (the value's place shows those states instead).
     */
    fun sensorSpot(st: CardStyle, look: SensorBadge.Look?, fr: Freshness): SensorSpot = when {
        look == null || !st.sensorTime || look.warmup != null || look.ended -> SensorSpot.NONE
        st.graph != null && fr.showGraph && st.graph.axisLabels -> SensorSpot.GRAPH
        st.value && fr.showValue -> SensorSpot.VALUE
        else -> SensorSpot.NONE
    }

    /**
     * Slots ([Slots.arrange]) right-aligned in [box] (main rightmost); returns the width used. Beside the value
     * ([dedicated] false) they take at most 55 % of the row; in a box of their own ([CardPart.SLOTS]) as many as fit
     * whole, laurels included. [CardStyle.slotDisc]: each slot on a disc, the rings shrunk so a laurel stays inside.
     */
    private fun drawRings(cv: Canvas, box: RectF, st: CardStyle, last: LastTreatment.Info?, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float, metricsOf: (RingSpec, Bands) -> GlucoseMetrics, countdown: Double?,
                          rec: CardLayout, dedicated: Boolean): Float {
        val extra = st.last != LastPlace.OFF && (last != null || st.lastElse != null)
        if (st.rings.isEmpty() && !extra) return 0f
        val size = if (dedicated && st.slotDisc != null) min(box.height(), 84 * d) else min(box.height() * 0.96f, 84 * d)
        if (size < 30 * d) return 0f
        val gap = if (dedicated && st.slotDisc != null) 6 * d else size * 0.14f
        val fit = ((box.width() * (if (dedicated) 1f else 0.55f) + gap) / (size + gap)).toInt().coerceAtMost(4)
        if (fit < 1) return 0f
        val rings = st.rings.take(3).mapIndexedNotNull { i, spec ->
            metricsOf(spec, bands).takeIf { it.tir.coverage >= MIN_COVERAGE }?.let { Triple(i, spec, it) }
        }
        val fallback = if (st.last != LastPlace.OFF && last == null) st.lastElse?.let { s ->
            metricsOf(s, bands).takeIf { it.tir.coverage >= MIN_COVERAGE }?.let { s to it }
        } else null
        val x = box.right
        var items = Slots.arrange(rings, st.last, last, fallback, fit)
        // in a box of its own every slot fits whole, the laurels' extra room included
        if (dedicated) while (items.isNotEmpty() && items.size * size + (items.size - 1) * gap + Slots.extraWidth(items, size / 2) > box.width() + 0.5f)
            items = Slots.arrange(rings, st.last, last, fallback, items.size - 1)
        if (items.isEmpty()) return 0f
        val main = Slots.mainIndex(items)
        val cx = Slots.centers(items, x, size, gap)
        val cy = box.centerY()
        val disc = st.slotDisc.takeIf { dedicated }
        val marked = items.any(Slots::hasLaurel)
        val ringR = if (disc == null) size / 2 else if (marked) size / 2 / 1.5f else size / 2 - 3 * d
        Slots.drawUnder(cv, items, cx, cy, ringR, pal)
        for ((i, item) in items.withIndex()) {
            if (disc != null) cv.drawCircle(cx[i], cy, if (marked) ringR + 3 * d else size / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = disc })
            Slots.draw(cv, item, cx[i], cy, ringR, nowS, pal, d, countdown.takeIf { i == main })
            rec.add("slot", RectF(cx[i] - ringR, cy - ringR, cx[i] + ringR, cy + ringR), Slots.describe(item, i == main, countdown.takeIf { i == main }), exact = true)
        }
        rec.add("slots", RectF(cx.last() - size / 2, box.top, box.right, box.bottom), "${items.size}", exact = true)
        rec.add("slots.box", box, exact = true)
        return x - (cx.last() - size / 2) + gap
    }

    /**
     * The sensor has ended ([SensorBadge.Look.ended]): "Sensor ended" in the secondary colour (a state, not an error) and
     * under it the action pill "Replace sensor" ([StatusBadge.replaceSensor], the same pill as "Eat carbs": full words,
     * short words or icon only as room allows). A box too short for the pill's row gets the icon-only pill beside the
     * words. The Mac does the same (SukraraGraph.swift `drawEnded`).
     */
    private fun drawEnded(cv: Canvas, box: RectF, nowS: Long, pal: GraphPalette, d: Float, rec: CardLayout) {
        val w = Words.current
        val pill = StatusBadge.replaceSensor(w)
        val badgeH = (box.height() * 0.24f).coerceIn(14 * d, 24 * d)
        val row = box.height() - badgeH * 1.25f >= 20 * d
        val textBox = if (row) RectF(box.left, box.top, box.right, box.bottom - badgeH * 1.25f) else box
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = pal.inkSoft; typeface = Typeface.create(Typeface.DEFAULT, 600, false); textSize = min(textBox.height() * 0.5f, 20 * d)
        }
        val iconRoom = if (row) 0f else badgeH * 1.3f
        // the checkered flag before the words: the status bar's "sensor ended" glyph, so the two read as one thing
        fun flagW() = p.textSize * 1.25f
        val tw = p.measureText(w.sensorEnded) + flagW()
        if (tw > box.width() - iconRoom) p.textSize *= ((box.width() - iconRoom) / tw).coerceAtLeast(0.45f)
        val base = textBox.centerY() + p.textSize * 0.36f
        val fs = p.textSize * 1.2f   // sports_score fills ~2/3 of its box
        SensorBadge.flag(cv, box.left - fs * 0.2f, base - p.textSize * 0.72f - (fs - p.textSize * 0.72f) / 2, fs, pal.inkSoft)
        cv.drawText(w.sensorEnded, box.left + flagW(), base, p)
        rec.add("value.ended", RectF(box.left, textBox.top, box.left + flagW() + p.measureText(w.sensorEnded), textBox.bottom))
        if (row) {
            val top = textBox.bottom + badgeH * 0.25f
            val pw = StatusBadge.draw(cv, box.left, top, badgeH, box.width(), pill, pal.dark)
            rec.add("value.pill", RectF(box.left, top, box.left + pw, top + badgeH), "[row]")
        } else {
            val x = box.left + flagW() + p.measureText(w.sensorEnded) + badgeH * 0.3f
            if (x + badgeH <= box.right) {
                StatusBadge.draw(cv, x, box.centerY() - badgeH / 2, badgeH, badgeH, pill, pal.dark)
                rec.add("value.pill", RectF(x, box.centerY() - badgeH / 2, x + badgeH, box.centerY() + badgeH / 2), "[icon]")
            } else rec.add("value.pill", info = "(no room)")
        }
    }

    /**
     * A new sensor warming up: the time left until the first reading ("42m") where the number goes, in the secondary
     * colour, the sensor disc beside it filling up, and "Ready at 19:45" under it.
     */
    private fun drawWarmup(cv: Canvas, box: RectF, look: SensorBadge.Look, small: Paint, nowS: Long, pal: GraphPalette, d: Float,
                           hour24: Boolean, rec: CardLayout) {
        val line = look.warmupLine(nowS, hour24)
        val numBox = RectF(box.left, box.top, box.right, box.bottom - small.textSize * 1.25f)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; typeface = RoundedFont.typeface(700); textSize = numBox.height() * 1.18f }
        val s = look.warmup ?: return
        val need = num.measureText(s) + num.textSize * 0.6f
        if (need > box.width()) num.textSize *= box.width() / need
        val capH = num.textSize * 0.72f
        val cy = numBox.centerY()
        val baseY = cy + capH / 2
        cv.drawText(s, box.left - num.textSize * 0.04f, baseY, num)
        val dh = capH * 0.5f
        SensorBadge.disc(cv, box.left + num.measureText(s) + num.textSize * 0.12f, cy - dh / 2, dh, look, pal)
        val t = Paint(small)
        if (t.measureText(line) > box.width()) t.textSize *= box.width() / t.measureText(line)
        cv.drawText(line, box.left, baseY + small.textSize * 1.25f, t)
        rec.add("value.warmup", RectF(box.left, cy - capH / 2, box.left + need, cy + capH / 2), s)
    }

    private val noReadingsPath by lazy { androidx.core.graphics.PathParser.createPathFromPathData(GlucoseLook.NO_READINGS_PATH) }

    /** The "no readings" icon (Material Symbols `sensors_off`, [GlucoseLook.NO_READINGS_PATH]), [size] square, top-left at ([x], [y]). */
    fun drawNoReadings(cv: Canvas, x: Float, y: Float, size: Float, color: Int) {
        cv.save()
        cv.translate(x, y)
        cv.scale(size / 960f, size / 960f)
        cv.translate(0f, 960f)
        cv.drawPath(noReadingsPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        cv.restore()
    }

    /**
     * The number + arrow, and under it the late reading's time and/or the status badge ([look]). The badge takes a row
     * under the number when the box is tall enough to keep the number at least 26 dp; otherwise it shrinks to its icon
     * beside the arrow, or is left out when there's no room there either.
     */
    private fun drawValue(cv: Canvas, box: RectF, r: Reading?, fr: Freshness, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float, look: StatusBadge.Look?, sensor: SensorBadge.Look?, lifeBadge: Boolean,
                          hour24: Boolean, rec: CardLayout, motion: CardMotion?) {
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = max(11 * d, box.height() * 0.17f) }
        val face = face(r, nowS, sensor, rec.surface)
        when (face.state) {
            GlucoseLook.State.WARMING_UP -> return drawWarmup(cv, box, sensor!!, small, nowS, pal, d, hour24, rec)
            // an ended sensor says so as soon as it has ended, instead of a value or "No reading since" (not an error)
            GlucoseLook.State.ENDED -> return drawEnded(cv, box, nowS, pal, d, rec)
            GlucoseLook.State.NO_READINGS, GlucoseLook.State.NONE -> {
                val big = Paint(small).apply { textSize = min(box.height() * 0.26f, 20 * d); typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
                val w = Words.current
                val line1 = if (r == null) w.noReadingsYet else w.noReadingSince
                val line2 = r?.let { Freshness.timeText(it.t, nowS, hour24) }
                // no readings for 20 min: the status bar's "no readings" icon (sensors_off) before the words, as tall as both
                // lines; icon and words fit the room left of the rings (as on the Mac)
                fun iconW() = if (r != null) big.textSize * (2.1f + 0.35f) else 0f
                val widest = listOfNotNull(line1, line2).maxOf { big.measureText(it) }
                if (widest + iconW() > box.width() - 8 * d && widest > 0)
                    big.textSize *= max(0.4f, (box.width() - 8 * d) / (widest + iconW()))
                val y = box.centerY() - if (line2 == null) -big.textSize / 3 else big.textSize * 0.15f
                val icon = if (r != null) big.textSize * 2.1f else 0f
                if (r != null) drawNoReadings(cv, box.left, y - big.textSize * 0.95f, icon, pal.inkSoft)
                val x = box.left + if (r != null) icon + big.textSize * 0.35f else 0f
                cv.drawText(line1, x, y, big)
                if (line2 != null) cv.drawText(line2, x, y + big.textSize * 1.25f, big)
                rec.add("value.none", RectF(box.left, y - big.textSize, box.right, y + (if (line2 != null) big.textSize * 1.25f else 0f)), face.state.name)
                return
            }
            GlucoseLook.State.VALUE -> {}
        }
        r!!
        val color = motion?.color ?: if (fr.strike) pal.inkSoft else pal.at(bands, r.sgv)
        // A late reading's time goes under the number (the user: beside it, it took the rings' room).
        val badgeH = (box.height() * 0.24f).coerceIn(14 * d, 24 * d)
        val badgeRow = look != null && box.height() - badgeH * 1.25f - (if (fr.showTime) small.textSize * 1.25f else 0f) >= 26 * d
        // the late time shares the badge's row (after the badge) when there is one
        val timeH = if (badgeRow) badgeH * 1.25f else if (fr.showTime) small.textSize * 1.25f else 0f
        val numBox = RectF(box.left, box.top, box.right, box.bottom - timeH)
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; typeface = RoundedFont.typeface(700)
            textSize = numBox.height() * 1.18f
            isStrikeThruText = fr.strike
        }
        val s = face.text!!
        // Fit the width as well: number + arrow must stay left of the rings.
        val arrowFactor = 0.62f
        // the sensor badge beside the arrow (SensorSpot.VALUE) is part of the row: the number shrinks to make room for it
        val life = sensor?.takeIf { lifeBadge }
        // (the badge starts at the arrow's end: number + 0.08 + 1.15 × the arrow, 0.62 of the size)
        fun need() = num.measureText(s) + (life?.let {
            num.textSize * 0.82f + SensorBadge.width(it, (num.textSize * 0.72f * 0.24f).coerceIn(9 * d, 14 * d), pal)
        } ?: (num.textSize * arrowFactor * 1.1f))
        repeat(3) { need().let { n -> if (n > box.width()) num.textSize *= box.width() / n * 0.995f } }
        // no row for the badge: its icon goes beside the arrow if there's room
        val iconBadge = look != null && !badgeRow &&
            box.width() - (num.measureText(s) + num.textSize * arrowFactor * 1.2f) >= min(numBox.height() * 0.5f, 24 * d)
        val capH = num.textSize * 0.72f
        val cy = numBox.centerY()
        val baseY = cy + capH / 2
        if (motion?.prev != null && motion.prev != s && motion.roll < 1f) rollNumber(cv, s, motion, box.left - num.textSize * 0.04f, baseY, capH, num)
        else cv.drawText(s, box.left - num.textSize * 0.04f, baseY, num)
        rec.add("value.number", RectF(box.left, cy - capH / 2, box.left + num.measureText(s), cy + capH / 2), if (fr.strike) "$s struck" else s)
        var x = box.left + num.measureText(s) + num.textSize * 0.08f
        val arrowSize = num.textSize * arrowFactor
        val rate = r.rate
        if (face.trend == TrendMark.ARROW && rate != null) {
            cv.save()
            cv.translate(x + arrowSize / 2, cy)
            cv.rotate(motion?.arrowDeg ?: Trend.angleDeg(rate))
            ValueGlyphs.arrow(cv, arrowSize * 1.05f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color },
                double = Trend.isDouble(rate))
            cv.restore()
            rec.add("value.trend", RectF(x, cy - arrowSize / 2, x + arrowSize, cy + arrowSize / 2), if (Trend.isDouble(rate)) "arrow double" else "arrow single")
        } else {
            // no trend (no rate, or the value is struck through): the hollow ring in the arrow's place
            cv.drawCircle(x + arrowSize / 2, cy, TrendMark.ringRadius(arrowSize), Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.color = color; style = Paint.Style.STROKE; strokeWidth = TrendMark.ringStroke(arrowSize)
            })
            rec.add("value.trend", RectF(x, cy - arrowSize / 2, x + arrowSize, cy + arrowSize / 2), "ring")
        }
        // the sensor life badge: right of the arrow (and of the icon badge), top-aligned with the number's caps
        sensor?.takeIf { lifeBadge }?.let { sl ->
            val sh = (capH * 0.24f).coerceIn(9 * d, 14 * d)
            val sx = x + arrowSize * 1.15f + (if (iconBadge && look != null) min(numBox.height() * 0.5f, 24 * d) + arrowSize * 0.15f else 0f)
            val sw = SensorBadge.width(sl, sh, pal)
            if (sx + sw <= box.right) {
                SensorBadge.draw(cv, sx, cy - capH / 2, sh, sl, pal)
                rec.add("value.sensor", RectF(sx, cy - capH / 2, sx + sw, cy - capH / 2 + sh), sl.label)
            } else rec.add("value.sensor", info = "${sl.label} (no room)")
        }
        if (badgeRow && look != null) {
            val top = numBox.bottom + badgeH * 0.25f
            val bw = StatusBadge.draw(cv, box.left, top, badgeH, box.width(), look, pal.dark)
            rec.add("value.status", RectF(box.left, top, box.left + bw, top + badgeH), "${look.kind} [row]")
            if (fr.showTime) {
                val t = Paint(small).apply { textSize = badgeH * 0.56f }
                val tx = box.left + bw + badgeH * 0.35f
                val text = Freshness.timeText(r.t, nowS, hour24)
                if (tx + t.measureText(text) <= box.right) {
                    cv.drawText(text, tx, top + badgeH / 2 + t.textSize * 0.36f, t)
                    rec.add("value.time", RectF(tx, top, tx + t.measureText(text), top + badgeH), "[badge row]")
                } else rec.add("value.time", info = "[badge row] (no room)")
            }
        } else {
            if (iconBadge && look != null) {
                val ih = min(numBox.height() * 0.5f, 24 * d)
                StatusBadge.draw(cv, x + arrowSize * 1.15f, cy - ih / 2, ih, ih, look, pal.dark)
                rec.add("value.status", RectF(x + arrowSize * 1.15f, cy - ih / 2, x + arrowSize * 1.15f + ih, cy + ih / 2), "${look.kind} [icon]")
            } else if (look != null) rec.add("value.status", info = "${look.kind} (no room)")
            if (fr.showTime) {
                val text = Freshness.timeText(r.t, nowS, hour24)
                cv.drawText(text, box.left, baseY + small.textSize * 1.25f, small)
                rec.add("value.time", RectF(box.left, baseY, box.left + small.measureText(text), baseY + small.textSize * 1.25f), "[own row]")
            }
        }
    }

    /**
     * The panel's rolling number ([CardMotion]): each digit that changed slides out and the new one in (up for a
     * rising value), clipped to the number's line; the digits are matched from the right, as RollingNumber did.
     */
    private fun rollNumber(cv: Canvas, s: String, m: CardMotion, x0: Float, baseY: Float, capH: Float, num: Paint) {
        val prev = m.prev ?: ""
        val lineH = capH * 1.6f
        val dir = if (m.up) 1f else -1f
        val e = m.roll.coerceIn(0f, 1f)
        cv.save()
        cv.clipRect(x0 - capH, baseY - capH * 1.3f, x0 + num.measureText(s) + capH, baseY + capH * 0.3f)
        val old = Paint(num)
        for (i in s.indices) {
            val x = x0 + num.measureText(s, 0, i)
            val fromRight = s.length - i
            val o = prev.getOrNull(prev.length - fromRight)
            if (o == s[i]) { cv.drawText(s[i].toString(), x, baseY, num); continue }
            val n = Paint(num).apply { alpha = (255 * e).toInt() }
            cv.drawText(s[i].toString(), x, baseY + (1 - e) * lineH * dir, n)
            if (o != null) {
                old.alpha = (255 * (1 - e)).toInt()
                cv.drawText(o.toString(), x, baseY - e * lineH * dir, old)
            }
        }
        cv.restore()
    }
}

/**
 * The value's glyphs for surfaces the system lays out ([CardSurface.Kind.GLYPH]: the notification's header and small
 * icon, the Live Update chip, the SystemUI hook), drawn from the card's decision ([GlucoseCard.face], a [GlucoseLook]):
 * the number in the card's rounded face, the card's arrow, the "no trend" ring ([TrendMark]).
 */
object ValueGlyphs {
    /** The value is sized for at least this wide a number, so a strip beside it doesn't change from 99 to 188. */
    const val REFERENCE = "388"

    /** The unit arrow pointing +x ([scale] px long), at the Mac's geometry; [double] = a double arrow. */
    fun arrow(cv: Canvas, scale: Float, p: Paint, double: Boolean) {
        fun head(tip: Float) = Path().apply {
            moveTo(tip * scale, 0f); lineTo((tip - .40f) * scale, .30f * scale)
            lineTo((tip - .30f) * scale, 0f); lineTo((tip - .40f) * scale, -.30f * scale); close()
        }
        val fill = Paint(p).apply { style = Paint.Style.FILL }
        cv.drawRect(-.42f * scale, -.07f * scale, .25f * scale, .07f * scale, fill)
        cv.drawPath(head(.46f), fill)
        if (double) cv.drawPath(head(.12f), fill)
    }

    /** The arrow for [rate] in a [size] px square, or the "no trend" ring ([TrendMark.NONE]) when it's null. */
    fun trend(cv: Canvas, rate: Double?, size: Int, p: Paint) {
        if (rate == null) {
            cv.drawCircle(size / 2f, size / 2f, TrendMark.ringRadius(size.toFloat()),
                Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = TrendMark.ringStroke(size.toFloat()) })
            return
        }
        cv.save()
        cv.translate(size / 2f, size / 2f)
        cv.rotate(Trend.angleDeg(rate))
        arrow(cv, size * 1.02f, p, double = Trend.isDouble(rate))
        cv.restore()
    }

    /**
     * The trend alone in a [size] px square ([trend]; pass the rate only when the look's trend is the arrow): the Live
     * Update chip's small icon (an alpha mask when [color] is null) or the notification card's arrow.
     */
    fun trendIcon(rate: Double?, size: Int = 96, color: Int? = null): Bitmap =
        Bitmap.createBitmap(size, size, if (color == null) Bitmap.Config.ALPHA_8 else Bitmap.Config.ARGB_8888).also {
            trend(Canvas(it), rate, size, Paint(Paint.ANTI_ALIAS_FLAG).apply { if (color != null) this.color = color })
        }

    /** The number paint in the card's rounded face at [px]. */
    fun numberPaint(color: Int, px: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; textSize = px; typeface = RoundedFont.typeface(700)
    }

    /**
     * [s] (the look's text) in the card's rounded face (proportional figures like SF Pro Rounded) at [px] text size,
     * struck through when [strike], cropped to the digits' height (like a TextView without font padding).
     */
    fun number(s: String, color: Int, px: Float, strike: Boolean = false): Bitmap {
        val p = numberPaint(color, px).apply { isStrikeThruText = strike }
        val b = android.graphics.Rect().also { p.getTextBounds("0123456789", 0, 10, it) }
        val pad = (px * 0.06f).toInt() + 1
        return Bitmap.createBitmap(kotlin.math.ceil(p.measureText(s)).toInt() + 2, b.height() + 2 * pad, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawText(s, 0f, (pad - b.top).toFloat(), p)
        }
    }

    /**
     * Alpha-mask small icon: the number fills the width, the trend arrow (or the "no trend" ring) underneath; struck
     * from 5 min, at half strength.
     */
    fun icon(look: GlucoseLook, rate: Double?, size: Int = 96): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8)
        val cv = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val text = look.text ?: return bmp
        // struck: everything at half strength as one layer (the bar over the digits doesn't add up)
        if (look.strike) cv.saveLayerAlpha(null, 128)
        val numberH = size * 0.62f
        p.textSize = numberH / 0.72f
        p.textSize *= min(1f, size / p.measureText(text))
        cv.drawText(text, size / 2f, numberH, p)
        if (look.strike) cv.drawRect(0f, numberH * 0.45f, size.toFloat(), numberH * 0.45f + size * 0.07f, p)
        val arrowBox = size - numberH - size * 0.04f
        cv.save()
        cv.translate(size / 2f, size - arrowBox / 2)
        if (look.trend == TrendMark.ARROW && rate != null) {
            cv.rotate(Trend.angleDeg(rate))
            arrow(cv, arrowBox * 1.15f, p, double = Trend.isDouble(rate))
        } else {
            cv.drawCircle(0f, 0f, TrendMark.ringRadius(arrowBox), Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = TrendMark.ringStroke(arrowBox) })
        }
        cv.restore()
        if (look.strike) cv.restore()
        return bmp
    }
}
