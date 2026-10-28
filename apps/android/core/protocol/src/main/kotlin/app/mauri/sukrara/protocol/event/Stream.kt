// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.event

import app.mauri.sukrara.protocol.Hex
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.event.Payload.bool
import app.mauri.sukrara.protocol.event.Payload.long
import app.mauri.sukrara.protocol.event.Payload.longOrNull
import app.mauri.sukrara.protocol.event.Payload.obj
import app.mauri.sukrara.protocol.event.Payload.requireV
import app.mauri.sukrara.protocol.event.Payload.string
import app.mauri.sukrara.protocol.event.Payload.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The stream's roster (kind 30078 signed by I, encrypted to G_n): which device keys publish data, which follower keys
 * may write treatments, the relays and the wearer's profile.
 */
data class RosterPayload(
    val epoch: Int,
    val devices: List<String>,
    val writers: List<String>,
    val relays: List<String>,
    val profile: Profile?,
)

object RosterCodec {
    fun encode(r: RosterPayload): String =
        buildJsonObject {
            put("v", Wire.V)
            put("epoch", r.epoch)
            put("devices", strings(r.devices))
            put("writers", strings(r.writers))
            put("relays", strings(r.relays))
            r.profile?.let { put("profile", it.toJson()) }
        }.toString()

    fun decode(json: String): RosterPayload {
        val o = Payload.parse(json)
        o.requireV(Wire.V)
        val devices = keys(o, "devices", required = true)
        val writers = keys(o, "writers", required = false)
        return RosterPayload(o.long("epoch").toInt(), devices, writers, Wire.secureRelays(o.strings("relays")), Profile.of(o.obj("profile")))
    }

    private fun keys(o: JsonObject, key: String, required: Boolean): List<String> {
        val arr = o[key] as? JsonArray ?: if (required) throw InvalidPayload("$key must be hex pubkeys") else return emptyList()
        return arr.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString && Hex.isKey(p.content) }?.content ?: throw InvalidPayload("$key must be hex pubkeys") }
    }
}

/**
 * The wearer's mirrored settings (PROTOCOL-v2 §Settings). [graph] and [alerts] are the schema's `"mirror": true`
 * rows under their wire keys; their meaning and coercion belong to the settings layer, so they stay JSON here.
 */
data class SettingsPayload(val u: Long, val graph: JsonObject, val alerts: JsonObject) {
    override fun toString() = "SettingsPayload(u=$u)"
}

object SettingsCodec {
    fun encode(s: SettingsPayload): String =
        buildJsonObject {
            put("v", Wire.V)
            put("type", "settings")
            put("u", s.u)
            put("graph", s.graph)
            put("alerts", s.alerts)
        }.toString()

    fun decode(json: String): SettingsPayload {
        val o = Payload.parse(json)
        o.requireV(Wire.V)
        if (o.string("type") != "settings") throw InvalidPayload("type must be settings")
        return SettingsPayload(o.long("u"), o.obj("graph") ?: JsonObject(emptyMap()), o.obj("alerts") ?: JsonObject(emptyMap()))
    }
}

/** One read key in the owner backup: epoch [n] and its secret as nsec. */
data class BackupEpoch(val n: Int, val nsec: String) {
    override fun toString() = "BackupEpoch($n)"
}

/** A follower in the owner backup. */
data class BackupFollower(val key: String, val name: String, val avatar: Avatar?, val since: Long, val revoked: Long?)

/**
 * An invite in the owner backup: [token], [expiry], the owner's own [label] ("who is it for?"), whether it was a
 * [nearby] pairing, when it was [sentAt], which follower key [claimedBy] it, and whether the owner answered ([done]).
 */
data class BackupInvite(
    val token: String,
    val expiry: Long,
    val label: String = "",
    val nearby: Boolean = false,
    val sentAt: Long = 0,
    val claimedBy: String? = null,
    val done: Boolean = false,
)

/**
 * The owner backup (kind 30078 signed by I, encrypted to I itself): everything a new phone needs besides I. Holds
 * every epoch's secret, so it is never logged ([toString] is redacted).
 */
data class BackupPayload(
    val sid: String,
    val epochs: List<BackupEpoch>,
    val followers: List<BackupFollower>,
    val invites: List<BackupInvite>,
    val relays: List<String>,
    val writers: List<String>,
    val profile: Profile?,
) {
    override fun toString() = "BackupPayload(epochs=${epochs.map { it.n }}, followers=${followers.size})"
}

object BackupCodec {
    fun encode(b: BackupPayload): String =
        buildJsonObject {
            put("v", Wire.V)
            put("sid", b.sid)
            put("epochs", buildJsonArray { b.epochs.forEach { add(buildJsonObject { put("n", it.n); put("g", it.nsec) }) } })
            put(
                "followers",
                buildJsonArray {
                    b.followers.forEach { f ->
                        add(
                            buildJsonObject {
                                put("f", f.key)
                                put("name", f.name)
                                put("avatar", f.avatar?.json ?: JsonNull)
                                put("since", f.since)
                                f.revoked?.let { put("revoked", it) }
                            },
                        )
                    }
                },
            )
            put("invites", buildJsonArray { b.invites.forEach { add(invite(it)) } })
            put("relays", strings(b.relays))
            put("writers", strings(b.writers))
            b.profile?.let { put("profile", it.toJson()) }
        }.toString()

    fun decode(json: String): BackupPayload {
        val o = Payload.parse(json)
        o.requireV(Wire.V)
        val sid = o.string("sid")?.takeIf(Hex::isKey) ?: throw InvalidPayload("bad sid")
        val epochs = objects(o, "epochs").map { BackupEpoch(it.long("n").toInt(), it.string("g") ?: throw InvalidPayload("epoch without g")) }
        val followers = objects(o, "followers").mapNotNull { f ->
            val key = f.string("f")?.takeIf(Hex::isKey) ?: return@mapNotNull null
            BackupFollower(key, f.string("name").orEmpty(), Avatar.of(f["avatar"]), f.longOrNull("since") ?: 0, f.longOrNull("revoked"))
        }
        val invites = objects(o, "invites").mapNotNull { i ->
            val token = i.string("t") ?: return@mapNotNull null
            BackupInvite(
                token = token,
                expiry = i.longOrNull("x") ?: 0,
                label = i.string("label").orEmpty(),
                nearby = i.bool("nearby") ?: false,
                sentAt = i.longOrNull("sentAt") ?: 0,
                claimedBy = i.string("claimedBy")?.takeIf(Hex::isKey),
                done = i.bool("done") ?: false,
            )
        }
        val writers = o.strings("writers").filter(Hex::isKey)
        return BackupPayload(sid, epochs, followers, invites, Wire.secureRelays(o.strings("relays")), writers, Profile.of(o.obj("profile")))
    }

    private fun invite(i: BackupInvite) = buildJsonObject {
        put("t", i.token)
        put("x", i.expiry)
        put("label", i.label)
        put("nearby", i.nearby)
        put("sentAt", i.sentAt)
        put("claimedBy", i.claimedBy?.let(::JsonPrimitive) ?: JsonNull)
        put("done", i.done)
    }

    private fun objects(o: JsonObject, key: String): List<JsonObject> = (o[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
}

private fun strings(list: List<String>) = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }
