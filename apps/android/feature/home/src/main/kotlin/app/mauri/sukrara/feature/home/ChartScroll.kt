// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.mauri.sukrara.graph.ChartView
import kotlin.math.abs

/**
 * Where home's chart looks: the end of its window ([endS], epoch s; null = following now) and its length while zoomed
 * ([spanS], s; null = the place's *Time shown*). Exact values are kept as doubles so sub-second drag steps add up.
 * Hoisted state: the gestures, the TalkBack actions and the tests drive the same object.
 */
@Stable
class ChartScroll(
    endS: Long? = null,
    spanS: Long? = null,
) {
    var endS by mutableStateOf(endS)
        private set
    var spanS by mutableStateOf(spanS)
        private set
    private var exactEnd: Double? = endS?.toDouble()
    private var exactSpan: Double? = spanS?.toDouble()

    /** Scrolled or zoomed: the chart doesn't show the usual window up to now (the *Now* pill shows). */
    val away: Boolean get() = endS != null || spanS != null

    /** The window's length now; [default] = the place's *Time shown*. */
    fun span(default: Long): Long = spanS ?: default

    /**
     * Moves the end by [dt] s, kept within [minEnd]..[now] (at [now] the chart follows now again); returns the seconds
     * actually moved (less at an edge: the rest is overscroll).
     */
    fun by(
        dt: Double,
        minEnd: Long,
        now: Long,
    ): Double {
        val cur = exactEnd ?: now.toDouble()
        val next = (cur + dt).coerceIn(minOf(minEnd, now).toDouble(), now.toDouble())
        exactEnd = if (next >= now) null else next
        endS = exactEnd?.toLong()
        return next - cur
    }

    /**
     * Zooms the time axis by [factor] (> 1 = in) keeping the moment at [anchor] (0 = the plot's left edge, 1 = its right
     * edge) in place. The length stays within [ChartView.MIN_SPAN_S]..[ChartView.MAX_SPAN_S] and the history ([first] =
     * the oldest reading), never narrower or wider than [default] needs; a window that reaches now keeps following now.
     */
    fun zoom(
        factor: Double,
        anchor: Double,
        default: Long,
        first: Long,
        now: Long,
    ) {
        if (factor <= 0.0 || factor.isNaN() || factor.isInfinite()) return
        val (lo, hi) = spanLimits(default, first, now)
        val span0 = exactSpan ?: default.toDouble()
        val span1 = (span0 / factor).coerceIn(lo, hi)
        val end0 = exactEnd ?: now.toDouble()
        val a = anchor.coerceIn(0.0, 1.0)
        val at = end0 - span0 * (1 - a)
        val end1 = (at + span1 * (1 - a)).coerceIn(minOf(first + span1, now.toDouble()), now.toDouble())
        set(end1, span1, default, now)
    }

    /** One zoom step for TalkBack and keyboard: twice as long ([out]) or half as long, anchored at the window's end. */
    fun step(
        out: Boolean,
        default: Long,
        first: Long,
        now: Long,
    ) = zoom(if (out) 0.5 else 2.0, 1.0, default, first, now)

    /** Puts the window at [end] with length [span] (the way back to now animates through this). */
    fun set(
        end: Double,
        span: Double,
        default: Long,
        now: Long,
    ) {
        exactSpan = if (abs(span - default) < SAME_SPAN_S) null else span
        spanS = exactSpan?.toLong()
        exactEnd = if (end >= now) null else end
        endS = exactEnd?.toLong()
    }

    companion object {
        /** A length within this of the place's own counts as not zoomed (the pill goes away). */
        private const val SAME_SPAN_S = 30.0

        /** The shortest and longest window for [default] with history from [first] at [now] (s). */
        fun spanLimits(
            default: Long,
            first: Long,
            now: Long,
        ): Pair<Double, Double> {
            val lo = minOf(ChartView.MIN_SPAN_S, default).toDouble()
            val hi = maxOf(default, minOf(ChartView.MAX_SPAN_S, now - first)).toDouble()
            return lo to hi
        }
    }
}
