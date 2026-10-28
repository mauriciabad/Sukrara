// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Payload.long
import app.mauri.sukrara.protocol.event.Payload.longOrNull
import app.mauri.sukrara.protocol.event.Payload.numberOrNull
import app.mauri.sukrara.protocol.event.Payload.requireV
import app.mauri.sukrara.protocol.event.Payload.string
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A glucose alert the wearer fired or cleared (PROTOCOL-v2 §Alerts, kind 21079). [id] is random per firing and a
 * clear repeats it; [n] counts the episode's alerts; [tr] = treatments the headline acknowledges (0, 1, 2 = more).
 */
data class AlertPayload(
    val id: String,
    val kind: String,
    val fire: Boolean,
    val at: Long,
    val t: Long?,
    val sgv: Int?,
    val rate: Double?,
    val threshold: Double?,
    val n: Int,
    val tr: Int,
) {
    /** Health data: never in logs. */
    override fun toString() = "AlertPayload($kind ${if (fire) "fire" else "clear"})"
}

object AlertCodec {
    val KINDS = setOf("very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast", "no_data")

    fun encode(a: AlertPayload): String =
        buildJsonObject {
            put("v", Wire.V)
            put("type", "alert")
            put("id", a.id)
            put("k", a.kind)
            put("state", if (a.fire) "fire" else "clear")
            put("at", a.at)
            put("t", a.t?.let(::JsonPrimitive) ?: JsonNull)
            put("sgv", a.sgv?.let(::JsonPrimitive) ?: JsonNull)
            put("r", a.rate?.let(::JsonPrimitive) ?: JsonNull)
            put("th", a.threshold?.let(::threshold) ?: JsonNull)
            put("n", a.n)
            put("tr", a.tr)
        }.toString()

    /** Whole thresholds go out as integers (`"th":180`). */
    private fun threshold(th: Double) = if (th == Math.floor(th)) JsonPrimitive(th.toLong()) else JsonPrimitive(th)

    fun decode(json: String): AlertPayload {
        val o = Payload.parse(json)
        o.requireV(Wire.V)
        if (o.string("type") != "alert") throw InvalidPayload("type must be alert")
        val kind = o.string("k")?.takeIf { it in KINDS } ?: throw InvalidPayload("unknown alert kind")
        val fire = when (o.string("state")) {
            "fire" -> true
            "clear" -> false
            else -> throw InvalidPayload("state must be fire|clear")
        }
        val id = o.string("id")?.takeIf { it.isNotEmpty() } ?: throw InvalidPayload("missing id")
        return AlertPayload(
            id = id,
            kind = kind,
            fire = fire,
            at = o.long("at"),
            t = o.longOrNull("t"),
            sgv = o.longOrNull("sgv")?.toInt(),
            rate = o.numberOrNull("r"),
            threshold = o.numberOrNull("th"),
            n = o.longOrNull("n")?.toInt() ?: 1,
            tr = o.longOrNull("tr")?.toInt() ?: 0,
        )
    }
}
