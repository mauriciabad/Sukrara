// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * How old the latest reading is decides what every surface shows (contracts/graph/freshness, docs/FEATURES.md
 * "Freshness"). A fresh value carries no "time ago"; the older it gets, the less is shown:
 *
 * | | age | value | time of the reading | graph |
 * |---|---|---|---|---|
 * | [FRESH] | ≤ 90 s | ✓ | – | ✓ |
 * | [LATE] | ≤ 5 min | ✓ | ✓ | ✓ |
 * | [STALE] | ≤ 20 min | struck through, grey | ✓ | ✓ |
 * | [OLD] | ≤ 3 h | – ("no readings") | ✓ | ✓ |
 * | [GONE] | older / none | – | ✓ | – |
 *
 * 90 s, not 60: readings come every minute, so one a few seconds over a minute old is still on time. 20 min is when
 * the "no data" alarm fires. Times are epoch seconds (the renderer's unit throughout).
 */
enum class Freshness(
    val showValue: Boolean,
    val showTime: Boolean,
    val strike: Boolean,
    val showGraph: Boolean,
) {
    FRESH(true, false, false, true),
    LATE(true, true, false, true),
    STALE(true, true, true, true),
    OLD(false, true, false, true),
    GONE(false, true, false, false),
    ;

    companion object {
        const val LATE_S = 90L
        const val STALE_S = 5 * 60L
        const val OLD_S = 20 * 60L
        const val GONE_S = 3 * 3600L
        private val STEPS = listOf(LATE_S, STALE_S, OLD_S, GONE_S)

        fun of(ageS: Long?): Freshness =
            when {
                ageS == null -> GONE
                ageS <= LATE_S -> FRESH
                ageS <= STALE_S -> LATE
                ageS <= OLD_S -> STALE
                ageS <= GONE_S -> OLD
                else -> GONE
            }

        /** The next age (s) at which [of] changes, so a surface knows when to redraw without a new reading. */
        fun nextChangeS(ageS: Long): Long? = STEPS.firstOrNull { it >= ageS }?.plus(1)

        /** When (epoch s) the step of a reading taken at [t] changes next, seen at [nowS]; null = never again / none. */
        fun nextChangeAt(
            t: Long?,
            nowS: Long,
        ): Long? = t?.let { r -> nextChangeS(nowS - r)?.let { r + it } }

        /**
         * THE look of the latest reading ([sgv] at [t], [rate] mg/dL/min) at [nowS], behind every card and glyph
         * surface (the Mac's `Freshness.look` is the same rule; both run contracts/graph/freshness). [ended] = the
         * sensor ended, [warmup] = a new sensor's warm-up time left ("42m"): their own states, before any value.
         * Surfaces ask `GlucoseCard.face`, not this.
         */
        fun look(
            sgv: Int?,
            t: Long?,
            rate: Double?,
            nowS: Long,
            ended: Boolean = false,
            warmup: String? = null,
        ): GlucoseLook {
            val fr = of(t?.let { nowS - it })
            return when {
                warmup != null -> GlucoseLook(GlucoseLook.State.WARMING_UP, fr, null, false, null, warmup)
                ended -> GlucoseLook(GlucoseLook.State.ENDED, fr, null, false, null)
                sgv == null || t == null -> GlucoseLook(GlucoseLook.State.NONE, fr, null, false, null)
                !fr.showValue -> GlucoseLook(GlucoseLook.State.NO_READINGS, fr, null, false, null)
                else ->
                    GlucoseLook(
                        GlucoseLook.State.VALUE,
                        fr,
                        valueText(sgv),
                        fr.strike,
                        if (rate != null && !fr.strike) TrendMark.ARROW else TrendMark.NONE,
                        rate = rate,
                    )
            }
        }

        /** The value as text: the number, or LO / HI outside the sensor's 40–400 mg/dL. */
        fun valueText(sgv: Int): String =
            when {
                sgv < 40 -> "LO"
                sgv > 400 -> "HI"
                else -> sgv.toString()
            }

        /** "14:05" today, "Tue 14:05" this week, "12 Oct" before; the clock time in the phone's 12/24-hour setting. */
        fun timeText(
            t: Long,
            nowS: Long,
            locale: Locale = Locale.getDefault(),
            zone: ZoneId = ZoneId.systemDefault(),
        ): String {
            val at = Instant.ofEpochSecond(t).atZone(zone)
            val now = Instant.ofEpochSecond(nowS).atZone(zone)
            val time = Clock.time(t, locale = locale, zone = zone)
            return when {
                at.toLocalDate() == now.toLocalDate() -> time
                nowS - t < 6 * 86400 -> at.dayOfWeek.getDisplayName(TextStyle.SHORT, locale) + " " + time
                else -> "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.SHORT, locale)}"
            }
        }

        /** Whole minutes since [t] (for "3 min ago" words); never negative. */
        fun minutesAgo(
            t: Long,
            nowS: Long,
        ): Long = ChronoUnit.MINUTES.between(Instant.ofEpochSecond(t), Instant.ofEpochSecond(maxOf(t, nowS)))
    }
}

/**
 * The trend beside a value: the arrow, or [NONE] = no trend to show (no rate, or the value is struck through) — a
 * hollow ring of the arrow's size, which has no direction so it can't be read as steady. Text places write [RING].
 */
enum class TrendMark {
    ARROW,
    NONE,
    ;

    companion object {
        const val RING = "○"

        /** The ring in an arrow's square box of [size]: radius and stroke. */
        fun ringRadius(size: Float) = size * 0.24f

        fun ringStroke(size: Float) = size * 0.11f
    }
}

/**
 * [Freshness.look]'s answer: the [state]; for a value its [text] (number or LO / HI), whether it's [strike]n through
 * and grey, and its [trend] (null without a value); [fresh] for the time and the chart.
 */
data class GlucoseLook(
    val state: State,
    val fresh: Freshness,
    val text: String?,
    val strike: Boolean,
    val trend: TrendMark?,
    val warmup: String? = null,
    val rate: Double? = null,
) {
    enum class State {
        /** A value to show (fresh, late or struck through). */
        VALUE,

        /** Nothing for 20 min: no value, the "no readings" icon and since when. */
        NO_READINGS,

        /** No reading at all yet. */
        NONE,

        /** The sensor has ended. */
        ENDED,

        /** A new sensor warming up: its time left ([warmup]) where the number goes. */
        WARMING_UP,
    }

    val shown: Boolean get() = state == State.VALUE

    /** [text] for places that can't draw a line through it (the Live Update chip): every character struck (U+0336). */
    val plain: String? get() = text?.let { t -> if (strike) t.map { "$it\u0336" }.joinToString("") else t }
}
