// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.treatments

import app.mauri.sukrara.alerts.AlertSettings
import app.mauri.sukrara.alerts.AlertStatus

/**
 * The last treatment that still matters, for the slot beside the value (2026-10-18 round): its amount, kind and the
 * time since it, with a ring for the time left of its alert mute ([AlertStatus.muteUntil]).
 *
 * Only rapid insulin and carbs: they act for hours and mute alerts. Long-acting insulin is left out (it mutes
 * nothing, and at one dose a day it would hold the slot all day, hiding every bolus and snack); notes have no amount.
 */
object LastTreatment {
    /**
     * How long after it was logged an entry stays in the slot (its ring is empty and grey once its mute is over):
     * rapid insulin 4 h (rapid analogues act for about 3–5 h, so the slot answers "is insulin still working?" and
     * guards against stacking a correction), carbs 3 min per gram between 30 min and 3 h (a 60 g meal is mostly
     * absorbed in 2–3 h, 15 g of fast carbs for a low in under an hour; the user: a small amount needn't stay for hours).
     */
    fun keepS(tr: Treatment): Long? = when (tr.k) {
        "rapid" -> 4 * 3600L
        "carbs" -> (tr.a * 180).toLong().coerceIn(30 * 60L, 3 * 3600L)
        else -> null
    }

    /**
     * [tr], its mute's end ([until], epoch s; null = its kind mutes nothing in the settings). [carbs]: while [tr] is
     * rapid insulin whose mute runs, the newest carbs still inside [keepS] (the slot draws both, concentric, where it
     * is big enough: [app.mauri.sukrara.graph.LastSlot]).
     */
    data class Info(val tr: Treatment, val until: Long?, val carbs: Info? = null) {
        /** Share of the mute window left at [now], 0 when over or none. */
        fun left(now: Long): Float {
            val u = until ?: return 0f
            val span = (u - tr.t).coerceAtLeast(1)
            return ((u - now).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    /**
     * The entry for the slot at [now], or null (nothing recent: the slot shows its fallback). Rapid insulin whose
     * mute is still running wins (the user: carbs logged on top of it show only once the insulin mute is over); then
     * the treatment that mutes the current alert ([status] Taken); otherwise the newest rapid / carbs entry still
     * inside [keepS] (a meal logged as insulin + carbs at the same minute shows the insulin). Running insulin carries the
     * newest carbs inside [keepS] along ([Info.carbs]).
     */
    fun of(treatments: List<Treatment>, s: AlertSettings, status: AlertStatus, now: Long): Info? {
        val live = treatments.filter { !it.del && it.t <= now }
        val tr = live.filter { it.k == "rapid" && (AlertStatus.muteUntil(it, s) ?: 0) > now }.maxByOrNull { it.t }
            ?: (status as? AlertStatus.Taken)?.treatment
            ?: live.filter { t -> keepS(t)?.let { now - t.t < it } == true }
                .maxWithOrNull(compareBy<Treatment> { it.t / 60 }.thenBy { if (it.k == "rapid") 1 else 0 })
            ?: return null
        val until = AlertStatus.muteUntil(tr, s)
        // carbs logged while the insulin works ride along in the same slot (the user, 2026-10-21)
        val carbs = if (tr.k == "rapid" && (until ?: 0) > now)
            live.filter { it.k == "carbs" && keepS(it)?.let { k -> now - it.t < k } == true }.maxByOrNull { it.t }
        else null
        return Info(tr, until, carbs?.let { Info(it, AlertStatus.muteUntil(it, s)) })
    }
}
