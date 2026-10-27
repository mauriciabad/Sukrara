// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/**
 * Every clock time Sukrara shows ("21:08" / "9:08 PM"): card, widgets, notifications, treatments, the graph's hour
 * labels. They follow the phone's Settings → 24-hour time, not the locale's default. Plain Kotlin (no Context): the
 * app installs [hour24Source] (`DateFormat.is24HourFormat`) once at start — the ONE place that setting is read; every
 * surface formats through here with the default [hour24]. The explicit parameters are for the clock fixtures.
 * Mac: `Clock` in SukraraGraph. The pattern is the locale's short time with its hour field switched to the wanted
 * cycle (`h:mm a` ↔ `HH:mm`), like Android's `DateFormat.getTimeFormat` without Android's ICU.
 */
object Clock {
    /** The phone's 24-hour setting, installed by the app; null (JVM tests) = the locale's. */
    @Volatile var hour24Source: (() -> Boolean)? = null

    val hour24: Boolean get() = hour24Source?.let { runCatching(it).getOrNull() } ?: localeHour24(Locale.getDefault())

    /** What every clock time drawn now depends on (24-hour setting + zone): part of every redraw key. */
    fun signature(): String = "$hour24|${ZoneId.systemDefault().id}"

    fun localeHour24(locale: Locale): Boolean = unquoted(localePattern(locale)).none { it == 'h' || it == 'K' }

    /** "21:08" / "9:08 PM" for [t] (epoch s). */
    fun time(
        t: Long,
        hour24: Boolean = this.hour24,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = formatter(hour24, locale).format(Instant.ofEpochSecond(t).atZone(zone))

    /** "12 Oct 21:08": a day this year and its time. */
    fun dayTime(
        t: Long,
        hour24: Boolean = this.hour24,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = dayLabel(t, locale, zone) + " " + time(t, hour24, locale, zone)

    /** "12 Oct 2026, 21:08": the locale's medium date and the time. */
    fun dateTime(
        t: Long,
        hour24: Boolean = this.hour24,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
        return date.format(Instant.ofEpochSecond(t).atZone(zone)) + ", " + time(t, hour24, locale, zone)
    }

    /** A minute of the day (0–1439, e.g. a reminder's time) as a clock time. */
    fun minuteOfDay(
        m: Int,
        hour24: Boolean = this.hour24,
        locale: Locale = Locale.getDefault(),
    ): String = time(m * 60L, hour24, locale, ZoneOffset.UTC)

    /** The graph's day label (a zoomed-out chart): "17 Oct". */
    fun dayLabel(
        t: Long,
        locale: Locale = Locale.getDefault(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val at = Instant.ofEpochSecond(t).atZone(zone)
        return "${at.dayOfMonth} ${at.month.getDisplayName(TextStyle.SHORT, locale)}"
    }

    /** The graph's hour label: "21:00" / "9 PM". */
    fun hourLabel(
        h: Int,
        hour24: Boolean = this.hour24,
        locale: Locale = Locale.getDefault(),
    ): String =
        if (hour24) {
            "%02d:00".format(Locale.ROOT, h)
        } else {
            val period = DateTimeFormatter.ofPattern("a", locale).format(java.time.LocalTime.of(h, 0))
            "${if (h % 12 == 0) 12 else h % 12} $period"
        }

    /** The time pattern for [locale] in the wanted hour cycle. */
    fun pattern(
        hour24: Boolean,
        locale: Locale,
    ): String {
        val p = localePattern(locale)
        val is24 = unquoted(p).none { it == 'h' || it == 'K' }
        return when {
            hour24 == is24 -> p
            hour24 ->
                collapseHour(
                    mapUnquoted(p) { c ->
                        when (c) {
                            'h', 'K' -> 'H'
                            'a', 'b', 'B' -> null
                            else -> c
                        }
                    },
                    "HH",
                ).trim(' ', '\u00A0', '\u202F')
            else ->
                collapseHour(
                    mapUnquoted(p) { c ->
                        when (c) {
                            'H', 'k' -> 'h'
                            else -> c
                        }
                    },
                    "h",
                ) + " a"
        }
    }

    private fun formatter(
        hour24: Boolean,
        locale: Locale,
    ): DateTimeFormatter = formatters.getOrPut(hour24 to locale) { DateTimeFormatter.ofPattern(pattern(hour24, locale), locale) }

    /** Built once per hour cycle and locale (every hour label of every chart frame formats through here). */
    private val formatters = java.util.concurrent.ConcurrentHashMap<Pair<Boolean, Locale>, DateTimeFormatter>()

    private fun localePattern(locale: Locale): String =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)

    /** The pattern's letters outside quotes. */
    private fun unquoted(p: String): String =
        buildString {
            var q = false
            for (c in p) {
                if (c == '\'') q = !q else if (!q) append(c)
            }
        }

    /** Maps the pattern letters outside quotes (null drops one, with the space before it). */
    private fun mapUnquoted(
        p: String,
        f: (Char) -> Char?,
    ): String =
        buildString {
            var q = false
            for (c in p) {
                when {
                    c == '\'' -> {
                        q = !q
                        append(c)
                    }
                    q -> append(c)
                    else -> f(c)?.let { append(it) } ?: run { while (isNotEmpty() && last() in " \u00A0\u202F") deleteCharAt(length - 1) }
                }
            }
        }

    /** One hour field of the wanted width ("h" → "HH" going to 24 h, "HH" → "h" going to 12 h). */
    private fun collapseHour(
        p: String,
        to: String,
    ): String = Regex("${to[0]}+").replaceFirst(p, to)
}
