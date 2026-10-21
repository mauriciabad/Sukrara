// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import java.util.Calendar
import java.util.Locale

/**
 * How old the latest reading is decides what a surface shows (investigation 16). The value is assumed to be current,
 * so a fresh one carries no "time ago"; the older it gets, the less is shown:
 *
 * | | age | value | time of the reading | graph |
 * |---|---|---|---|---|
 * | [FRESH] | ≤ 90 s | ✓ | – | ✓ |
 * | [LATE] | ≤ 5 min | ✓ | ✓ | ✓ |
 * | [STALE] | ≤ 20 min | struck through | ✓ | ✓ |
 * | [OLD] | ≤ 3 h | – | ✓ ("since 14:05") | ✓ |
 * | [GONE] | older / none | – | ✓ | – |
 *
 * 90 s, not 60: readings come every minute, so a reading a few seconds over a minute old is still the latest one on
 * time. 20 min is when the "no data" alarm fires.
 */
enum class Freshness(val showValue: Boolean, val showTime: Boolean, val strike: Boolean, val showGraph: Boolean) {
    FRESH(true, false, false, true),
    LATE(true, true, false, true),
    STALE(true, true, true, true),
    OLD(false, true, false, true),
    GONE(false, true, false, false);

    companion object {
        const val LATE_S = 90L
        const val STALE_S = 5 * 60L
        const val OLD_S = 20 * 60L
        const val GONE_S = 3 * 3600L

        fun of(ageS: Long?): Freshness = when {
            ageS == null -> GONE
            ageS <= LATE_S -> FRESH
            ageS <= STALE_S -> LATE
            ageS <= OLD_S -> STALE
            ageS <= GONE_S -> OLD
            else -> GONE
        }

        /** The next age (s) at which [of] changes, so a surface knows when to redraw without a new reading. */
        fun nextChangeS(ageS: Long): Long? = listOf(LATE_S, STALE_S, OLD_S, GONE_S).firstOrNull { it >= ageS }?.plus(1)

        /** When (epoch s) the step of a reading taken at [t] changes next, seen at [nowS]; null = never again / no reading. */
        fun nextChangeAt(t: Long?, nowS: Long): Long? = t?.let { r -> nextChangeS(nowS - r)?.let { r + it } }

        /**
         * What every glucose surface shows for the latest reading ([sgv] at [t], [rate] mg/dL/min) at [nowS]: the one
         * decision behind the cards (app panel, widgets, lock screen, notification art), the glucose notification and
         * its small icon, the Live Update chip, the root status-bar icons and the SystemUI hook. The Mac's
         * `Freshness.look` is the same rule; both run spikes/graph-fixtures/freshness.
         * [ended] = the sensor has ended (its own state, before any value).
         */
        fun look(sgv: Int?, t: Long?, rate: Double?, nowS: Long, ended: Boolean = false): GlucoseLook {
            val fr = of(t?.let { nowS - it })
            return when {
                ended -> GlucoseLook(GlucoseLook.State.ENDED, fr, null, false, null)
                sgv == null || t == null -> GlucoseLook(GlucoseLook.State.NONE, fr, null, false, null)
                !fr.showValue -> GlucoseLook(GlucoseLook.State.NO_READINGS, fr, null, false, null)
                else -> GlucoseLook(GlucoseLook.State.VALUE, fr, valueText(sgv), fr.strike,
                    if (rate != null && !fr.strike) TrendMark.ARROW else TrendMark.NONE)
            }
        }

        /** The value as text: the number, or LO / HI outside the sensor's 40–400 mg/dL. */
        fun valueText(sgv: Int): String = when {
            sgv < 40 -> "LO"
            sgv > 400 -> "HI"
            else -> sgv.toString()
        }

        /**
         * "14:05" today, "Tue 14:05" this week, "12 Oct" before; the time in the phone's 12/24-hour setting ([Clock]).
         * A clock time doesn't tick, so nothing redraws for it.
         */
        fun timeText(t: Long, nowS: Long, hour24: Boolean = Clock.hour24, locale: Locale = Locale.getDefault()): String {
            val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
            val n = Calendar.getInstance().apply { timeInMillis = nowS * 1000 }
            val time = Clock.time(t, hour24, locale)
            return when {
                c.get(Calendar.YEAR) == n.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR) -> time
                nowS - t < 6 * 86400 -> "%ta %s".format(locale, c, time)
                else -> "%te %tb".format(locale, c, c)
            }
        }
    }
}

/**
 * The trend beside a value: the arrow, or [NONE] = no trend to show (no rate from the sensor, or the value is struck
 * through): a hollow ring of the arrow's size (the user, 2026-10-21: "something different" from an arrow). A circle has
 * no direction, so it can't be read as steady (→), and unlike the old dot / "?" it neither looks like a speck nor casts
 * doubt on the number. Text places write it as [RING].
 */
enum class TrendMark { ARROW, NONE;
    companion object {
        const val RING = "○"
        /** The ring in an arrow's square box of [size] (the arrows' unit is the box): radius and stroke. */
        fun ringRadius(size: Float) = size * 0.24f
        fun ringStroke(size: Float) = size * 0.11f
    }
}

/**
 * [Freshness.look]'s answer: the [state]; for a value its [text] (number or LO / HI), whether it's [strike]n through
 * and grey (stale, 5–20 min) and its [trend] (null without a value); [fresh] for the time and the chart
 * ([Freshness.showTime], [Freshness.showGraph]).
 */
data class GlucoseLook(val state: State, val fresh: Freshness, val text: String?, val strike: Boolean, val trend: TrendMark?) {
    enum class State {
        /** A value to show (fresh, late or struck through). */
        VALUE,
        /** Nothing for 20 min: no value, the "no readings" icon ([NO_READINGS_PATH]) and since when. */
        NO_READINGS,
        /** No reading at all yet. */
        NONE,
        /** The sensor has ended: the checkered flag. */
        ENDED,
    }

    /** Grey instead of the range colour: the struck-through value. */
    val grey get() = strike

    /** [text] for places that can't draw a line through it (the Live Update chip): every character struck (U+0336). */
    val plain: String? get() = text?.let { t -> if (strike) t.map { "$it\u0336" }.joinToString("") else t }

    companion object {
        /**
         * Material Symbols Outlined `sensors_off` (Apache 2.0), 960 × 960 with y from −960: no readings for 20 min. The
         * same path is `res/drawable/ic_stat_no_readings.xml`; the Mac draws SF Symbol `antenna.radiowaves.left.and.right.slash`.
         */
        const val NO_READINGS_PATH = "M791-55 325-521q-2 10-3.5 20t-1.5 21q0 33 12.5 62t34.5 51l-57 57q-32-33-51-76.5T240-480q0-28 6-54t17-49l-59-59q-21 36-32.5 76.5T160-480q0 67 25 125t69 101l-57 57q-54-55-85.5-127.5T80-480q0-62 17.5-117T146-700l-91-91 57-57 736 736-57 57Zm23-205-58-58q21-36 32.5-76.5T800-480q0-67-25-125t-69-101l57-57q54 54 85.5 126.5T880-480q0 61-17.5 116.5T814-260ZM697-377l-62-62q2-10 3.5-20t1.5-21q0-33-12.5-62T593-593l57-57q32 32 51 75.5t19 94.5q0 28-6 54t-17 49Z"
    }
}
