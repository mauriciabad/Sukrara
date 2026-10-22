// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.core.Words
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.PathParser
import app.mauri.sukrara.alerts.AlertEngine
import app.mauri.sukrara.alerts.AlertStatus
import app.mauri.sukrara.alerts.Kind
import app.mauri.sukrara.alerts.MuteCopy
import app.mauri.sukrara.treatments.LastTreatment
import app.mauri.sukrara.treatments.TreatmentMarks
import kotlin.math.min

/**
 * The badge under the glucose value (2026-10-18 round), from [AlertStatus]:
 * - None: nothing.
 * - Needed: a filled pill in the colour of what to log, with its marker: "Eat carbs" (low side), "Take insulin"
 *   (high side).
 * - Taken: the same pill, tonal (the colour at low alpha, text in the colour), with the "notifications paused" bell and
 *   the time left of the mute ("45m left"): alerts wait while the treatment works.
 * - Paused (this device's Mute of the side, or Mute all, while out of range): the same tonal pill and time left;
 *   "Muted" when it has no end ([app.mauri.sukrara.alerts.AlertEngine.NEVER]).
 * - Muted (Mute all, in range or without a current reading; round f30): a tonal pill in the neutral colour (kind
 *   "muted", like "sensor" / "now") with the "notifications off" bell: "Muted · 1h 20m", "Muted" without an end
 *   (alerts/MuteCopy, spikes/alerts/copy.json).
 *
 * One look on every surface: the widgets and the lock screen draw it here, the app's panel with Compose
 * (`ui/Widgets.kt`, the same colours and words from [look]).
 */
object StatusBadge {
    /** [kind] = the treatment kind whose colour and marker the badge uses; [tonal] = Taken. */
    data class Look(val kind: String, val text: String, val short: String, val tonal: Boolean)

    fun look(s: AlertStatus, now: Long): Look? = when (s) {
        AlertStatus.None -> null
        is AlertStatus.Needed ->
            Words.current.let { w ->
                if (s.kind in Kind.LOW_SIDE) Look("carbs", w.eatCarbs, w.carbsShort, false) else Look("rapid", w.takeInsulin, w.insulinShort, false)
            }
        is AlertStatus.Taken -> left(s.until, now, Words.current).let { (text, short) -> Look(s.treatment.k, text, short, true) }
        // muted from the notification: the colour of what answers it (carbs for lows, insulin for highs)
        is AlertStatus.Paused -> Words.current.let { w ->
            val kind = if (s.kind in Kind.LOW_SIDE) "carbs" else "rapid"
            if (s.until == AlertEngine.NEVER) Look(kind, w.muted, w.muted, true)
            else left(s.until, now, w).let { (text, short) -> Look(kind, text, short, true) }
        }
        is AlertStatus.Muted -> Words.current.let { w ->
            if (s.until == AlertEngine.NEVER) Look(MUTED, MuteCopy.pillForever(w), w.muted, true)
            else Look(MUTED, MuteCopy.pill(MuteCopy.leftMin(s.until, now), w), w.muted, true)
        }
    }

    /** [Look.kind] of the Mute all pill (neutral colour, the "notifications off" bell). */
    const val MUTED = "muted"

    /** The action pill on an ended sensor (round sensor-life): the same pill, kind "sensor" (neutral colour, sensor disc). */
    fun replaceSensor(w: Words = Words.current) = Look("sensor", w.replaceSensor, w.replaceSensorShort, false)

    /** The pill over a scrolled chart (GlucoseGraph, [ChartView.now]): the same pill, kind "now" (neutral colour, arrow). */
    fun backToNow(w: Words = Words.current) = Look("now", w.backToNow, w.backToNow, false)

    /** The pill's "45m left" / "queda 1h" (plural badge_time_left) and its short form "45m", for an end [until]. */
    private fun left(until: Long, now: Long, w: Words): Pair<String, String> {
        val short = TreatmentMarks.duration(until - now, up = true)
        return w.timeLeft.format(Durations.compactQuantity(MuteCopy.leftMin(until, now)), short) to short
    }

    /**
     * For screen readers (widget descriptions, the app's card): the mute lines the app shows (alerts/MuteCopy, the
     * Mac's VoiceOver says the same): "Low alerts muted after carbs · 12 min left", "Low alerts muted · 1 h left",
     * "Alerts muted · 1 h 12 min left", "Alerts muted until you turn it off" (only Mute all has no end).
     */
    fun describe(ctx: Context, s: AlertStatus, now: Long): String? = when (s) {
        AlertStatus.None -> null
        is AlertStatus.Needed -> look(s, now)?.text
        is AlertStatus.Taken -> MuteCopy.sideAfter(ctx, s.kind, s.treatment.k, MuteCopy.leftMin(s.until, now))
        is AlertStatus.Paused -> if (s.until >= AlertEngine.NEVER) MuteCopy.allForever(ctx) else MuteCopy.sideMutedUntil(ctx, s.kind, s.until, now)
        is AlertStatus.Muted -> MuteCopy.all(ctx, s.until, now)
    }

    /** Material Symbols "notifications_paused" (Apache 2.0), viewBox 0 -960 960 960; also `ic_sym_notifications_paused`. */
    private const val BELL_PAUSED = "M370-320h220v-72H480l110-136v-72H370v72h110L370-392v72ZM160-200v-80h80v-280q0-83 50-147.5" +
        "T420-792v-28q0-25 17.5-42.5T480-880q25 0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v280h80v80H160Zm320-300Zm0 420q-33 " +
        "0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80ZM320-280h320v-280q0-66-47-113t-113-47q-66 0-113 47t-47 113v280Z"
    private val bellPath: Path by lazy { PathParser.createPathFromPathData(BELL_PAUSED) }
    /** Material Symbols "notifications_off" (Apache 2.0), viewBox 0 -960 960 960; also `ic_sym_notifications_off`. */
    private const val BELL_OFF = "M160-200v-80h80v-280q0-33 8.5-65t25.5-61l60 60q-7 16-10.5 32.5T320-560v280h248L56-792l56-56 736 736-56 " +
        "56-146-144H160Zm560-154-80-80v-126q0-66-47-113t-113-47q-26 0-50 8t-44 24l-58-58q20-16 43-28t49-18v-28q0-25 17.5-42.5T480-880q25 " +
        "0 42.5 17.5T540-820v28q80 20 130 84.5T720-560v206Zm-276-50Zm36 324q-33 0-56.5-23.5T400-160h160q0 33-23.5 56.5T480-80Zm33-481Z"
    private val bellOffPath: Path by lazy { PathParser.createPathFromPathData(BELL_OFF) }

    /** The bell, [size] px square, top-left at ([x], [y]); [off] = "notifications off" (Mute all) instead of "paused". */
    fun bell(cv: Canvas, x: Float, y: Float, size: Float, color: Int, off: Boolean = false) {
        val p = Path(if (off) bellOffPath else bellPath)
        p.transform(Matrix().apply { setTranslate(0f, 960f); postScale(size / 960f, size / 960f); postTranslate(x, y) })
        cv.drawPath(p, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    private fun paints(look: Look, h: Float, dark: Boolean): Triple<Int, Int, Paint> {
        val c = TreatmentMarks.color(TreatmentMarks.palette(dark), look.kind)
        val bg = if (look.tonal) ColorUtils.setAlphaComponent(c, if (dark) 0x3D else 0x2E) else c
        val fg = if (look.tonal) c else TreatmentMarks.onColor(dark)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fg; textSize = h * 0.56f; typeface = Typeface.create(Typeface.DEFAULT, 600, false)
        }
        return Triple(bg, fg, text)
    }

    /** Width of the full badge [h] px tall; [short] = the shorter words. */
    fun width(look: Look, h: Float, dark: Boolean, short: Boolean = false): Float =
        h * 0.4f + h * 0.62f + h * 0.25f + paints(look, h, dark).third.measureText(if (short) look.short else look.text) + h * 0.45f

    /**
     * Draw the badge [h] px tall with its top-left at ([x], [y]), at most [maxW] wide: the full words, the short ones,
     * or (no room for words) only the icon in a circle. Returns the width used, 0 if not even the circle fits.
     */
    fun draw(cv: Canvas, x: Float, y: Float, h: Float, maxW: Float, look: Look, dark: Boolean): Float {
        val (bg, fg, text) = paints(look, h, dark)
        val words = when {
            width(look, h, dark) <= maxW -> look.text
            width(look, h, dark, short = true) <= maxW -> look.short
            h <= maxW -> null
            else -> return 0f
        }
        val icon = h * 0.62f
        val w = if (words == null) h else h * 0.4f + icon + h * 0.25f + text.measureText(words) + h * 0.45f
        cv.drawRoundRect(RectF(x, y, x + w, y + h), h / 2, h / 2, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        val ix = if (words == null) x + (h - icon) / 2 else x + h * 0.4f
        if (look.tonal) bell(cv, ix, y + (h - icon) / 2, icon, fg, off = look.kind == MUTED)
        else TreatmentMarks.marker(cv, look.kind, ix + icon / 2, y + h / 2, icon * 0.42f, fg)
        if (words != null) {
            val fm = text.fontMetrics
            cv.drawText(words, ix + icon + h * 0.25f, y + h / 2 - (fm.ascent + fm.descent) / 2, text)
        }
        return w
    }
}

/**
 * Where the last-treatment slot sits among the slots beside the value (2026-10-18 round): one more slot, not a slot
 * kind, so the user's rings stay as they are. [NEAR_VALUE] = leftmost, next to the number; [END] = rightmost.
 */
enum class LastPlace { OFF, NEAR_VALUE, END }

/** One slot beside the value, ready to draw. */
sealed interface SlotItem {
    val emphasis: Float
    data class Metric(val spec: RingSpec, val m: GlucoseMetrics, override val emphasis: Float) : SlotItem
    data class Last(val info: LastTreatment.Info) : SlotItem { override val emphasis = 1f }
}

object Slots {
    private val RING_EMPHASIS = floatArrayOf(1f, 0.55f, 0.35f)
    /** The fallback ring (no recent treatment) when it is faded: like a second slot. */
    private const val FALLBACK_EMPHASIS = 0.55f

    /**
     * What to draw, right to left (main first), at most [max] slots. [rings] = the user's slots (main first) with
     * their metrics, already without the ones lacking data. When there's no room for everything, the main ring stays
     * first, then a recent treatment, then the second ring, then the fallback ring, then the third ring; so on a small
     * widget the treatment pushes the second ring out, and the fallback only fills room the rings leave.
     */
    fun arrange(rings: List<Triple<Int, RingSpec, GlucoseMetrics>>, place: LastPlace, last: LastTreatment.Info?,
                fallback: Pair<RingSpec, GlucoseMetrics>?, max: Int): List<SlotItem> {
        val metric = rings.map { (i, s, m) -> i to SlotItem.Metric(s, m, if (s.faded) RING_EMPHASIS[i.coerceIn(0, 2)] else 1f) }
        val extra: SlotItem? = when {
            place == LastPlace.OFF -> null
            last != null -> SlotItem.Last(last)
            else -> fallback?.let { (s, m) -> SlotItem.Metric(s, m, if (s.faded) FALLBACK_EMPHASIS else 1f) }
        }
        val priority = buildList {
            metric.firstOrNull { it.first == 0 }?.let { add(it.second) }
            if (extra is SlotItem.Last) add(extra)
            metric.firstOrNull { it.first == 1 }?.let { add(it.second) }
            if (extra is SlotItem.Metric) add(extra)
            metric.filter { it.first >= 2 }.forEach { add(it.second) }
        }
        val keep = priority.take(max.coerceAtLeast(0)).toSet()
        val ordered = metric.map { it.second }.filter { it in keep }
        return when {
            extra == null || extra !in keep -> ordered
            place == LastPlace.END -> listOf(extra) + ordered
            else -> ordered + extra
        }
    }

    /**
     * Which of [items] (as [arrange] returns them) is the main ring, the one the sensor countdown ring
     * ([SensorBadge.drawCountdown]) covers: the first ring, else the only slot there is; -1 = none.
     */
    fun mainIndex(items: List<SlotItem>): Int = items.indexOfFirst { it is SlotItem.Metric }.takeIf { it >= 0 } ?: if (items.isEmpty()) -1 else 0

    private fun mark(item: SlotItem) =
        if (item is SlotItem.Metric && item.spec.kind == SlotKind.TIR) TirRing.mark(item.m.tir, item.spec) else TirMark.NONE
    /**
     * What a slot shows, for the layout report ([CardLayout]): kind, window, range, the number in it, its mark, and
     * whether it is the main one (and carries the sensor countdown ring). The same words on the Mac.
     */
    fun describe(item: SlotItem, main: Boolean, countdown: Double?): String = buildString {
        when (item) {
            is SlotItem.Metric -> {
                append(item.spec.kind.name).append(' ').append(item.spec.windowS)
                if (item.spec.kind == SlotKind.TIR) append(' ').append(item.spec.range.name)
                append(' ').append(if (item.m.empty) "-" else if (item.spec.kind == SlotKind.TIR) TirRing.text(item.m.tir, item.spec)
                    else MetricGauge.text(item.spec.kind, item.m).first)
                mark(item).takeIf { it != TirMark.NONE }?.let { append(' ').append(it.name.lowercase()) }
            }
            is SlotItem.Last -> {
                append("LAST ").append(item.info.tr.k)
                item.info.carbs?.let { append('+').append(it.tr.k) }
            }
        }
        if (main) append(" [main]")
        if (countdown != null) append(" [countdown]")
    }

    /**
     * A slot as words for a place that shows text only (the Live Update card's metrics): the value and a short label
     * ("86%" · "in range 1d", "4.5u" · "12m ago"); null = nothing to show.
     */
    fun text(item: SlotItem, now: Long): Pair<String, String>? = when (item) {
        is SlotItem.Metric -> if (item.m.empty) null else when (item.spec.kind) {
            SlotKind.TIR -> "${TirRing.text(item.m.tir, item.spec)}%" to
                "${Words.current.let { if (item.spec.range == TirRange.TIGHT) it.tirVeryGood else it.tirInRange }} ${item.spec.period}"
            else -> MetricGauge.text(item.spec.kind, item.m).let { (v, u) -> "$v$u" } to "${item.spec.kind.short} ${item.spec.period}"
        }
        is SlotItem.Last -> (TreatmentMarks.label(item.info.tr, 8) ?: item.info.tr.k) to
            Words.current.ago.format(TreatmentMarks.duration(now - item.info.tr.t))
    }

    /** Whether [item] is a ring that wears a laurel (any [TirMark]). */
    fun hasLaurel(item: SlotItem) = mark(item) != TirMark.NONE

    /**
     * Extra room between [items]`[i]` and the next one to its left when either wears a laurel ([r] = a slot's radius):
     * 0.22 r, so the gap is 0.5 r. One laurel's branch then clears the neighbour; two laurels' facing stems (on circles
     * of 1.25 r) touch in the middle of the gap, where the wreaths meet.
     */
    fun extraGap(items: List<SlotItem>, i: Int, r: Float): Float =
        if (i + 1 < items.size && (hasLaurel(items[i]) || hasLaurel(items[i + 1]))) r * 0.22f else 0f

    /** Room kept at the card's edge (right of the first item) when it wears a laurel: 0.3 r, so its branch stays mostly on the card. */
    fun edgeInset(items: List<SlotItem>, r: Float) = if (items.isNotEmpty() && hasLaurel(items[0])) r * 0.3f else 0f

    /** The slots' centres, right to left from [right] (the card's edge), [edgeInset] in, [gap] + [extraGap] apart. */
    fun centers(items: List<SlotItem>, right: Float, size: Float, gap: Float): List<Float> {
        val out = ArrayList<Float>(items.size)
        var x = right - edgeInset(items, size / 2) - size / 2
        for (i in items.indices) { out += x; x -= size + gap + extraGap(items, i, size / 2) }
        return out
    }

    /**
     * Room kept left of the leftmost item (the last one) when it wears a laurel: its branch reaches about half a radius
     * past the ring, so the value's words beside it don't run into the leaves (the Mac's "No reading since" did).
     */
    fun leftInset(items: List<SlotItem>, r: Float) = if (items.isNotEmpty() && hasLaurel(items.last())) r * 0.3f else 0f

    /** All the [extraGap]s of [items] and the [edgeInset] together. */
    fun extraWidth(items: List<SlotItem>, r: Float) = items.indices.sumOf { extraGap(items, it, r).toDouble() }.toFloat() + edgeInset(items, r)

    /**
     * What goes under all the slots, before any [draw]: the rings' laurels. [cx] = each item's centre (same order).
     * Between two laurelled rings the facing sides meet ([Laurel.Side.MERGED]); every other side is the whole branch,
     * which the neighbour covers where it still reaches it.
     */
    fun drawUnder(cv: Canvas, items: List<SlotItem>, cx: List<Float>, cy: Float, radius: Float, pal: GraphPalette) {
        val colors = items.map { item ->
            if (hasLaurel(item)) ColorUtils.setAlphaComponent(pal.tight, (255 * (item as SlotItem.Metric).emphasis).toInt()) else null
        }
        val order = items.indices.sortedBy { cx[it] }   // left to right
        fun side(n: Int?) = if (n != null && colors[n] != null) Laurel.Side.MERGED else Laurel.Side.FULL
        for ((p, i) in order.withIndex()) {
            val c = colors[i] ?: continue
            val l = order.getOrNull(p - 1); val r = order.getOrNull(p + 1)
            Laurel.draw(cv, cx[i], cy, radius, c, side(l), side(r))
        }
        // The flowers: a tip flower on each side, except where two flowered laurels meet: one shared piece there, the
        // flower of the ring that isn't faded, or of the higher number when both are faded or neither is.
        val blooms = items.map { item -> (item as? SlotItem.Metric)?.takeIf { it.spec.kind == SlotKind.TIR && it.spec.marks }?.let { Bloom.of(it.m.tir, it.spec.range) } }
        fun alpha(i: Int) = (255 * (items[i] as SlotItem.Metric).emphasis).toInt()
        for ((p, i) in order.withIndex()) {
            val b = blooms[i] ?: continue
            val l = order.getOrNull(p - 1); val r = order.getOrNull(p + 1)
            if (l == null || blooms[l] == null) Blooms.tip(cv, b, cx[i], cy, radius, true, pal, alpha(i))
            if (r == null || blooms[r] == null) Blooms.tip(cv, b, cx[i], cy, radius, false, pal, alpha(i))
            else Blooms.center(cv, sharedBloom(items, blooms, i, r), cx[i], cx[r], cy, radius, pal, alpha(i), alpha(r))
        }
    }

    /** Which of two meeting rings' [Bloom]s the shared piece uses: the unfaded ring's, else the higher number's. */
    private fun sharedBloom(items: List<SlotItem>, blooms: List<Bloom?>, a: Int, b: Int): Bloom {
        val ma = items[a] as SlotItem.Metric; val mb = items[b] as SlotItem.Metric
        val first = when {
            ma.emphasis != mb.emphasis -> if (ma.emphasis > mb.emphasis) a else b
            else -> if (ma.m.tir.of(ma.spec.range) >= mb.m.tir.of(mb.spec.range)) a else b
        }
        return blooms[first]!!
    }

    /**
     * Draw [item] as a circle of [radius] around ([cx], [cy]), with the sensor [countdown] ring over it (the main slot's;
     * a time-in-range ring draws it under its crown).
     */
    fun draw(cv: Canvas, item: SlotItem, cx: Float, cy: Float, radius: Float, nowS: Long, pal: GraphPalette, d: Float,
             countdown: Double? = null) {
        if (item is SlotItem.Metric && item.spec.kind == SlotKind.TIR) {
            TirRing.draw(cv, cx, cy, radius, item.m.tir, item.spec, pal, item.emphasis, d, countdown)
            return
        }
        when (item) {
            is SlotItem.Metric -> MetricGauge.draw(cv, cx, cy, radius, item.m, item.spec, pal, item.emphasis, d)
            is SlotItem.Last -> LastSlot.draw(cv, cx, cy, radius, item.info, nowS, pal, d)
        }
        SensorBadge.drawCountdown(cv, cx, cy, radius, countdown, pal)
    }
}

/**
 * The last-treatment slot: the kind's marker, the amount ("4.5u", "30g") in the kind's colour and the time since it
 * ("12m"); around it a ring in the kind's colour for the share of the alert mute still left (a determinate circular
 * indicator), shrinking back to 12 o'clock. When the mute is over (or the kind mutes nothing) only the grey track is
 * left; [LastTreatment.keepS] later the slot gives way to its fallback.
 */
object LastSlot {
    /** Smallest slot radius (dp) that draws insulin + carbs concentric; smaller slots show the insulin alone. */
    const val PAIR_MIN_DP = 20f

    fun draw(cv: Canvas, cx: Float, cy: Float, radius: Float, info: LastTreatment.Info, nowS: Long, pal: GraphPalette, d: Float) {
        info.carbs?.takeIf { radius >= PAIR_MIN_DP * d }?.let { return drawPair(cv, cx, cy, radius, info, it, nowS, pal) }
        val sw = radius * 0.2f
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = sw; strokeCap = Paint.Cap.ROUND }
        ring.color = pal.grid
        cv.drawOval(oval, ring)
        val c = TreatmentMarks.color(TreatmentMarks.palette(pal.dark), info.tr.k)
        val left = info.left(nowS)
        if (left > 0f) {
            ring.color = c
            cv.drawArc(oval, -90f, (360f * left).coerceAtLeast(2f), false, ring)
        }
        TreatmentMarks.marker(cv, info.tr.k, cx, cy - radius * 0.4f, radius * 0.14f, c, pal.bg.takeIf { it != 0 })
        val inner = (radius - sw) * 2 * 0.86f
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = c; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 800, false)
            textSize = radius * 0.5f
        }
        val s = TreatmentMarks.amount(info.tr) ?: ""
        num.textSize = min(num.textSize, num.textSize * inner / num.measureText(s).coerceAtLeast(1f))
        cv.drawText(s, cx, cy + radius * 0.17f, num)
        val sub = Paint(num).apply {
            textSize = radius * 0.3f; typeface = Typeface.create(Typeface.DEFAULT, 600, false); color = pal.inkSoft
        }
        cv.drawText(TreatmentMarks.duration(nowS - info.tr.t), cx, cy + radius * 0.17f + sub.textSize * 1.25f, sub)
    }

    /**
     * Insulin with carbs logged while it works (the user, 2026-10-21): two concentric rings, the outer one the insulin's
     * mute left, the inner one the carbs' (both the slot's ring style, thinner), and the two amounts stacked inside in
     * their colours ("4.5u" over "45g"); no markers or time (the colours and the units say which is which). The Mac
     * draws the same (SukraraGraph.swift `LastSlot.drawPair`).
     */
    private fun drawPair(cv: Canvas, cx: Float, cy: Float, radius: Float, ins: LastTreatment.Info, carbs: LastTreatment.Info,
                         nowS: Long, pal: GraphPalette) {
        val tp = TreatmentMarks.palette(pal.dark)
        val outerSw = radius * 0.16f
        val innerSw = radius * 0.13f
        val outerR = radius - outerSw / 2
        val innerR = radius - outerSw - radius * 0.05f - innerSw / 2
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        for ((info, r, sw) in listOf(Triple(ins, outerR, outerSw), Triple(carbs, innerR, innerSw))) {
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            ring.strokeWidth = sw; ring.color = pal.grid
            cv.drawOval(oval, ring)
            val left = info.left(nowS)
            if (left > 0f) {
                ring.color = TreatmentMarks.color(tp, info.tr.k)
                cv.drawArc(oval, -90f, (360f * left).coerceAtLeast(2f), false, ring)
            }
        }
        // the two amounts in the inner circle, one size so they read as a pair
        val room = (innerR - innerSw / 2) * 2 * 0.84f
        val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, 800, false) }
        num.textSize = radius * 0.36f
        val a = TreatmentMarks.amount(ins.tr) ?: ""
        val b = TreatmentMarks.amount(carbs.tr) ?: ""
        val widest = maxOf(num.measureText(a), num.measureText(b)).coerceAtLeast(1f)
        num.textSize = min(num.textSize, num.textSize * room / widest)
        val cap = num.textSize * 0.72f
        val gap = num.textSize * 0.28f
        num.color = TreatmentMarks.color(tp, ins.tr.k)
        cv.drawText(a, cx, cy - gap / 2, num)
        num.color = TreatmentMarks.color(tp, carbs.tr.k)
        cv.drawText(b, cx, cy + gap / 2 + cap, num)
    }
}

/**
 * The slots beside the value with the app's own settings (rings, last-treatment slot and its fallback) and the
 * role's stored metrics, for surfaces that lay the slots out themselves (the lock screen widget around the lock icon,
 * the notification): the same choice as [GlucoseCard]'s header, at most [max] slots, right to left (main first).
 */
fun appSlots(c: android.content.Context, readings: List<app.mauri.sukrara.core.Reading>,
             treatments: List<app.mauri.sukrara.treatments.Treatment>, nowS: Long, max: Int): List<SlotItem> {
    val cfg = GraphSettings.value
    val bands = cfg.bands.sane()
    val alerts = app.mauri.sukrara.alerts.Alerts.settings.value
    val last = if (cfg.lastPlace == LastPlace.OFF) null
        else LastTreatment.of(treatments, alerts, AlertStatus.of(readings.maxByOrNull { it.t }, alerts, treatments, nowS,
            app.mauri.sukrara.alerts.Alerts.paused.value, app.mauri.sukrara.alerts.Alerts.allMuted.value), nowS)
    fun metrics(s: RingSpec) = GraphData.metrics(c, s.windowS, bands, nowS).takeIf { it.tir.coverage >= GlucoseCard.MIN_COVERAGE }
    val rings = cfg.rings().take(3).mapIndexedNotNull { i, s -> metrics(s)?.let { Triple(i, s, it) } }
    val fallback = if (cfg.lastPlace != LastPlace.OFF && last == null) cfg.lastElseRing()?.let { s -> metrics(s)?.let { s to it } } else null
    return Slots.arrange(rings, cfg.lastPlace, last, fallback, max)
}
