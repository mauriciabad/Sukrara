// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core.model

import kotlinx.coroutines.flow.StateFlow

/**
 * What alerts need from the phone to ring, as one checklist: the home screen's reliability row and the
 * first-run setup's permission step render the same items in the same order. Implemented by the alert runtime /
 * monitor (it reads the permissions and channel settings); screens only render it and ask to fix an item.
 */
enum class AlertRequirement {
    Notifications,
    FullScreenIntent,
    ExactAlarm,
    BatteryExemption,

    /** The alert channels may break through Do Not Disturb and still have their importance. */
    AlertChannel,

    /** Bluetooth on and the nearby-devices permission (wearer only: the sensor talks over Bluetooth). */
    Bluetooth,
}

/** The checklist's current answers: every requirement in [AlertRequirement] order with whether it is met. */
data class AlertReadiness(
    val missing: Set<AlertRequirement> = emptySet(),
) {
    val ready: Boolean get() = missing.isEmpty()

    /** The first problem in checklist order (the one the row names), or null when alerts can ring. */
    val firstProblem: AlertRequirement? get() = AlertRequirement.entries.firstOrNull { it in missing }

    companion object {
        val Ready = AlertReadiness()
    }
}

/** The live checklist and the way to fix one item (opens the system screen for it). */
interface AlertReadinessSource {
    val readiness: StateFlow<AlertReadiness>

    /** Re-reads every item (the app calls it on resume: permissions change in the system's settings). */
    fun refresh()

    /** Opens the system screen that fixes [requirement] (notification settings, battery exemption, …). */
    fun fix(requirement: AlertRequirement)
}
