// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

/**
 * The words of the duration formats (Android: the `duration_*` strings, en + es). Each is a format string taking whole
 * numbers: [minutes] "%1$dm", [hours] "%1$dh", [hoursMinutes] "%1$dh %2$dm", [days] "%1$dd" (the card's compact
 * convention); [textMinutes] "%1$d min", [textHours] "%1$d h", [textHoursMinutes] "%1$d h %2$d min" (in words).
 */
data class DurationWords(
    val minutes: String,
    val hours: String,
    val hoursMinutes: String,
    val days: String,
    val textMinutes: String,
    val textHours: String,
    val textHoursMinutes: String,
)

/**
 * THE duration formatter (contracts/settings/durations.json; tools/check `durations` keeps every other place from
 * writing minutes by hand): 60 min is "1 h", never "60 min".
 */
object Durations {
    /** In words and settings: "45 min", "1 h", "1 h 30 min", "12 h" (no days). */
    fun text(
        min: Long,
        w: DurationWords,
    ): String {
        val m = min.coerceAtLeast(0)
        return when {
            m < 60 -> w.textMinutes.format(m)
            m % 60 == 0L -> w.textHours.format(m / 60)
            else -> w.textHoursMinutes.format(m / 60, m % 60)
        }
    }

    /** The card's compact convention: "12m", "1h 5m", "3h" (from 6 h whole hours only), "2d" from two days. */
    fun compact(
        min: Long,
        w: DurationWords,
    ): String {
        val m = min.coerceAtLeast(0)
        return when {
            m < 60 -> w.minutes.format(m)
            m < 6 * 60 && m % 60 != 0L -> w.hoursMinutes.format(m / 60, m % 60)
            m < 48 * 60 -> w.hours.format(m / 60)
            else -> w.days.format(m / 1440)
        }
    }

    /** One unit, rounded down: "45m", from 60 min "20h", from 24 h "13d" (the sensor-life badge, the warm-up). */
    fun whole(
        min: Long,
        w: DurationWords,
    ): String {
        val m = min.coerceAtLeast(0)
        return when {
            m >= 1440 -> w.days.format(m / 1440)
            m >= 60 -> w.hours.format(m / 60)
            else -> w.minutes.format(m)
        }
    }

    /** The quantity a plural agrees with for [compact] ("queda 1 h" / "quedan 1 h 30 min"): its one number, else the minutes. */
    fun compactQuantity(min: Long): Int {
        val m = min.coerceAtLeast(0)
        return when {
            m < 60 -> m
            m < 6 * 60 && m % 60 != 0L -> m
            m < 48 * 60 -> m / 60
            else -> m / 1440
        }.toQuantity()
    }

    /** The plural quantity of [whole]. */
    fun wholeQuantity(min: Long): Int {
        val m = min.coerceAtLeast(0)
        return (
            if (m >= 1440) {
                m / 1440
            } else if (m >= 60) {
                m / 60
            } else {
                m
            }
        ).toQuantity()
    }

    /** The plural quantity of [text]. */
    fun textQuantity(min: Long): Int {
        val m = min.coerceAtLeast(0)
        return (if (m >= 60 && m % 60 == 0L) m / 60 else m).toQuantity()
    }

    private fun Long.toQuantity(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private val hMm = Regex("""^(\d{1,3}):(\d{2})$""")
    private val hm = Regex("""^(?:(\d{1,3}(?:[.,]\d+)?)\s*h)?\s*(?:(\d{1,4})\s*(?:min|m)?)?$""")

    /** Typed minutes: "45", "45 min", "1h", "1 h 30", "1h 30m", "1:30", "1,5 h" → minutes; null = not a duration. */
    fun parse(input: String): Int? {
        val s = input.trim().lowercase()
        if (s.isEmpty()) return null
        hMm.matchEntire(s)?.let { m ->
            val mm = m.groupValues[2].toInt()
            return if (mm < 60) m.groupValues[1].toInt() * 60 + mm else null
        }
        val m = hm.matchEntire(s) ?: return null
        val h = m.groupValues[1]
        val mins = m.groupValues[2]
        if (h.isEmpty() && mins.isEmpty()) return null
        val fromHours = if (h.isEmpty()) 0.0 else h.replace(',', '.').toDouble() * 60
        return Math.round(fromHours + (mins.toIntOrNull() ?: 0)).toInt()
    }
}
