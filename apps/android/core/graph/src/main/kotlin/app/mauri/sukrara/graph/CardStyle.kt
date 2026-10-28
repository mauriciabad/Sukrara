// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.graph.stats.Bands
import app.mauri.sukrara.graph.stats.TirRange

/** Colours of every graph surface; [dark] picks the set. Range colours are the same hue family in both. */
data class GraphPalette(
    val dark: Boolean,
    val bg: Int,
    val ink: Int,
    val inkSoft: Int,
    val grid: Int,
    val veryLow: Int,
    val low: Int,
    val tight: Int,
    val target: Int,
    val high: Int,
    val veryHigh: Int,
    val extremelyHigh: Int,
) {
    /** The colour of a glucose value: its band's, or [extremelyHigh] from [Bands.extremelyHigh] up. */
    fun at(
        b: Bands,
        mgdl: Double,
    ) = if (mgdl >= b.extremelyHigh) extremelyHigh else of(b.of(mgdl))

    fun at(
        b: Bands,
        mgdl: Int,
    ) = at(b, mgdl.toDouble())

    fun of(b: Band) =
        when (b) {
            Band.VERY_LOW -> veryLow
            Band.LOW -> low
            Band.TIGHT -> tight
            Band.TARGET -> target
            Band.HIGH -> high
            Band.VERY_HIGH -> veryHigh
        }

    companion object {
        val DARK =
            GraphPalette(
                true,
                bg = 0xFF1C1C1E.toInt(),
                ink = 0xFFF2F2F2.toInt(),
                inkSoft = 0xFFA3A3A8.toInt(),
                grid = 0xFF3A3A3E.toInt(),
                veryLow = 0xFFC2413B.toInt(),
                low = 0xFFF0524F.toInt(),
                tight = 0xFF3DD47A.toInt(),
                target = 0xFF8CC63F.toInt(),
                high = 0xFFF5A524.toInt(),
                veryHigh = 0xFFFF7A45.toInt(),
                extremelyHigh = 0xFFE35BD0.toInt(),
            )
        val LIGHT =
            GraphPalette(
                false,
                bg = 0xFFFFFFFF.toInt(),
                ink = 0xFF1C1C1E.toInt(),
                inkSoft = 0xFF6B6B70.toInt(),
                grid = 0xFFDADADF.toInt(),
                veryLow = 0xFFB3261E.toInt(),
                low = 0xFFD92D2A.toInt(),
                tight = 0xFF17A34A.toInt(),
                target = 0xFF6E9E1E.toInt(),
                high = 0xFFD97706.toInt(),
                veryHigh = 0xFFC2410C.toInt(),
                extremelyHigh = 0xFFA3238F.toInt(),
            )

        fun of(dark: Boolean) = if (dark) DARK else LIGHT
    }
}

/** What one placement of the chart shows. [windowS] = the time span (and the window of the distribution). */
data class GraphStyle(
    val windowS: Long = 12 * 3600L,
    val bands: Bands = Bands(),
    /** The left overlay: the distribution and the % column. */
    val side: Boolean = true,
    val axisLabels: Boolean = true,
    val treatments: Boolean = true,
    /** Extra dashed lines, mg/dL ([GraphConfig.lines]). */
    val lines: List<Int> = GraphConfig.DEFAULT_LINES,
    /** Hours between the vertical lines, at least ([GraphConfig.gridHours]); 0 = none (the time labels stay). */
    val gridHours: Int = 1,
    /** The crown over a perfect 100 in the % column ([GraphConfig.columnCrown]). */
    val crown: Boolean = true,
    /** Which parts of each treatment kind show ([GraphConfig.marks]). */
    val marks: MarkShow = MarkShow.DEFAULT,
)

/**
 * A chart scrolled through time (the app panel; the other surfaces can't scroll): the window ends at [endS] (epoch s,
 * at most now); [y] = the y range to draw while the surface animates it ([GlucoseCard.yRange]; null = the window's
 * own); [now] = the "Now" pill's visibility 0..1; [plotInset] = the plot's bottom as px above the chart's bottom while
 * the surface eases it (null = where the layout puts it); [spanS] = the window's length while zoomed
 * ([MIN_SPAN_S]..[MAX_SPAN_S]; null = [GraphStyle.windowS]).
 */
data class ChartView(
    val endS: Long,
    val y: Pair<Float, Float>? = null,
    val now: Float = 0f,
    val plotInset: Float? = null,
    val spanS: Long? = null,
) {
    companion object {
        const val MIN_SPAN_S = 2 * 3600L
        const val MAX_SPAN_S = 31 * 86_400L
    }
}

/** What a slot beside the value shows: a time-in-range ring or one of the report's numbers. */
enum class SlotKind { TIR, GMI, AVERAGE, CV }

/** Where the last-treatment slot sits among the slots: [NEAR_VALUE] = leftmost, next to the number; [END] = rightmost. */
enum class LastPlace { OFF, NEAR_VALUE, END }

/**
 * One slot beside the value: its [kind], window and, for a ring, which range its centre number counts. [faded] = the
 * second and third slots are drawn fainter than the main one; [order] = the ring's segments clockwise from 12 o'clock;
 * [marks] = it wears its [TirMark].
 */
data class RingSpec(
    val windowS: Long,
    val range: TirRange = TirRange.IN_RANGE,
    val kind: SlotKind = SlotKind.TIR,
    val faded: Boolean = true,
    val order: List<Band> = DEFAULT_RING_ORDER,
    val marks: Boolean = true,
) {
    companion object {
        /** In range from the top (very good first), the lows, then the highs, very high last. */
        val DEFAULT_RING_ORDER = listOf(Band.TIGHT, Band.TARGET, Band.LOW, Band.VERY_LOW, Band.HIGH, Band.VERY_HIGH)
    }
}

/**
 * What a whole card shows: value header and/or chart, up to three rings (main first), the last-treatment slot ([last],
 * [lastElse] while there's none) and the status badge under the value ([badge]). A surface builds it from its place's
 * settings only; [sensorTime] has no default so every place passes its own.
 */
data class CardStyle(
    val graph: GraphStyle? = GraphStyle(),
    val rings: List<RingSpec> = listOf(RingSpec(86_400), RingSpec(7 * 86_400L)),
    val value: Boolean = true,
    val last: LastPlace = LastPlace.OFF,
    val lastElse: RingSpec? = null,
    val badge: Boolean = true,
    /** The sensor's time left on this card; warm-up and ended show regardless. */
    val sensorTime: Boolean,
    /** Slots drawn as discs of this colour (on a coloured surface: the notification); null = none. */
    val slotDisc: Int? = null,
    /** The value in this colour instead of its range colour (a surface whose own colour already says the range). */
    val valueInk: Int? = null,
    /** The ranges the value's colour and the slots count in: the user's, also on a card without a chart. */
    val bands: Bands = graph?.bands ?: Bands(),
)

/**
 * Which part of a card one [GlucoseCard.draw] call paints. A surface that puts parts in boxes of its own draws each
 * with the SAME whole-card [CardStyle], so every whole-card decision (sensor badge spot, slots) is still made once.
 */
enum class CardPart { ALL, HEADER, VALUE, SLOTS, GRAPH }

/** Where the sensor's time left goes on a card ([GlucoseCard.sensorSpot]). */
enum class SensorSpot {
    /** In the chart's hour-label row, at its left edge. */
    GRAPH,
    NONE,
}

/**
 * The app panel's value animation (paint only, never layout): the previous text [prev] rolling into the new one as
 * [roll] goes 0 → 1 ([up] = a rising value), the arrow at [arrowDeg] and the value in [color] while they ease. Null
 * (or reduced motion) = drawn still.
 */
data class CardMotion(
    val prev: String?,
    val roll: Float,
    val up: Boolean,
    val arrowDeg: Float?,
    val color: Int?,
)
