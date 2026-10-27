// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import app.mauri.sukrara.protocol.WireJson

/** A payload that breaks the spec: the event carrying it is rejected with [reason]. */
class InvalidPayload(val reason: String) : Exception(reason)

/** Strict readers over decrypted JSON, after the reference's `_int` / `_num` / `_v` (5.0 is accepted as 5). */
internal object Payload {
    fun parse(json: String): JsonObject =
        runCatching { WireJson.parseToJsonElement(json).jsonObject }.getOrElse { throw InvalidPayload("not a JSON object") }

    fun JsonObject.long(key: String): Long = longOrNull(key) ?: throw InvalidPayload("missing $key")

    fun JsonObject.longOrNull(key: String): Long? = this[key]?.let { asLong(it, key) }

    fun asLong(e: JsonElement, key: String): Long? {
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive ?: throw InvalidPayload("$key must be an integer")
        if (p.isString || p.booleanOrNull != null) throw InvalidPayload("$key must be an integer")
        p.longOrNull?.let { return it }
        val d = p.doubleOrNull ?: throw InvalidPayload("$key must be an integer")
        if (d != Math.floor(d) || d.isInfinite()) throw InvalidPayload("$key must be an integer")
        return d.toLong()
    }

    fun JsonObject.number(key: String): Double = numberOrNull(key) ?: throw InvalidPayload("missing $key")

    fun JsonObject.numberOrNull(key: String): Double? {
        val e = this[key] ?: return null
        if (e is JsonNull) return null
        val p = e as? JsonPrimitive ?: throw InvalidPayload("$key must be a number")
        if (p.isString || p.booleanOrNull != null) throw InvalidPayload("$key must be a number")
        return p.doubleOrNull ?: throw InvalidPayload("$key must be a number")
    }

    fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()

    fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    fun JsonObject.requireV(want: Int) {
        if (longOrNull("v") != want.toLong()) throw InvalidPayload("unsupported v=${this["v"]}")
    }

    /** Rates on the wire: rounded to 2 decimals, JSON null when unknown. */
    fun rate(r: Double?): JsonElement = if (r == null) JsonNull else JsonPrimitive(Math.round(r * 100) / 100.0)
}
