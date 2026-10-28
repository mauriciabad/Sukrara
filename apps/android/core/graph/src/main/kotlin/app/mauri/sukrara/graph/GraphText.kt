// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.graph

import app.mauri.sukrara.graph.stats.Band
import app.mauri.sukrara.settings.SettingsSpec

/**
 * The text forms of the structured graph settings: how they are stored and sent in the wearer's settings event
 * (contracts/protocol/PROTOCOL-v2.md "Settings"; the Mac's `SettingsText` reads the same spellings). Every parser takes
 * anything and returns a value the settings accept, or null = use the default.
 */
object GraphText {
    /** "TIR:86400:1,TIR:604800:1,GMI:1209600:1" (kind:window s:faded). */
    fun slotsText(slots: List<RingSpec>): String = slots.joinToString(",", transform = ::slotText)

    fun slotText(r: RingSpec): String = "${r.kind.name}:${r.windowS}:${if (r.faded) 1 else 0}"

    /** Exactly three valid slots, else null. */
    fun parseSlots(text: String): List<RingSpec>? = text.split(",").mapNotNull(::parseSlot).takeIf { it.size == 3 }

    /** One slot ("TIR:2592000:1"); null when malformed or "" (= nothing, for `lastElse`). The period snaps to `ringWindow`. */
    fun parseSlot(text: String): RingSpec? {
        val p = text.trim().split(":")
        if (p.size < 2) return null
        val kind = SlotKind.entries.firstOrNull { it.name == p[0] } ?: return null
        val window = p[1].toDoubleOrNull() ?: return null
        return RingSpec(SettingsSpec.RING_WINDOW.coerce(window).toLong(), kind = kind, faded = p.size < 3 || p[2] != "0")
    }

    /** "TIGHT,TARGET,LOW,VERY_LOW,HIGH,VERY_HIGH": each of the six bands once, else null. */
    fun parseRingOrder(text: String): List<Band>? {
        val order = text.split(",").map { it.trim() }.mapNotNull { n -> Band.entries.firstOrNull { it.name == n } }
        return order.takeIf { it.size == Band.entries.size && it.toSet().size == Band.entries.size }
    }

    fun ringOrderText(order: List<Band>): String = order.joinToString(",") { it.name }

    /** "100,120" → distinct values the `line` format accepts, lowest first, at most its `maxItems`. */
    fun parseLines(text: String): List<Int> {
        val spec = SettingsSpec.LINE
        return text
            .split(",")
            .mapNotNull { it.trim().toDoubleOrNull() }
            .filter(spec::valid)
            .map { it.toInt() }
            .distinct()
            .sorted()
            .take(spec.maxItems)
    }

    fun linesText(lines: List<Int>): String = lines.joinToString(",")
}
