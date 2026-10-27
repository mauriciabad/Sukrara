// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.alerts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The treatment that answers an alert (contracts/alerts/SPEC.md §2): carbs for lows, rapid insulin for highs. */
@Serializable
enum class TreatmentKind(val wire: String) {
    @SerialName("carbs") CARBS("carbs"),
    @SerialName("rapid") RAPID("rapid"),
}

/**
 * One side of the alerts (SPEC §1 "Episodes", §2 "Scope"): the unit an episode, a *Mute* and a *Remind less* act on.
 * A high's Mute never quiets a low. [mainKind] names the side when nothing of it is matching (a mute without an episode).
 */
@Serializable
enum class Side(val wire: String) {
    @SerialName("low") LOW("low"),
    @SerialName("high") HIGH("high"),
    @SerialName("falling_fast") FALLING_FAST("falling_fast"),
    @SerialName("rising_fast") RISING_FAST("rising_fast"),
    @SerialName("no_data") NO_DATA("no_data"),
    ;

    val mainKind: AlertKind
        get() = when (this) {
            LOW -> AlertKind.LOW
            HIGH -> AlertKind.HIGH
            FALLING_FAST -> AlertKind.FALLING_FAST
            RISING_FAST -> AlertKind.RISING_FAST
            NO_DATA -> AlertKind.NO_DATA
        }
}

/** Independent groups of kinds, each with at most one episode and one notification (SPEC §1). */
@Serializable
enum class AlertGroup(val wire: String) {
    @SerialName("level") LEVEL("level"),
    @SerialName("rate") RATE("rate"),
    @SerialName("no_data") NO_DATA("no_data"),
}

/**
 * The 8 alert kinds of SPEC §1. [wire] is the `k` of the settings JSON and the `alert` event; [severity] orders the
 * kinds of one side (moving to a more severe one alerts at once); [answeredBy] is the treatment that answers it
 * whatever the quiet rules say (null: no data).
 */
@Serializable
enum class AlertKind(
    val wire: String,
    val side: Side,
    val group: AlertGroup,
    val severity: Int,
    val answeredBy: TreatmentKind?,
) {
    @SerialName("very_low") VERY_LOW("very_low", Side.LOW, AlertGroup.LEVEL, 2, TreatmentKind.CARBS),
    @SerialName("low") LOW("low", Side.LOW, AlertGroup.LEVEL, 1, TreatmentKind.CARBS),
    @SerialName("high") HIGH("high", Side.HIGH, AlertGroup.LEVEL, 1, TreatmentKind.RAPID),
    @SerialName("very_high") VERY_HIGH("very_high", Side.HIGH, AlertGroup.LEVEL, 2, TreatmentKind.RAPID),
    @SerialName("extremely_high") EXTREMELY_HIGH("extremely_high", Side.HIGH, AlertGroup.LEVEL, 3, TreatmentKind.RAPID),
    @SerialName("falling_fast") FALLING_FAST("falling_fast", Side.FALLING_FAST, AlertGroup.RATE, 1, TreatmentKind.CARBS),
    @SerialName("rising_fast") RISING_FAST("rising_fast", Side.RISING_FAST, AlertGroup.RATE, 1, TreatmentKind.RAPID),
    @SerialName("no_data") NO_DATA("no_data", Side.NO_DATA, AlertGroup.NO_DATA, 1, null),
    ;

    val isLevel: Boolean get() = group == AlertGroup.LEVEL

    /** Lows alert strictly below their threshold (low 70 alerts at 69); the other levels at or above it. */
    val isBelowThreshold: Boolean get() = side == Side.LOW

    companion object {
        /** Level kinds by severity: the first that matches is the one active level alert. */
        val LEVELS_BY_SEVERITY = listOf(VERY_LOW, EXTREMELY_HIGH, VERY_HIGH, LOW, HIGH)

        /** The level kinds a falling-back-into-range mute can quiet (extremely high never). */
        val FALLING_MUTABLE = setOf(HIGH, VERY_HIGH)

        fun fromWire(wire: String): AlertKind? = entries.firstOrNull { it.wire == wire }
    }
}
