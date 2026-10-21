// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

/**
 * THE duration formatter (round f30; the user, 2026-10-21: "Showing 60 min and 120 min is long, 1 h and 2 h is
 * better. This applies everywhere in the UI"). Every user-visible duration goes through here; the formats and their
 * cases are spikes/durations/durations.json, shared with the Mac's Durations.swift (DurationsTest). tools/check rule
 * `durations` fails on a duration formatted anywhere else.
 */
object Durations {
    /** In words and settings (notifications, sheets, presets, history): "45 min", "1 h", "1 h 30 min", "12 h". */
    fun text(min: Long, w: Words = Words.current): String {
        val m = min.coerceAtLeast(0)
        return when {
            m < 60 -> w.durTextMinutes.format(m)
            m % 60 == 0L -> w.durTextHours.format(m / 60)
            else -> w.durTextHoursMinutes.format(m / 60, m % 60)
        }
    }

    fun text(min: Int, w: Words = Words.current): String = text(min.toLong(), w)

    /** [text] of a span in seconds, partial minutes rounded up (time left: "1 min" until it is over). */
    fun textS(s: Long, w: Words = Words.current): String = text((s.coerceAtLeast(0) + 59) / 60, w)

    /**
     * The card's compact convention (treatment labels, badges "Xm left"): "12m", "1h 5m", "3h" (whole hours drop the
     * minutes; from 6 h only hours), "2d" from two days.
     */
    fun compact(min: Long, w: Words = Words.current): String {
        val m = min.coerceAtLeast(0)
        return when {
            m < 60 -> w.durMinutes.format(m)
            m < 6 * 60 -> if (m % 60 == 0L) w.durHours.format(m / 60) else w.durHoursMinutes.format(m / 60, m % 60)
            m < 48 * 60 -> w.durHours.format(m / 60)
            else -> w.durDays.format(m / 1440)
        }
    }

    /** One unit, rounded down: "45m", from 60 min "20h", from 24 h "13d" (the sensor-life badge, the warm-up). */
    fun whole(min: Long, w: Words = Words.current): String {
        val m = min.coerceAtLeast(0)
        return when {
            m >= 1440 -> w.durDays.format(m / 1440)
            m >= 60 -> w.durHours.format(m / 60)
            else -> w.durMinutes.format(m)
        }
    }

    private val H_MM = Regex("""^(\d{1,3}):(\d{2})$""")
    private val HM = Regex("""^(?:(\d{1,3}(?:[.,]\d+)?)\s*h)?\s*(?:(\d{1,4})\s*(?:min|m)?)?$""")

    /**
     * Typed minutes (Mute…, a custom value): "45", "45 min", "1h", "1 h 30", "1h 30m", "1:30", "1,5 h" → minutes;
     * null when it isn't a duration. The caller checks the range.
     */
    fun parse(input: String): Int? {
        val s = input.trim().lowercase()
        if (s.isEmpty()) return null
        H_MM.matchEntire(s)?.let { m ->
            val mm = m.groupValues[2].toInt()
            return if (mm < 60) m.groupValues[1].toInt() * 60 + mm else null
        }
        val m = HM.matchEntire(s) ?: return null
        val h = m.groupValues[1]
        val mins = m.groupValues[2]
        if (h.isEmpty() && mins.isEmpty()) return null
        val hm = if (h.isEmpty()) 0.0 else h.replace(',', '.').toDouble() * 60
        return (hm + (mins.toIntOrNull() ?: 0)).let { Math.round(it).toInt() }
    }
}
