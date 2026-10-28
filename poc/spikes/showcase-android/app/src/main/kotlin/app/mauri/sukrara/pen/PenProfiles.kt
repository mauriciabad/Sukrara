// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.pen

import app.mauri.sukrara.pen.opennov.PenRead

/**
 * The pen models Sukrara reads (OpenNov: NovoPen 6 and NovoPen Echo Plus speak the same NFC protocol), like the sensor
 * families' SensorProfile: [verified] is the single switch for the red *Untested* tag and the display check
 * (PRODUCT §6). Bluetooth pens and caps (InPen, Tempo, Mallya) are a different protocol: not here.
 */
data class PenProfile(val id: String, val name: String, val verified: Boolean)

object PenProfiles {
    /** The target model (the user's pen). Verified for this round; docs/29 records what its first real read reports. */
    val ECHO_PLUS = PenProfile("echoPlus", "NovoPen Echo Plus", verified = true)
    /** NovoPen 6 (1 u steps): same protocol, never read by us. */
    val NOVOPEN_6 = PenProfile("novopen6", "NovoPen 6", verified = false)
    val ALL = listOf(ECHO_PLUS, NOVOPEN_6)

    /**
     * Part numbers seen on real pens → model. Both models report the same model string ("Novo Nordisk A/S NovoPen"),
     * so the part number is the only identity: add each one a real read reports (docs/29).
     */
    private val PARTS = mapOf<String, PenProfile>()

    fun byId(id: String?) = ALL.firstOrNull { it.id == id }

    /**
     * The model of a read: a known part number; else a half-unit dose (only the Echo Plus dials 0.5 u); else NovoPen 6
     * (untested: the display check confirms the read before anything is logged).
     */
    fun identify(r: PenRead): PenProfile =
        PARTS.entries.firstOrNull { (p, _) -> r.part.startsWith(p) }?.value
            ?: if (r.model.contains("Echo", ignoreCase = true) || r.doses.any { Math.round(it.units * 10) % 10 == 5L }) ECHO_PLUS
            else NOVOPEN_6
}
