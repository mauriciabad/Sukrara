// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.treatments

import java.time.Duration
import java.time.Instant

/**
 * What the treatment dialog needs to know about this phone's alerts (`AppGraph` answers from the alert settings and
 * the glucose state; the dialog never reads the alert engine).
 */
interface AlertHints {
    /** The latest reading is fresh and on the low side of the enabled alert rules: eating is what's needed. */
    fun lowNow(): Boolean

    /** How long a treatment quiets this phone's alerts now. */
    fun quiet(): QuietRules
}

/**
 * Minutes a treatment quiets the alerts it answers: after rapid insulin the highs, after carbs the lows; null = that
 * rule is off, alerts are off, or this phone mirrors the wearer's alerts (the wearer's own rules decide).
 */
data class QuietRules(
    val afterInsulinMin: Int?,
    val afterCarbsMin: Int?,
) {
    companion object {
        val NONE = QuietRules(null, null)
    }
}

/**
 * The dialog's quiet line: which side stays quiet for how long once these entries are saved ("Highs quiet for 45 min").
 * Minutes are what is left at [now] for entries at [at]; a side whose quiet is already over says nothing.
 */
data class QuietNote(
    val highsMin: Long?,
    val lowsMin: Long?,
) {
    companion object {
        fun of(
            rules: QuietRules,
            rapid: Boolean,
            carbs: Boolean,
            at: Instant,
            now: Instant,
        ): QuietNote? {
            fun left(
                on: Boolean,
                min: Int?,
            ): Long? {
                if (!on || min == null) return null
                return Duration.between(now, at.plus(Duration.ofMinutes(min.toLong()))).toMinutes().takeIf { it > 0 }
            }
            val note = QuietNote(left(rapid, rules.afterInsulinMin), left(carbs, rules.afterCarbsMin))
            return note.takeIf { it.highsMin != null || it.lowsMin != null }
        }
    }
}
