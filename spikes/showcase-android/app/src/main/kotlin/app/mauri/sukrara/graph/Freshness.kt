// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
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

        /** "14:05" today, "Tue 14:05" this week, "12 Oct" before. A clock time doesn't tick, so nothing redraws for it. */
        fun timeText(t: Long, nowS: Long, locale: Locale = Locale.getDefault()): String {
            val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
            val n = Calendar.getInstance().apply { timeInMillis = nowS * 1000 }
            val time = DateFormat.getTimeInstance(DateFormat.SHORT, locale).format(Date(t * 1000))
            return when {
                c.get(Calendar.YEAR) == n.get(Calendar.YEAR) && c.get(Calendar.DAY_OF_YEAR) == n.get(Calendar.DAY_OF_YEAR) -> time
                nowS - t < 6 * 86400 -> "%ta %s".format(locale, c, time)
                else -> "%te %tb".format(locale, c, c)
            }
        }
    }
}
