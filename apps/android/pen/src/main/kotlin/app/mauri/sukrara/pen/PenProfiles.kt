// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import app.mauri.sukrara.pen.opennov.PenRead
import kotlin.math.roundToLong

/**
 * A pen model Sukrara reads (OpenNov: NovoPen 6 and NovoPen Echo Plus speak the same NFC protocol). [verified] is the
 * single switch for logging: an unverified model's doses are never logged. Bluetooth pens and caps (InPen, Tempo,
 * Mallya) speak another protocol and aren't here.
 */
data class PenProfile(
    val id: String,
    val name: String,
    val verified: Boolean,
)

object PenProfiles {
    /** The target model (0.5u steps). */
    val ECHO_PLUS = PenProfile("echoPlus", "NovoPen Echo Plus", verified = true)

    /** NovoPen 6 (1u steps): the same protocol, never read with a real pen yet. */
    val NOVOPEN_6 = PenProfile("novopen6", "NovoPen 6", verified = false)
    val ALL = listOf(ECHO_PLUS, NOVOPEN_6)

    fun byId(id: String?): PenProfile? = ALL.firstOrNull { it.id == id }

    /**
     * The model of a read: both models report the same model string, so a half-unit dose (only the Echo Plus dials
     * 0.5u) or "Echo" in the model makes it an Echo Plus; anything else is taken as the untested NovoPen 6.
     */
    fun identify(r: PenRead): PenProfile =
        if (r.model.contains("Echo", ignoreCase = true) || r.doses.any { (it.units * 10).roundToLong() % 10 == 5L }) {
            ECHO_PLUS
        } else {
            NOVOPEN_6
        }
}
