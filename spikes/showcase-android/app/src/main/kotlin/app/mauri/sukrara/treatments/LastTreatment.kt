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
     * guards against stacking a correction), carbs 3 h (a meal is mostly absorbed in 2–3 h; fast carbs for a low much sooner).
     */
    fun keepS(k: String): Long? = when (k) { "rapid" -> 4 * 3600L; "carbs" -> 3 * 3600L; else -> null }

    /** [tr], its mute's end ([until], epoch s; null = its kind mutes nothing in the settings). */
    data class Info(val tr: Treatment, val until: Long?) {
        /** Share of the mute window left at [now], 0 when over or none. */
        fun left(now: Long): Float {
            val u = until ?: return 0f
            val span = (u - tr.t).coerceAtLeast(1)
            return ((u - now).toFloat() / span).coerceIn(0f, 1f)
        }
    }

    /**
     * The entry for the slot at [now], or null (nothing recent: the slot shows its fallback). The treatment that mutes
     * the current alert wins ([status] Taken); otherwise the newest rapid / carbs entry still inside [keepS]
     * (a meal logged as insulin + carbs at the same minute shows the insulin).
     */
    fun of(treatments: List<Treatment>, s: AlertSettings, status: AlertStatus, now: Long): Info? {
        val tr = (status as? AlertStatus.Taken)?.treatment
            ?: treatments.filter { !it.del && it.t <= now && keepS(it.k)?.let { k -> now - it.t < k } == true }
                .maxWithOrNull(compareBy<Treatment> { it.t / 60 }.thenBy { if (it.k == "rapid") 1 else 0 })
            ?: return null
        return Info(tr, AlertStatus.muteUntil(tr, s))
    }
}
