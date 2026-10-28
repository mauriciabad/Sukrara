// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.feature.home

import app.mauri.sukrara.core.model.AlertQuietSide
import app.mauri.sukrara.designsystem.alerts.QuietExit

/**
 * What home asks the alert runtime to do (the same actions the alert notification offers, on this phone only). A
 * side is named by its quiet's alert kind (wire name, [AlertQuietSide.kind]).
 */
sealed interface HomeAlertAction {
    /** *Mute alerts* for [minutes]; null = until turned off. A new length replaces the old one (*Change time*). */
    data class MuteAll(
        val minutes: Int?,
    ) : HomeAlertAction

    data object UnmuteAll : HomeAlertAction

    /** *Unmute* a side the person muted, or *Alert me anyway* after a treatment / while falling back. */
    data class EndQuiet(
        val kind: String,
    ) : HomeAlertAction

    data class RemindAsUsual(
        val kind: String,
    ) : HomeAlertAction

    /** A muted side's *Change to* length: muted for [minutes] from now. */
    data class MuteSide(
        val kind: String,
        val minutes: Int,
    ) : HomeAlertAction

    companion object {
        /** The runtime action of way out [exit] of side [q]. */
        fun exit(
            q: AlertQuietSide,
            exit: QuietExit,
        ): HomeAlertAction =
            when (exit) {
                QuietExit.Unmute, QuietExit.AlertAnyway -> EndQuiet(q.kind)
                QuietExit.RemindAsUsual -> RemindAsUsual(q.kind)
            }
    }
}
