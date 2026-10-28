// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

/**
 * What the glucose needs from the user now, for the badge under the value: the alert runtime maps its `AlertStatus`
 * here and publishes it in [AlertUiState.status]; every card surface draws that one answer (the renderer stays free of
 * `:core:alerts`). [lowSide] = the low side (very low / low / falling fast).
 */
sealed interface CardStatus {
    data object None : CardStatus

    /** Out of range, nothing covers it: "Eat carbs" / "Take insulin". */
    data class Needed(
        val lowSide: Boolean,
    ) : CardStatus

    /** A logged [treatment] (its kind and time) covers it until [until]. */
    data class Taken(
        val treatment: TreatmentKind,
        val at: Long,
        val until: Long,
    ) : CardStatus

    /** Muted on this device while out of range; [until] = [NEVER] without an end. */
    data class Paused(
        val lowSide: Boolean,
        val until: Long,
    ) : CardStatus

    /** Mute all, in range or without a current reading; [until] = [NEVER] without an end. */
    data class Muted(
        val until: Long,
    ) : CardStatus

    companion object {
        /** "Until you turn it off" (the alert engine's NEVER). */
        const val NEVER = Long.MAX_VALUE / 4
    }
}
