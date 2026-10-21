// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import app.mauri.sukrara.core.Reading
import app.mauri.sukrara.treatments.Treatment

/**
 * What the glucose needs from the user right now, for the badge under the value and the last-treatment slot.
 * Pure: the same answer on every surface (app, widgets, notification, lock screen) from the same inputs.
 *
 * Shared interface between the alert rules and the graph surfaces (2026-10-18 round): the alerts side owns the
 * rules here, the graph side only draws the result.
 */
sealed interface AlertStatus {
    /** In range (or alerts off): no badge. */
    data object None : AlertStatus

    /** Out of range and nothing logged that covers it: tell the user what to do. [kind] is a [Kind] level. */
    data class Needed(val kind: String) : AlertStatus

    /** Out of range, but a treatment's mute window covers it: faded badge until [until] (epoch s). */
    data class Taken(val kind: String, val treatment: Treatment, val until: Long) : AlertStatus

    /** Out of range, muted from the alert notification (*Mute*) until [until] (epoch s): the same faded badge. */
    data class Paused(val kind: String, val until: Long) : AlertStatus

    companion object {
        /** The level kind [r] is in at [now], or null (stale reading, in range, alerts off). */
        fun level(r: Reading?, s: AlertSettings, now: Long): String? {
            if (r == null || !s.on || now - r.t > AlertEngine.FRESH_S) return null
            return Kind.LEVELS.firstOrNull { k -> s.cfg(k).let { c -> c.on && if (k in Kind.LOW_SIDE) r.sgv < c.value else r.sgv >= c.value } }
        }

        /** When [t]'s mute window ends (epoch s), or null if this kind of treatment mutes nothing (rule off, deleted). */
        fun muteUntil(t: Treatment, s: AlertSettings): Long? = when {
            t.del -> null
            t.k == "rapid" && s.muteInsulin -> t.t + s.muteS("rapid")
            t.k == "carbs" && s.muteCarbs -> t.t + s.muteS("carbs")
            else -> null
        }

        /**
         * Same rules as [AlertEngine]: carbs cover very low and low, rapid insulin high and very high, while their mute
         * window lasts (`t ≤ now < t + min`); extremely high is never covered. Otherwise [paused] (side → until, this
         * device's *Mute* of the side or *Mute all*, [AlertEngine.NEVER] = no end) covers every level kind, very low and
         * extremely high included (round f30).
         */
        fun of(r: Reading?, s: AlertSettings, treatments: List<Treatment>, now: Long, paused: Map<String, Long> = emptyMap()): AlertStatus {
            val k = level(r, s, now) ?: return None
            val by = s.mutedBy(k)
            val cover = by?.let { b -> treatments.filter { it.k == b && it.t <= now }.mapNotNull { t -> muteUntil(t, s)?.let { t to it } }
                .filter { it.second > now }.maxByOrNull { it.first.t } }
            if (cover != null) return Taken(k, cover.first, cover.second)
            val until = paused[Kind.side(k)]?.takeIf { it > now }
            return if (until != null) Paused(k, until) else Needed(k)
        }
    }
}
