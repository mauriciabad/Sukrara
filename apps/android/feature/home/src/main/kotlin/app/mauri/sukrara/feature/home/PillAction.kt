// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.CardStatus
import app.mauri.sukrara.core.model.TreatmentKind

/**
 * What a tap on the card's pill does: the pill looks like an action, so every pill that shows does something.
 *  - *Eat carbs* / *Take insulin* → Add treatment with that field focused, where this phone may add entries.
 *  - a side quiet after a treatment (*45m left*) or muted (*Muted*) → that side's ways out ([Quiet]).
 *  - *Muted · 1h* (Mute all) → its ways out ([MuteAll]).
 *  - *Replace sensor* (the sensor ended, the wearer) → pair the new sensor.
 */
sealed interface PillAction {
    data class Treat(
        val carbs: Boolean,
    ) : PillAction

    /** The quiet of one side: [lowSide] = lows; [taken] = a treatment quiets it (else the person muted it). */
    data class Quiet(
        val lowSide: Boolean,
        val taken: Boolean,
    ) : PillAction

    data object MuteAll : PillAction

    data object ReplaceSensor : PillAction

    data object None : PillAction

    companion object {
        /**
         * The action of the pill for [status] at [nowS]; [canAdd] = this phone may add entries; [ended] = the card shows
         * the ended sensor's *Replace sensor* pill and this phone wears it; [muteAll] = *Mute all* is on (a muted side's
         * pill then also stands for Mute all, whose Unmute is what ends it).
         */
        fun of(
            status: CardStatus,
            nowS: Long,
            canAdd: Boolean,
            ended: Boolean = false,
            muteAll: Boolean = false,
        ): PillAction =
            when {
                ended -> ReplaceSensor
                status is CardStatus.Needed -> if (canAdd) Treat(carbs = status.lowSide) else None
                status is CardStatus.Taken && status.until > nowS -> Quiet(lowSide = status.treatment == TreatmentKind.Carbs, taken = true)
                status is CardStatus.Paused && status.until > nowS && muteAll -> MuteAll
                status is CardStatus.Paused && status.until > nowS -> Quiet(lowSide = status.lowSide, taken = false)
                status is CardStatus.Muted && status.until > nowS -> MuteAll
                else -> None
            }
    }
}
