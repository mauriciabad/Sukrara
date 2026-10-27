// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import app.mauri.sukrara.core.model.TreatmentKind
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** One entry the treatment dialog saves (amount in units or grams; 0 for a note). */
data class NewEntry(
    val kind: TreatmentKind,
    val amount: Double,
    val note: String = "",
)

/**
 * The Add treatment form without the UI: what the amount fields accept, the entries Save creates and when they
 * happen. The rules are shared with the Mac through contracts/treatments/treatment-form.json (TreatmentFormTest).
 */
object TreatmentForm {
    const val MAX_UNITS = 100.0
    const val MAX_GRAMS = 500.0
    const val NOTE_BYTES = 100

    /** Longest "minutes ago": under a day, like a picked time (at most 23:59 back). */
    const val MAX_AGO_MIN = 1439

    /** An amount field's parsed value. */
    sealed interface Amount {
        data object Empty : Amount

        data class Valid(
            val value: Double,
        ) : Amount

        /** Not a number, zero after rounding, or over the limit: Save is disabled and the limits line shows. */
        data object Invalid : Amount
    }

    /** What an amount field keeps of a typed text: digits, `.` and `,`, at most 5 characters. */
    fun filterAmount(typed: String): String = typed.filter { it.isDigit() || it == '.' || it == ',' }.take(5)

    /** What the minutes-ago field keeps: ASCII digits, at most 4. */
    fun filterMinutes(typed: String): String = typed.filter { it in '0'..'9' }.take(4)

    /** Insulin rounds to 0.1 u, carbs to whole grams; a decimal comma counts as a dot. */
    fun parse(
        text: String,
        grams: Boolean,
    ): Amount {
        val t = text.trim().replace(',', '.')
        if (t.isEmpty()) return Amount.Empty
        val v = t.toDoubleOrNull() ?: return Amount.Invalid
        if (v <= 0 || v > if (grams) MAX_GRAMS else MAX_UNITS) return Amount.Invalid
        val rounded = if (grams) Math.round(v).toDouble() else Math.round(v * 10) / 10.0
        return if (rounded <= 0) Amount.Invalid else Amount.Valid(rounded)
    }

    /** The entries Save creates, in kind order (rapid, long, carbs, note); null = an amount is invalid. */
    fun entries(
        rapid: String,
        long: String,
        carbs: String,
        note: String,
    ): List<NewEntry>? {
        val amounts =
            listOf(
                TreatmentKind.RapidInsulin to parse(rapid, grams = false),
                TreatmentKind.LongInsulin to parse(long, grams = false),
                TreatmentKind.Carbs to parse(carbs, grams = true),
            )
        if (amounts.any { it.second == Amount.Invalid }) return null
        val text = clip(note.trim(), NOTE_BYTES)
        return buildList {
            amounts.forEach { (kind, amount) -> if (amount is Amount.Valid) add(NewEntry(kind, amount.value)) }
            if (text.isNotEmpty()) add(NewEntry(TreatmentKind.Note, 0.0, text))
        }
    }

    /** [text] cut to at most [maxBytes] UTF-8 bytes without splitting a character. */
    fun clip(
        text: String,
        maxBytes: Int,
    ): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val cp = text.codePointAt(end)
            bytes += String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes > maxBytes) break
            end += Character.charCount(cp)
        }
        return text.substring(0, end)
    }

    /**
     * The picked [time] today in [zone], or yesterday when that is later than [now] (entries are logged after the
     * fact, never ahead); null = within the current minute, i.e. now.
     */
    fun pickTime(
        now: Instant,
        zone: ZoneId,
        time: LocalTime,
    ): Instant? {
        val today = now.atZone(zone).truncatedTo(ChronoUnit.DAYS).with(time.truncatedTo(ChronoUnit.MINUTES))
        val pick = if (today.toInstant().isAfter(now)) today.minusDays(1) else today
        return pick.toInstant().takeIf { Duration.between(it, now) >= Duration.ofMinutes(1) }
    }

    /** Typed minutes ago → minutes; null = empty or a day or more (OK disabled). */
    fun agoMinutes(typed: String): Int? = typed.trim().toIntOrNull()?.takeIf { it in 0..MAX_AGO_MIN }

    /** [minutes] before [now], resolved when saving; null = 0 minutes, i.e. now. */
    fun agoTime(
        now: Instant,
        minutes: Int,
    ): Instant? = if (minutes == 0) null else now.minusSeconds(minutes * 60L)
}
