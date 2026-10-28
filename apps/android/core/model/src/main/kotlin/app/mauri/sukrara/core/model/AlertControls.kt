// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

/**
 * The person's alert actions from any surface (home, settings, Active now): the same ones the alert notification
 * offers. Kinds are the contract ids (`low`, `very_high`, `no_data`…); an action acts on the kind's side (a high's
 * mute never quiets a low). Implemented by the alert runtime in `:monitor`; every call returns once it is applied and
 * saved. An unknown kind changes nothing.
 */
interface AlertControls {
    /** Mutes [kind]'s side for [minutes] from now (a new length replaces the old one: "Change time"). */
    suspend fun mute(
        kind: String,
        minutes: Int,
    )

    /** Ends a mute or a treatment / falling quiet ("Unmute", "Alert me anyway"). */
    suspend fun unmute(kind: String)

    suspend fun remindLess(kind: String)

    suspend fun remindAsUsual(kind: String)

    /** *Mute all* for [minutes]; null = until turned off. */
    suspend fun muteAll(minutes: Int?)

    suspend fun unmuteAll()
}
