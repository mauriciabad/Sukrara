// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.ColorUtils
import app.mauri.sukrara.graph.stats.GlucoseMetrics
import app.mauri.sukrara.graph.stats.TirRange
import kotlin.math.min

/** One slot beside the value, ready to draw. */
sealed interface SlotItem {
    val emphasis: Float

    data class Metric(
        val spec: RingSpec,
        val m: GlucoseMetrics,
        override val emphasis: Float,
    ) : SlotItem

    data class Last(
        val info: LastTreatment.Info,
    ) : SlotItem {
        override val emphasis = 1f
    }
}

/** The slots beside the value: which show (priority when room is short), where, and how they are drawn. */
object Slots {
    private val RING_EMPHASIS = floatArrayOf(1f, 0.55f, 0.35f)
    private const val FALLBACK_EMPHASIS = 0.55f

    /**
     * What to draw, right to left (main first), at most [max] slots. [rings] = the user's slots (index, spec, metrics)
     * with data. Priority: main ring, a recent treatment, second ring, the fallback ring, third ring.
     */
    internal fun arrange(
        rings: List<Triple<Int, RingSpec, GlucoseMetrics>>,
        place: LastPlace,
        last: LastTreatment.Info?,
        fallback: Pair<RingSpec, GlucoseMetrics>?,
        max: Int,
    ): List<SlotItem> {
        val metric = rings.map { (i, s, m) -> i to SlotItem.Metric(s, m, if (s.faded) RING_EMPHASIS[i.coerceIn(0, 2)] else 1f) }
        val extra: SlotItem? =
            when {
                place == LastPlace.OFF -> null
                last != null -> SlotItem.Last(last)
                else -> fallback?.let { (s, m) -> SlotItem.Metric(s, m, if (s.faded) FALLBACK_EMPHASIS else 1f) }
            }
        val priority =
            buildList {
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

    /** The main ring (the one the sensor countdown covers): the first ring, else the only slot; -1 = none. */
    internal fun mainIndex(items: List<SlotItem>): Int =
        items.indexOfFirst { it is SlotItem.Metric }.takeIf { it >= 0 } ?: if (items.isEmpty()) -1 else 0

    private fun mark(item: SlotItem) =
        if (item is SlotItem.Metric && item.spec.kind == SlotKind.TIR) TirRing.mark(item.m.tir, item.spec) else TirMark.NONE

    /** What a slot shows, for the layout report: kind, window, range, number, mark, main / countdown. Same on the Mac. */
    internal fun describe(
        item: SlotItem,
        main: Boolean,
        countdown: Double?,
    ): String =
        buildString {
            when (item) {
                is SlotItem.Metric -> {
                    append(item.spec.kind.name).append(' ').append(item.spec.windowS)
                    if (item.spec.kind == SlotKind.TIR) append(' ').append(item.spec.range.name)
                    append(' ')
                    append(
                        when {
                            item.m.empty -> "-"
                            item.spec.kind == SlotKind.TIR -> TirRing.text(item.m.tir, item.spec, java.util.Locale.ROOT)
                            else -> MetricGauge.text(item.spec.kind, item.m).first
                        },
                    )
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

    /** A slot as words for a text-only place (value, short label): "86%" · "in range 1d", "4.5u" · "12m ago". */
    fun text(
        item: SlotItem,
        now: Long,
        w: CardWords,
    ): Pair<String, String>? =
        when (item) {
            is SlotItem.Metric -> {
                if (item.m.empty) {
                    null
                } else if (item.spec.kind == SlotKind.TIR) {
                    "${TirRing.text(item.m.tir, item.spec)}%" to
                        "${if (item.spec.range == TirRange.TIGHT) w.tirVeryGood else w.tirInRange} ${w.period(item.spec.windowS)}"
                } else {
                    MetricGauge.text(item.spec.kind, item.m).let { (v, u) -> "$v$u" } to
                        "${MetricGauge.short(item.spec.kind, w)} ${w.period(item.spec.windowS)}"
                }
            }

            is SlotItem.Last -> {
                (TreatmentMarks.label(item.info.tr, 8) ?: item.info.tr.k) to w.ago(w.shortDuration(now - item.info.tr.t))
            }
        }

    internal fun hasLaurel(item: SlotItem) = mark(item) != TirMark.NONE

    /** Extra room after [items]`[i]` when it or its left neighbour wears a laurel (0.22 r): the wreaths meet in the gap. */
    internal fun extraGap(
        items: List<SlotItem>,
        i: Int,
        r: Float,
    ): Float = if (i + 1 < items.size && (hasLaurel(items[i]) || hasLaurel(items[i + 1]))) r * 0.22f else 0f

    /** Room at the card's edge when the rightmost item wears a laurel. */
    private fun edgeInset(
        items: List<SlotItem>,
        r: Float,
    ) = if (items.isNotEmpty() && hasLaurel(items[0])) r * 0.3f else 0f

    /** The slots' centres, right to left from [right]. */
    internal fun centers(
        items: List<SlotItem>,
        right: Float,
        size: Float,
        gap: Float,
    ): List<Float> {
        val out = ArrayList<Float>(items.size)
        var x = right - edgeInset(items, size / 2) - size / 2
        for (i in items.indices) {
            out += x
            x -= size + gap + extraGap(items, i, size / 2)
        }
        return out
    }

    /** Room left of the leftmost item when it wears a laurel, so the value's words don't run into its leaves. */
    internal fun leftInset(
        items: List<SlotItem>,
        r: Float,
    ) = if (items.isNotEmpty() && hasLaurel(items.last())) r * 0.3f else 0f

    internal fun extraWidth(
        items: List<SlotItem>,
        r: Float,
    ) = items.indices.sumOf { extraGap(items, it, r).toDouble() }.toFloat() + edgeInset(items, r)

    /** The laurels and flowers under all the slots, before any [draw] (neighbours cover what reaches them). */
    internal fun drawUnder(
        cv: Canvas,
        items: List<SlotItem>,
        cx: List<Float>,
        cy: Float,
        radius: Float,
        pal: GraphPalette,
    ) {
        val colors = items.map { if (hasLaurel(it)) ColorUtils.setAlphaComponent(pal.tight, (255 * it.emphasis).toInt()) else null }
        val order = items.indices.sortedBy { cx[it] } // left to right

        fun side(n: Int?) = if (n != null && colors[n] != null) Laurel.Side.MERGED else Laurel.Side.FULL
        for ((p, i) in order.withIndex()) {
            val c = colors[i] ?: continue
            Laurel.draw(cv, cx[i], cy, radius, c, side(order.getOrNull(p - 1)), side(order.getOrNull(p + 1)))
        }
        val blooms =
            items.map { item ->
                (item as? SlotItem.Metric)?.takeIf { it.spec.kind == SlotKind.TIR && it.spec.marks }?.let {
                    Bloom.of(
                        it.m.tir,
                        it.spec.range,
                    )
                }
            }

        fun alpha(i: Int) = (255 * items[i].emphasis).toInt()
        for ((p, i) in order.withIndex()) {
            val b = blooms[i] ?: continue
            val l = order.getOrNull(p - 1)
            val r = order.getOrNull(p + 1)
            if (l == null || blooms[l] == null) Blooms.tip(cv, b, cx[i], cy, radius, true, pal, alpha(i))
            if (r == null || blooms[r] == null) {
                Blooms.tip(cv, b, cx[i], cy, radius, false, pal, alpha(i))
            } else {
                Blooms.center(cv, sharedBloom(items, blooms, i, r), cx[i], cx[r], cy, radius, pal, alpha(i), alpha(r))
            }
        }
    }

    /** Which of two meeting rings' [Bloom]s the shared piece uses: the unfaded ring's, else the higher number's. */
    private fun sharedBloom(
        items: List<SlotItem>,
        blooms: List<Bloom?>,
        a: Int,
        b: Int,
    ): Bloom {
        val ma = items[a] as SlotItem.Metric
        val mb = items[b] as SlotItem.Metric
        val first =
            when {
                ma.emphasis != mb.emphasis -> if (ma.emphasis > mb.emphasis) a else b
                else -> if (ma.m.tir.of(ma.spec.range) >= mb.m.tir.of(mb.spec.range)) a else b
            }
        return checkNotNull(blooms[first])
    }

    /** Draw [item] as a circle of [radius] around ([cx], [cy]) with the sensor [countdown] ring over it. */
    internal fun draw(
        cv: Canvas,
        item: SlotItem,
        cx: Float,
        cy: Float,
        radius: Float,
        nowS: Long,
        pal: GraphPalette,
        d: Float,
        w: CardWords,
        countdown: Double?,
    ) {
        when {
            item is SlotItem.Metric && item.spec.kind == SlotKind.TIR -> {
                return TirRing.draw(cv, cx, cy, radius, item.m.tir, item.spec, pal, item.emphasis, w, countdown)
            }

            item is SlotItem.Metric -> {
                MetricGauge.draw(cv, cx, cy, radius, item.m, item.spec, pal, item.emphasis, d, w)
            }

            item is SlotItem.Last -> {
                LastSlot.draw(cv, cx, cy, radius, item.info, nowS, pal, d, w)
            }
        }
        SensorBadge.drawCountdown(cv, cx, cy, radius, countdown, pal)
    }
}

/**
 * The last-treatment slot: the kind's marker, the amount in its colour and the time since it; around it a ring for
 * the share of the alert mute still left. Insulin with carbs riding along: two concentric rings and both amounts.
 */
internal object LastSlot {
    /** Smallest slot radius (dp) that draws insulin + carbs concentric. */
    const val PAIR_MIN_DP = 20f

    private fun bold(w: Int) = Typeface.create(Typeface.DEFAULT, w, false)

    fun draw(
        cv: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        info: LastTreatment.Info,
        nowS: Long,
        pal: GraphPalette,
        d: Float,
        w: CardWords,
    ) {
        info.carbs?.takeIf { radius >= PAIR_MIN_DP * d }?.let { return drawPair(cv, cx, cy, radius, info, it, nowS, pal) }
        val sw = radius * 0.2f
        val oval = RectF(cx - radius + sw / 2, cy - radius + sw / 2, cx + radius - sw / 2, cy + radius - sw / 2)
        val ring =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = sw
                strokeCap = Paint.Cap.ROUND
                color = pal.grid
            }
        cv.drawOval(oval, ring)
        val c = TreatmentMarks.color(TreatmentMarks.palette(pal.dark), info.tr.k)
        val left = info.left(nowS)
        if (left > 0f) {
            ring.color = c
            cv.drawArc(oval, -90f, (360f * left).coerceAtLeast(2f), false, ring)
        }
        TreatmentMarks.marker(cv, info.tr.k, cx, cy - radius * 0.4f, radius * 0.14f, c, pal.bg.takeIf { it != 0 })
        val inner = (radius - sw) * 2 * 0.86f
        val num =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = c
                textAlign = Paint.Align.CENTER
                typeface = bold(800)
                textSize = radius * 0.5f
            }
        val s = TreatmentMarks.amount(info.tr).orEmpty()
        num.textSize = min(num.textSize, num.textSize * inner / num.measureText(s).coerceAtLeast(1f))
        cv.drawText(s, cx, cy + radius * 0.17f, num)
        val sub =
            Paint(num).apply {
                textSize = radius * 0.3f
                typeface = bold(600)
                color = pal.inkSoft
            }
        cv.drawText(w.shortDuration(nowS - info.tr.t), cx, cy + radius * 0.17f + sub.textSize * 1.25f, sub)
    }

    private fun drawPair(
        cv: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        ins: LastTreatment.Info,
        carbs: LastTreatment.Info,
        nowS: Long,
        pal: GraphPalette,
    ) {
        val tp = TreatmentMarks.palette(pal.dark)
        val outerSw = radius * 0.16f
        val innerSw = radius * 0.13f
        val outerR = radius - outerSw / 2
        val innerR = radius - outerSw - radius * 0.05f - innerSw / 2
        val ring =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        for ((info, r, sw) in listOf(Triple(ins, outerR, outerSw), Triple(carbs, innerR, innerSw))) {
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            ring.strokeWidth = sw
            ring.color = pal.grid
            cv.drawOval(oval, ring)
            val left = info.left(nowS)
            if (left > 0f) {
                ring.color = TreatmentMarks.color(tp, info.tr.k)
                cv.drawArc(oval, -90f, (360f * left).coerceAtLeast(2f), false, ring)
            }
        }
        // the two amounts in the inner circle, one size so they read as a pair
        val room = (innerR - innerSw / 2) * 2 * 0.84f
        val num =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = bold(800)
                textSize = radius * 0.36f
            }
        val a = TreatmentMarks.amount(ins.tr).orEmpty()
        val b = TreatmentMarks.amount(carbs.tr).orEmpty()
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
