// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

/**
 * THE mute / remind-less state lines (contracts/alerts/copy.json `state`s), as data: which line and its arguments.
 * The platform words them from its own strings (Android resources en + es; Mac MuteCopy) and formats
 * minutes with the one duration formatter. A side is named by its main alert: very low inside a low mute is "Low".
 */
sealed interface MuteLine {
    /** "Low alerts muted · 12 min left"; [leftMin] null = no time (the muted notification's title). */
    data class SideMuted(val side: Side, val leftMin: Long?) : MuteLine

    /** "Low alerts muted after carbs · 12 min left". */
    data class SideAfter(val side: Side, val treatment: TreatmentKind, val leftMin: Long) : MuteLine

    /** "High alerts muted while falling back into range". */
    data class SideFalling(val side: Side) : MuteLine

    /** "Low reminders every 30 min". */
    data class SideLess(val side: Side, val intervalMin: Int) : MuteLine

    /** "Alerts muted · 1 h 12 min left". */
    data class AllTimed(val leftMin: Long) : MuteLine

    /** "Alerts muted until you turn it off". */
    data object AllForever : MuteLine

    /** The muted notification's text "Until 10:42" ([until] epoch s; the platform formats the clock time). */
    data class NotifUntil(val until: Long) : MuteLine

    /** The card's pill "Muted · 1h 12m" (compact duration). */
    data class Pill(val leftMin: Long) : MuteLine

    /** The card's pill without an end: "Muted". */
    data object PillForever : MuteLine

    /** A Mute all length "For 30 min". */
    data class Choice(val min: Int) : MuteLine

    /** "Until you turn it off". */
    data object ChoiceForever : MuteLine
}

object MuteCopy {
    /** The minutes a mute ending at [until] still shows at [now]: partial minutes round up ("1 min" to the end). */
    fun leftMin(until: Long, now: Long): Long = ((until - now).coerceAtLeast(0) + 59) / 60

    /**
     * When the time left of a mute ending at [until] next changes on screen, seen at [now]: the minute boundary counted
     * from its end, so every surface ticks together; null for no mute, no end or one already over.
     */
    fun nextChangeAt(until: Long, now: Long): Long? {
        if (until <= now || until >= Rules.NEVER) return null
        return until - (leftMin(until, now) - 1) * 60
    }

    /** The state line of a [QuietSide] at [now] (relaxed and not quiet: the remind-less line). */
    fun of(q: QuietSide, now: Long): MuteLine = when (q.reason) {
        QuietReason.NOTIFICATION -> sideMuted(q.kind.side, q.until, now)
        QuietReason.CARBS -> MuteLine.SideAfter(q.kind.side, TreatmentKind.CARBS, leftMin(q.until, now))
        QuietReason.INSULIN -> MuteLine.SideAfter(q.kind.side, TreatmentKind.RAPID, leftMin(q.until, now))
        QuietReason.FALLING -> MuteLine.SideFalling(q.kind.side)
        null -> MuteLine.SideLess(q.kind.side, q.relaxMin)
    }

    fun sideMuted(side: Side, until: Long, now: Long): MuteLine =
        MuteLine.SideMuted(side, if (until >= Rules.NEVER) null else leftMin(until, now))

    /** Mute all's state line for its end [until] seen at [now]. */
    fun all(until: Long, now: Long): MuteLine = if (until >= Rules.NEVER) MuteLine.AllForever else MuteLine.AllTimed(leftMin(until, now))

    /** The card's pill while Mute all is on. */
    fun pill(until: Long, now: Long): MuteLine = if (until >= Rules.NEVER) MuteLine.PillForever else MuteLine.Pill(leftMin(until, now))

    /** The Mute all choices: the presets in order, 0 = until turned off. */
    fun muteAllChoices(): List<MuteLine> = AlertLimits.MUTE_ALL.presetMinutes.map { if (it == 0) MuteLine.ChoiceForever else MuteLine.Choice(it) }
}
