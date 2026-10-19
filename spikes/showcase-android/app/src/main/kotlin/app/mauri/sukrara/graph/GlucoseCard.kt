// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Words
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
 * crown with the flowered laurel. The laurels need the lows' goals met (below < 4 %, very low < 1 %: a reward never
 * sits on a day of lows), every mark the report's 70 % of data ([Goal.MIN_COVERAGE]). The 100 in the % column beside
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
            if (Goal.BELOW.metShare(tir.below) != true || Goal.VERY_LOW.metShare(tir.veryLow) != true) return NONE
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
 * ([last], with [lastElse] while there's none) and the status badge under the value ([badge]).
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
        sensor: SensorInfo? = SensorLife.current.value,
        countdownH: Int = SensorLife.countdown.value,
    ) {
        val latest = readings.maxByOrNull { it.t }
        val status = alerts?.let { AlertStatus.of(latest, it, treatments, nowS) } ?: AlertStatus.None
        val last = if (st.last != LastPlace.OFF && alerts != null) LastTreatment.of(treatments, alerts, status, nowS) else null
        val fr = Freshness.of(latest?.let { nowS - it.t })
        val sl = SensorBadge.look(sensor, nowS, latest?.t)
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
            val countdown = SensorBadge.ring(sl, sensor, nowS, countdownH)
            val ringsW = if (sideBySide) 0f else drawRings(cv, box, st, last, nowS, bands, pal, d, metricsOf, countdown)
            val look = if (st.badge) StatusBadge.look(status, nowS) else null
            drawValue(cv, RectF(box.left, box.top, box.right - ringsW, box.bottom), latest, fr, nowS, bands, pal, d, look,
                sl, st.hour24)
        }
        val chart = if (sideBySide) RectF(valueRight + 6 * d, 0f, w, h) else RectF(0f, headerH + if (st.value) 6 * d else 0f, w, h)
        when {
            graph != null -> GlucoseGraph.draw(cv, chart, readings, treatments, nowS, graph, pal, d, alerts)
            st.graph != null && chart.height() > 24 * d -> {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = 12 * d; textAlign = Paint.Align.CENTER }
                val msg = when {
                    sl?.warmup != null -> Words.current.warmingUp
                    sl?.ended == true -> Words.current.sensorEnded
                    else -> latest?.let { Words.current.noReadingsSince.format(Freshness.timeText(it.t, nowS, st.hour24)) } ?: Words.current.noReadingsYet
                }
                cv.drawText(msg, chart.centerX(), chart.centerY(), p)
            }
        }
    }

    /** Slots ([Slots.arrange]) right-aligned in [box] (main rightmost); returns the width used. */
    private fun drawRings(cv: Canvas, box: RectF, st: CardStyle, last: LastTreatment.Info?, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float, metricsOf: (RingSpec, Bands) -> GlucoseMetrics, countdown: Double?): Float {
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
        val x = box.right
        var used = 0f
        val items = Slots.arrange(rings, st.last, last, fallback, fit)
        val main = Slots.mainIndex(items)
        val cx = Slots.centers(items, x, size, gap)
        Slots.drawUnder(cv, items, cx, box.centerY(), size / 2, pal)
        for ((i, item) in items.withIndex()) Slots.draw(cv, item, cx[i], box.centerY(), size / 2, nowS, pal, d, countdown.takeIf { i == main })
        if (items.isNotEmpty()) used = x - (cx.last() - size / 2) + gap
        return used
    }

    /**
     * The sensor has ended ([SensorBadge.Look.ended]): "Sensor ended" in the secondary colour (a state, not an error) and
     * under it the action pill "Replace sensor" ([StatusBadge.replaceSensor], the same pill as "Eat carbs": full words,
     * short words or icon only as room allows). A box too short for the pill's row gets the icon-only pill beside the
     * words. The Mac does the same (SukraraGraph.swift `drawEnded`).
     */
    private fun drawEnded(cv: Canvas, box: RectF, nowS: Long, pal: GraphPalette, d: Float) {
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
        if (row) StatusBadge.draw(cv, box.left, textBox.bottom + badgeH * 0.25f, badgeH, box.width(), pill, pal.dark)
        else {
            val x = box.left + flagW() + p.measureText(w.sensorEnded) + badgeH * 0.3f
            if (x + badgeH <= box.right) StatusBadge.draw(cv, x, box.centerY() - badgeH / 2, badgeH, badgeH, pill, pal.dark)
        }
    }

    /**
     * A new sensor warming up: the time left until the first reading ("42m") where the number goes, in the secondary
     * colour, the sensor disc beside it filling up, and "Ready at 19:45" under it.
     */
    private fun drawWarmup(cv: Canvas, box: RectF, look: SensorBadge.Look, small: Paint, nowS: Long, pal: GraphPalette, d: Float,
                           hour24: Boolean) {
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
    }

    /**
     * The number + arrow, and under it the late reading's time and/or the status badge ([look]). The badge takes a row
     * under the number when the box is tall enough to keep the number at least 26 dp; otherwise it shrinks to its icon
     * beside the arrow, or is left out when there's no room there either.
     */
    private fun drawValue(cv: Canvas, box: RectF, r: Reading?, fr: Freshness, nowS: Long, bands: Bands,
                          pal: GraphPalette, d: Float, look: StatusBadge.Look? = null, sensor: SensorBadge.Look? = null,
                          hour24: Boolean = Clock.hour24) {
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.inkSoft; textSize = max(11 * d, box.height() * 0.17f) }
        if (sensor?.warmup != null) return drawWarmup(cv, box, sensor, small, nowS, pal, d, hour24)
        // an ended sensor says so as soon as it has ended, instead of a value or "No reading since" (not an error)
        if (sensor?.ended == true) return drawEnded(cv, box, nowS, pal, d)
        if (r == null || !fr.showValue) {
            val big = Paint(small).apply { textSize = min(box.height() * 0.26f, 20 * d); typeface = Typeface.create(Typeface.DEFAULT, 600, false) }
            val w = Words.current
            val line1 = if (r == null) w.noReadingsYet else w.noReadingSince
            val line2 = r?.let { Freshness.timeText(it.t, nowS, hour24) }
            val y = box.centerY() - if (line2 == null) -big.textSize / 3 else big.textSize * 0.15f
            cv.drawText(line1, box.left, y, big)
            if (line2 != null) cv.drawText(line2, box.left, y + big.textSize * 1.25f, big)
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
            this.color = color; typeface = RoundedFont.typeface(700)
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
        // the sensor life badge: right of the arrow (and of the icon badge), top-aligned with the number's caps
        sensor?.let { sl ->
            val sh = (capH * 0.24f).coerceIn(9 * d, 14 * d)
            val sx = x + arrowSize * 1.15f + (if (iconBadge && look != null) min(numBox.height() * 0.5f, 24 * d) + arrowSize * 0.15f else 0f)
            if (sx + SensorBadge.width(sl, sh, pal) <= box.right) SensorBadge.draw(cv, sx, cy - capH / 2, sh, sl, pal)
        }
        if (badgeRow && look != null) {
            val top = numBox.bottom + badgeH * 0.25f
            val bw = StatusBadge.draw(cv, box.left, top, badgeH, box.width(), look, pal.dark)
            if (fr.showTime) {
                val t = Paint(small).apply { textSize = badgeH * 0.56f }
                val tx = box.left + bw + badgeH * 0.35f
                val text = Freshness.timeText(r.t, nowS, hour24)
                if (tx + t.measureText(text) <= box.right) cv.drawText(text, tx, top + badgeH / 2 + t.textSize * 0.36f, t)
            }
        } else {
            if (iconBadge && look != null) {
                val ih = min(numBox.height() * 0.5f, 24 * d)
                StatusBadge.draw(cv, x + arrowSize * 1.15f, cy - ih / 2, ih, ih, look, pal.dark)
            }
            if (fr.showTime) cv.drawText(Freshness.timeText(r.t, nowS, hour24), box.left, baseY + small.textSize * 1.25f, small)
        }
    }
}
