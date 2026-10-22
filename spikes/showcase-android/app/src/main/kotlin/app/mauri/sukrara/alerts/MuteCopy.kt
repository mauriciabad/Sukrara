// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import android.content.Context
import app.mauri.sukrara.R
import app.mauri.sukrara.core.Durations
import app.mauri.sukrara.core.Words

/**
 * THE mute / remind-less state lines (round f31; the user, 2026-10-22: "muted alerts should show a countdown, and the
 * Never option makes no sense … figure out the best copywriting"). Every surface that words a mute builds its line
 * here: the muted notification, the home pill's sheet and chips, Active now, Mute alerts' state and dialog, the
 * history. The cases are spikes/alerts/copy.json, shared with the Mac's MuteCopy (MuteCopyTest, `-alerts-selftest`).
 * Wording after AOSP Do Not Disturb (core/res zen_mode_forever "Until you turn off" / "Hasta que lo desactives",
 * zen_mode_until "Until %1$s", zen_mode_duration_hours "For 1 hour"). Durations only through [Durations]: text in the
 * lines, compact on the card's pill ([pill], drawn by StatusBadge from [Words]).
 *
 * A side's name is its main alert's ([Kind.side]): very low inside a low mute is still "Low alerts".
 */
object MuteCopy {
    /** The minutes a mute ending at [until] still shows at [now] (epoch s): partial minutes round up ("1 min" to the end). */
    fun leftMin(until: Long, now: Long): Long = ((until - now).coerceAtLeast(0) + 59) / 60

    /**
     * When the time left of a mute ending at [until] next changes on screen (epoch s), seen at [now]: the minute
     * boundary counted from its end, so every surface ticks together with no drift; null for no mute, no end
     * ([AlertEngine.NEVER]) or one already over.
     */
    fun nextChangeAt(until: Long, now: Long): Long? {
        if (until <= now || until >= AlertEngine.NEVER) return null
        return until - (leftMin(until, now) - 1) * 60
    }

    /** The side's name in the mute lines: "Low" / "de glucosa baja". */
    fun side(ctx: Context, kind: String): String = ctx.getString(when (Kind.side(kind)) {
        "low" -> R.string.mute_side_low
        "high" -> R.string.mute_side_high
        Kind.FALLING_FAST -> R.string.mute_side_falling_fast
        Kind.RISING_FAST -> R.string.mute_side_rising_fast
        else -> R.string.mute_side_no_data
    })

    /** "12 min left" / "quedan 12 min" ("queda" with exactly one unit: 1 min, 1 h). */
    fun left(ctx: Context, min: Long, w: Words = Words.current): String =
        ctx.getString(if (min == 1L || min == 60L) R.string.mute_left_one else R.string.mute_left, Durations.text(min, w))

    /** "Low alerts muted · 12 min left"; [leftMin] null = no time (the muted notification's title, a mute without an end). */
    fun sideMuted(ctx: Context, kind: String, leftMin: Long?, w: Words = Words.current): String {
        val line = ctx.getString(R.string.mute_side_muted, side(ctx, kind))
        return leftMin?.let { ctx.getString(R.string.mute_with_left, line, left(ctx, it, w)) } ?: line
    }

    /** [sideMuted] for a mute ending at [until] seen at [now]. */
    fun sideMutedUntil(ctx: Context, kind: String, until: Long, now: Long, w: Words = Words.current): String =
        sideMuted(ctx, kind, if (until >= AlertEngine.NEVER) null else leftMin(until, now), w)

    /** "Low reminders every 30 min". */
    fun sideLess(ctx: Context, kind: String, intervalMin: Int, w: Words = Words.current): String =
        ctx.getString(R.string.mute_side_less, side(ctx, kind), Durations.text(intervalMin, w))

    /** Mute alerts for a time: "Alerts muted · 1 h 12 min left". */
    fun allTimed(ctx: Context, leftMin: Long, w: Words = Words.current): String =
        ctx.getString(R.string.mute_with_left, ctx.getString(R.string.mute_all_on), left(ctx, leftMin, w))

    /** Mute alerts without an end: "Alerts muted until you turn it off". */
    fun allForever(ctx: Context): String = ctx.getString(R.string.mute_all_forever)

    /** Mute alerts' state line for its end [until] seen at [now]. */
    fun all(ctx: Context, until: Long, now: Long, w: Words = Words.current): String =
        if (until >= AlertEngine.NEVER) allForever(ctx) else allTimed(ctx, leftMin(until, now), w)

    /** The muted notification's title: "Low alerts muted" (the countdown is its chronometer). */
    fun notifTitle(ctx: Context, kind: String): String = sideMuted(ctx, kind, null)

    /** The muted notification's text: "Until 10:42" ([time] = the end as the clock shows it). */
    fun notifText(ctx: Context, time: String): String = ctx.getString(R.string.mute_until, time)

    /** The card's pill while every alert is muted for a time: "Muted · 1h 12m" (compact; StatusBadge draws it). */
    fun pill(leftMin: Long, w: Words = Words.current): String = w.mutedFor.format(Durations.compact(leftMin, w))

    /** The card's pill without an end: "Muted". */
    fun pillForever(w: Words = Words.current): String = w.muted

    /** One Mute alerts length: "For 30 min", "For 1 h". */
    fun choice(ctx: Context, min: Int, w: Words = Words.current): String = ctx.getString(R.string.mute_choice, Durations.text(min, w))

    /** The Mute alerts length without an end: "Until you turn it off". */
    fun choiceForever(ctx: Context): String = ctx.getString(R.string.mute_choice_forever)
}
