// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import java.util.Calendar

/** One entry the treatment dialog creates; [at] = epoch s. */
data class NewEntry(val k: String, val a: Double, val note: String, val at: Long)

/**
 * The Add treatment dialog's form without the UI (ui/TreatmentDialog.kt): what the amount fields accept, the entries
 * Save creates and the time picker's day. The Mac's dialog does the same (`TreatmentForm` in TreatmentDialog.swift);
 * both run the cases in spikes/libreview/treatment-form.json.
 */
object TreatmentForm {
    const val MAX_UNITS = 100.0
    const val MAX_GRAMS = 500.0
    const val NOTE_BYTES = 100

    /** What an amount field keeps of a typed text: digits, `.` and `,`, at most 5 characters. */
    fun filterAmount(v: String): String = v.filter { it.isDigit() || it == '.' || it == ',' }.take(5)

    /** "4", "4.5" (insulin, 0.1u steps) or whole grams; null = empty; NaN = not a valid amount (0 after rounding too). */
    fun parse(s: String, grams: Boolean): Double? {
        val t = s.trim().replace(',', '.')
        if (t.isEmpty()) return null
        val v = t.toDoubleOrNull() ?: return Double.NaN
        val max = if (grams) MAX_GRAMS else MAX_UNITS
        if (v <= 0 || v > max) return Double.NaN
        val a = if (grams) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
        return if (a <= 0) Double.NaN else a
    }

    /** Any filled amount field that isn't a valid amount. */
    fun invalid(rapid: String, long: String, carbs: String): Boolean =
        listOf(parse(rapid, false), parse(long, false), parse(carbs, true)).any { it != null && it.isNaN() }

    /** The entries Save creates at [at], in kind order (rapid, long, carbs, note); null = an invalid amount. */
    fun entries(rapid: String, long: String, carbs: String, note: String, at: Long): List<NewEntry>? {
        if (invalid(rapid, long, carbs)) return null
        val n = note.trim()
        return buildList {
            parse(rapid, false)?.let { add(NewEntry("rapid", it, "", at)) }
            parse(long, false)?.let { add(NewEntry("long", it, "", at)) }
            parse(carbs, true)?.let { add(NewEntry("carbs", it, "", at)) }
            if (n.isNotEmpty()) add(NewEntry("note", 0.0, Treatment.clip(n, NOTE_BYTES), at))
        }
    }

    /**
     * The picked [hour]:[minute] today, or yesterday when that is later than [now] (entries are logged after the fact,
     * never ahead); null = within the current minute, i.e. now. Epoch s.
     */
    fun pickTime(now: Calendar, hour: Int, minute: Int): Long? {
        val pick = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (after(now)) add(Calendar.DAY_OF_YEAR, -1)
        }
        return if (now.timeInMillis - pick.timeInMillis < 60_000) null else pick.timeInMillis / 1000
    }

    /** Longest "minutes ago": under a day, like [pickTime] (a picked time is at most 23:59 back). */
    const val MAX_AGO_MIN = 1439

    /** What the minutes-ago field keeps of a typed text: ASCII digits, at most 4. */
    fun filterMinutes(v: String): String = v.filter { it in '0'..'9' }.take(4)

    /** Typed minutes ago → minutes; null = empty or more than [MAX_AGO_MIN] (OK disabled). */
    fun agoMinutes(s: String): Int? = s.trim().toIntOrNull()?.takeIf { it in 0..MAX_AGO_MIN }

    /** [minutes] before [now] (epoch s), resolved when saving; null = 0 minutes, i.e. now. */
    fun agoTime(now: Long, minutes: Int): Long? = if (minutes == 0) null else now - minutes * 60L
}
