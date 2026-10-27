// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.Hex
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Payload.long
import app.mauri.sukrara.protocol.event.Payload.obj
import app.mauri.sukrara.protocol.event.Payload.requireV
import app.mauri.sukrara.protocol.event.Payload.string
import app.mauri.sukrara.protocol.event.Payload.strings
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The private messages inside NIP-59 gift wraps (PROTOCOL-v2 §Events, rumor kinds 30500 / 30501 / 30502). */
sealed interface Rumor {
    val kind: Int
    val type: String

    /** Follower → owner (30500): asks for access with the invite token, self-asserted profile. */
    data class Request(val token: String, val profile: Profile?) : Rumor {
        override val kind = Wire.KIND_TO_OWNER
        override val type = "request"
    }

    /** Follower → owner (30500): a later change of name / picture. */
    data class ProfileUpdate(val profile: Profile) : Rumor {
        override val kind = Wire.KIND_TO_OWNER
        override val type = "profile"
    }

    /** Follower → owner (30500): saw an unknown `gk`. */
    data class Rekey(val have: String) : Rumor {
        override val kind = Wire.KIND_TO_OWNER
        override val type = "rekey"
    }

    /** Follower → owner (30500): hours missing since [since] (hour start). */
    data class History(val since: Long) : Rumor {
        override val kind = Wire.KIND_TO_OWNER
        override val type = "history"
    }

    /** Owner → follower (30501): the read key of [epoch] (as nsec), the stream id and the publishing devices. */
    data class KeyDelivery(val sid: String, val epoch: Int, val readKeyNsec: String, val devices: List<String>, val relays: List<String>) : Rumor {
        override val kind = Wire.KIND_TO_FOLLOWER
        override val type = "key"

        override fun toString() = "KeyDelivery(epoch=$epoch, devices=${devices.size})"
    }

    /** Owner → follower (30501): courtesy notice; security comes from rotation. */
    data object Revoked : Rumor {
        override val kind = Wire.KIND_TO_FOLLOWER
        override val type = "revoked"
    }

    /** Owner → follower (30501): another follower's request had already claimed the invite. */
    data object InviteUsed : Rumor {
        override val kind = Wire.KIND_TO_FOLLOWER
        override val type = "invite-used"
    }

    /** Owner → follower (30501): the owner denied the request. */
    data object Declined : Rumor {
        override val kind = Wire.KIND_TO_FOLLOWER
        override val type = "declined"
    }

    /** The invite (30502, v 3), wrapped to the invite key: expiry, relays and the wearer's profile. */
    data class Invite(val expiry: Long, val relays: List<String>, val profile: Profile?) : Rumor {
        override val kind = Wire.KIND_INVITE
        override val type = "invite"
    }
}

object RumorCodec {
    fun encode(r: Rumor): String =
        buildJsonObject {
            put("v", if (r is Rumor.Invite) Wire.INVITE_V else Wire.V)
            put("type", r.type)
            body(r)
        }.toString()

    private fun JsonObjectBuilder.body(r: Rumor) {
        when (r) {
            is Rumor.Request -> {
                put("token", r.token)
                put("profile", r.profile?.toJson() ?: JsonNull)
            }
            is Rumor.ProfileUpdate -> put("profile", r.profile.toJson())
            is Rumor.Rekey -> put("have", r.have)
            is Rumor.History -> put("since", r.since)
            is Rumor.KeyDelivery -> {
                put("sid", r.sid)
                put("epoch", r.epoch)
                put("g", r.readKeyNsec)
                put("devices", buildJsonArray { r.devices.forEach { add(JsonPrimitive(it)) } })
                put("relays", buildJsonArray { r.relays.forEach { add(JsonPrimitive(it)) } })
            }
            is Rumor.Invite -> {
                put("x", r.expiry)
                put("r", buildJsonArray { r.relays.forEach { add(JsonPrimitive(it)) } })
                put("profile", r.profile?.toJson() ?: JsonNull)
            }
            Rumor.Revoked, Rumor.InviteUsed, Rumor.Declined -> Unit
        }
    }

    /** The rumor in [content] for rumor [kind]; null for a `type` this version doesn't know (ignored, not rejected). */
    fun decode(kind: Int, content: String): Rumor? {
        val o = Payload.parse(content)
        val type = o.string("type") ?: throw InvalidPayload("rumor without type")
        o.requireV(if (kind == Wire.KIND_INVITE) Wire.INVITE_V else Wire.V)
        return when (kind) {
            Wire.KIND_TO_OWNER -> toOwner(type, o)
            Wire.KIND_TO_FOLLOWER -> toFollower(type, o)
            Wire.KIND_INVITE -> if (type == "invite") Rumor.Invite(o.long("x"), Wire.secureRelays(o.strings("r")), Profile.of(o["profile"])) else null
            else -> null
        }
    }

    private fun toOwner(type: String, o: JsonObject): Rumor? = when (type) {
        "request" -> Rumor.Request(o.string("token") ?: throw InvalidPayload("request without token"), Profile.of(o["profile"]))
        "profile" -> Profile.of(o.obj("profile"))?.let(Rumor::ProfileUpdate) ?: throw InvalidPayload("profile update without profile")
        "rekey" -> Rumor.Rekey(o.string("have") ?: throw InvalidPayload("rekey without have"))
        "history" -> Rumor.History(o.long("since"))
        else -> null
    }

    private fun toFollower(type: String, o: JsonObject): Rumor? = when (type) {
        "key" -> {
            val sid = o.string("sid")?.takeIf(Hex::isKey) ?: throw InvalidPayload("bad sid")
            val g = o.string("g") ?: throw InvalidPayload("key without g")
            Rumor.KeyDelivery(sid, o.long("epoch").toInt(), g, o.strings("devices").filter(Hex::isKey), Wire.secureRelays(o.strings("relays")))
        }
        "revoked" -> Rumor.Revoked
        "invite-used" -> Rumor.InviteUsed
        "declined" -> Rumor.Declined
        else -> null
    }
}
