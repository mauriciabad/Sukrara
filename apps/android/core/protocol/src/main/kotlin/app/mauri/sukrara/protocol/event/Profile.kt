// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.Hex
import app.mauri.sukrara.protocol.event.Payload.longOrNull
import app.mauri.sukrara.protocol.event.Payload.obj
import app.mauri.sukrara.protocol.event.Payload.string
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A generated picture as numbers (PROTOCOL-v2 §Profiles). [json] is kept verbatim so a newer renderer version (`v`) is
 * forwarded unchanged; only v1 fields are interpreted.
 */
class Avatar private constructor(val json: JsonObject) {
    val version: Int get() = json.longOrNull("v")?.toInt() ?: 0
    val known: Boolean get() = version in 1..CURRENT
    val colors: Int get() = json.longOrNull("c")?.toInt() ?: 0
    val shape: String get() = json.string("s").orEmpty()
    val angle: Int get() = json.longOrNull("a")?.toInt() ?: 0
    val seed: Long get() = json.longOrNull("n") ?: 0

    /** "Turn the colours": angle + 45, seed + 1 (normalised). */
    fun turned(): Avatar = v1(colors, shape, angle + 45, seed + 1)

    fun withShape(s: String): Avatar = v1(colors, s, angle, seed)

    fun withColors(c: Int): Avatar = v1(c, shape, angle, seed)

    override fun equals(other: Any?) = other is Avatar && other.json == json

    override fun hashCode() = json.hashCode()

    override fun toString() = json.toString()

    /** One `dots` circle in unit coordinates; [alpha] 0–255. */
    data class Dot(val x: Double, val y: Double, val r: Double, val alpha: Int)

    companion object {
        const val CURRENT = 1
        private const val MAX_JSON = 512
        const val PALETTES = 8
        val SHAPES = listOf("cube", "drop", "dots", "rings", "letter")

        /** From the wire: any object with a positive integer `v` and ≤ 512 bytes of JSON; else null. */
        fun of(e: JsonElement?): Avatar? {
            val o = e as? JsonObject ?: return null
            val v = runCatching { o.longOrNull("v") }.getOrNull() ?: return null
            if (v <= 0 || o.toString().toByteArray().size > MAX_JSON) return null
            return Avatar(o)
        }

        /** A normalised v1 avatar: `c` mod 8, unknown shape → cube, `a` mod 360 down to a multiple of 15, `n` & 0x7fffffff. */
        fun v1(colors: Int, shape: String, angle: Int, seed: Long) = Avatar(
            buildJsonObject {
                put("v", 1)
                put("c", Math.floorMod(colors, PALETTES))
                put("s", shape.takeIf { it in SHAPES } ?: "cube")
                put("a", Math.floorMod(angle, 360) / 15 * 15)
                put("n", seed and 0x7fffffffL)
            },
        )

        /** v1 `dots`: five circles from `sha256("sukrara/avatar/v1/dots:" + n)` (contracts/protocol/identity/avatar-v1.json). */
        fun v1Dots(seed: Long): List<Dot> {
            val h = Hex.sha256("sukrara/avatar/v1/dots:$seed".toByteArray())
            fun u(i: Int) = (h[i].toInt() and 0xff) / 255.0
            return (0 until 5).map { k ->
                Dot(0.25 + 0.5 * u(4 * k + 1), 0.25 + 0.5 * u(4 * k + 2), 0.08 + 0.12 * u(4 * k), 90 + (110 * u(4 * k + 3)).toInt())
            }
        }
    }
}

/** A person's self-asserted name and picture. Travels only inside encrypted messages; authenticates nothing. */
data class Profile(val name: String, val avatar: Avatar?) {
    fun toJson(): JsonObject = buildJsonObject {
        put("name", name)
        put("avatar", avatar?.json ?: JsonNull)
    }

    companion object {
        const val MAX_NAME = 40

        /** An edit of [typed] over the stored [previous] name: trimmed, at most 40 characters, blank keeps [previous]. */
        fun editedName(typed: String, previous: String): String = typed.trim().ifBlank { previous }.take(MAX_NAME)

        fun of(e: JsonElement?): Profile? {
            val o = e as? JsonObject ?: return null
            return Profile(o.string("name").orEmpty().trim().take(MAX_NAME), Avatar.of(o.obj("avatar")))
        }
    }
}
