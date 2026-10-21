// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import java.text.DateFormatSymbols
import java.text.SimpleDateFormat
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/**
 * Every clock time Sukrara shows ("21:08" / "9:08 PM"): the card, the widgets, the lock screen, the notifications,
 * the sensor reminders and calendar event, the treatments, the graph's hour labels. They follow the phone's
 * Settings → 24-hour time, not the locale's default. Plain Kotlin (no Context) so the drawing code and the JVM tests
 * use it too: the app installs [hour24Source] (`DateFormat.is24HourFormat`) at start and that is the ONE place the
 * setting is read; every surface (cards, graph hour labels, widgets, notifications, screens) formats through here with
 * the default [hour24], never a flag of its own (2026-10-21: the value widget read it from one place and the chart
 * cards from another, "08:59" beside "8:59 AM"). The explicit parameter is for the clock fixtures only. tools/check
 * `clock` holds the code to it; CardLayoutTest renders every surface in 12- and 24-hour time. Mac: `Clock` in
 * SukraraGraph.swift.
 *
 * The pattern is the locale's short time with its hour field switched to the wanted cycle (`h:mm a` ↔ `HH:mm`), the
 * same result as Android's `DateFormat.getTimeFormat` without needing Android's ICU.
 */
object Clock {
    /** The phone's 24-hour setting, installed by the app; null (JVM, a process the app didn't start) = the locale's. */
    @Volatile var hour24Source: (() -> Boolean)? = null

    /** The phone's 24-hour setting (the locale's default when unknown). */
    val hour24: Boolean get() = hour24Source?.let { runCatching(it).getOrNull() } ?: localeHour24(Locale.getDefault())

    /**
     * What every clock time drawn now depends on: the 24-hour setting and the time zone. Part of every redraw key (widgets,
     * the notification and its pictures), so a flip of the setting while Sukrara runs redraws them
     * ([app.mauri.sukrara.widgets.SukraraHooks.CLOCK_ACTIONS] triggers it).
     */
    fun signature(): String = "${hour24}|${java.util.TimeZone.getDefault().id}"

    /** Does [locale] use a 24-hour clock by default? */
    fun localeHour24(locale: Locale): Boolean = unquoted(localePattern(locale)).none { it == 'h' || it == 'K' }

    /** Tests: every time formatted and the hour cycle it was formatted in, as it happens (null in the app). */
    @Volatile var observer: ((String, Boolean) -> Unit)? = null

    /** "21:08" / "9:08 PM" for [t] (unix s). */
    fun time(t: Long, hour24: Boolean = this.hour24, locale: Locale = Locale.getDefault()): String =
        SimpleDateFormat(pattern(hour24, locale), locale).format(Date(t * 1000)).also { observer?.invoke(it, hour24) }

    /** "12 Oct 21:08": a day this year and its time. */
    fun dayTime(t: Long, hour24: Boolean = this.hour24, locale: Locale = Locale.getDefault()): String =
        "%te %tb".format(locale, t * 1000, t * 1000) + " " + time(t, hour24, locale)

    /** "12 Oct 2026, 21:08": the locale's medium date and the time (the calendar event's description). */
    fun dateTime(t: Long, hour24: Boolean = this.hour24, locale: Locale = Locale.getDefault()): String =
        java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale).format(Date(t * 1000)) + ", " + time(t, hour24, locale)

    /** A minute of the day (0–1439, e.g. a reminder's time) as a clock time. */
    fun minuteOfDay(m: Int, hour24: Boolean = this.hour24, locale: Locale = Locale.getDefault()): String {
        val f = SimpleDateFormat(pattern(hour24, locale), locale).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return f.format(Date(((m / 60) * 3600L + (m % 60) * 60L) * 1000)).also { observer?.invoke(it, hour24) }
    }

    /** The graph's hour label: "21:00" / "9 PM". */
    fun hourLabel(h: Int, hour24: Boolean = this.hour24, locale: Locale = Locale.getDefault()): String =
        (if (hour24) "%02d:00".format(Locale.ROOT, h)
        else "${if (h % 12 == 0) 12 else h % 12} ${DateFormatSymbols.getInstance(locale).amPmStrings[if (h < 12) 0 else 1]}")
            .also { observer?.invoke(it, hour24) }

    /** The time pattern for [locale] in the wanted hour cycle. */
    fun pattern(hour24: Boolean, locale: Locale): String {
        val p = localePattern(locale)
        val is24 = unquoted(p).none { it == 'h' || it == 'K' }
        return when {
            hour24 == is24 -> p
            hour24 -> mapUnquoted(p) { c -> when (c) { 'h', 'K' -> 'H'; 'a', 'b', 'B' -> null; else -> c } }
                .let { collapseHour(it, "HH") }.trim(' ', '\u00A0', '\u202F')
            else -> mapUnquoted(p) { c -> when (c) { 'H', 'k' -> 'h'; else -> c } }.let { collapseHour(it, "h") } + " a"
        }
    }

    private fun localePattern(locale: Locale): String =
        DateTimeFormatterBuilder.getLocalizedDateTimePattern(null, FormatStyle.SHORT, IsoChronology.INSTANCE, locale)

    /** The pattern's letters outside quotes. */
    private fun unquoted(p: String): String = buildString { var q = false; for (c in p) { if (c == '\'') q = !q else if (!q) append(c) } }

    /** Maps the pattern letters outside quotes (null drops one, with the space before it). */
    private fun mapUnquoted(p: String, f: (Char) -> Char?): String = buildString {
        var q = false
        p.forEach { c ->
            if (c == '\'') { q = !q; append(c) }
            else if (q) append(c)
            else f(c)?.let { append(it) } ?: run { while (isNotEmpty() && last() in " \u00A0\u202F") deleteCharAt(length - 1) }
        }
    }

    /** One hour field of the wanted width ("h" → "HH" when going to 24 h, "HH" → "h" to 12 h). */
    private fun collapseHour(p: String, to: String): String {
        val c = to[0]
        return Regex("$c+").replaceFirst(p, to)
    }
}
