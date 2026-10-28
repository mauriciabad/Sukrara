// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.follower

import app.mauri.sukrara.core.model.storage.SecretUnreadableException
import app.mauri.sukrara.core.model.storage.SecureStore
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.RosterCodec
import app.mauri.sukrara.protocol.event.SensorLife
import app.mauri.sukrara.protocol.event.SettingsCodec
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.follow.Epoch
import app.mauri.sukrara.protocol.follow.FollowStatus
import app.mauri.sukrara.protocol.follow.FollowerState
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Everything this phone keeps about the person it follows: the protocol state [stream] (follower key F and the read keys
 * — secrets, so the whole record lives in the SecureStore and [toString] is redacted), the invite it joined with, the
 * wearer's profile, the wearer's sensor, the newest mirrored settings and the engine's own marks.
 */
data class FollowerRecord(
    val stream: FollowerState,
    val token: String,
    /** The 4 digits the wearer may be asked to type ([app.mauri.sukrara.protocol.invite.Invites.matchCode]). */
    val code: String,
    val me: Profile?,
    val wearer: Profile?,
    val joinedAt: Long,
    val requestedAt: Long = 0,
    val sensor: SensorLife? = null,
    val lastHeardAt: Long? = null,
    val historyAskedAt: Long = 0,
    val settings: SettingsPayload? = null,
) {
    /** May add entries: active and F is one of the roster's writers. */
    val canWrite: Boolean get() = stream.status == FollowStatus.Active && stream.follower.public.hex in stream.writers

    override fun toString() = "FollowerRecord(${stream.status}, epochs=${stream.epochs.values.map { it.n }})"
}

/** The record as one SecureStore entry (`follower`). Its JSON is private to this app; nothing else reads it. */
class FollowerStore(private val secure: SecureStore, private val crypto: NostrCrypto) {
    sealed interface Load {
        data object Missing : Load

        data class Loaded(val record: FollowerRecord) : Load

        /** The Keystore refused (often temporary): never treated as "not following". */
        data class Unreadable(val transient: Boolean) : Load

        /** The entry opened but isn't a record: following must start over. */
        data object Damaged : Load
    }

    suspend fun load(): Load {
        val bytes =
            try {
                secure.read(NAME)
            } catch (e: SecretUnreadableException) {
                return Load.Unreadable(e.transient)
            } ?: return Load.Missing
        return FollowerCodec.decode(crypto, bytes.decodeToString())?.let(Load::Loaded) ?: Load.Damaged
    }

    /** False when the Keystore refused the write (the caller keeps the previous record as the truth). */
    suspend fun save(record: FollowerRecord): Boolean =
        try {
            secure.write(NAME, FollowerCodec.encode(record).encodeToByteArray())
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    suspend fun delete(): Boolean =
        try {
            secure.delete(NAME)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }

    companion object {
        const val NAME = "follower"
    }
}

internal object FollowerCodec {
    private const val V = 1

    fun encode(r: FollowerRecord): String {
        val s = r.stream
        return buildJsonObject {
            put("v", V)
            put("f", s.follower.secret.revealHex())
            put("i", s.identity?.hex)
            put("status", s.status.name)
            put("sid", s.sid)
            put(
                "epochs",
                buildJsonArray {
                    s.epochs.values.forEach {
                        add(
                            buildJsonObject {
                                put("n", it.n)
                                put("g", it.key.secret.revealHex())
                            },
                        )
                    }
                },
            )
            put("devices", strings(s.devices))
            put("writers", strings(s.writers))
            put("relays", strings(s.relays))
            put("roster", s.roster?.let { JsonPrimitive(RosterCodec.encode(it)) } ?: JsonNull)
            put("rosterEpoch", s.rosterEpoch)
            put("rosterCreatedAt", s.rosterCreatedAt)
            put("sensorCreatedAt", s.sensorCreatedAt)
            put("settingsU", s.settingsU)
            put("creators", JsonObject(s.creators.mapValues { JsonPrimitive(it.value) }))
            put(
                "held",
                JsonObject(s.held.mapValues { (_, v) -> buildJsonArray { add(JsonPrimitive(v.first)); add(JsonPrimitive(v.second)) } }),
            )
            put(
                "alertsSeen",
                buildJsonArray {
                    s.alertsSeen.forEach { (k, at) ->
                        add(
                            buildJsonArray {
                                add(JsonPrimitive(k.first))
                                add(JsonPrimitive(k.second))
                                add(JsonPrimitive(k.third))
                                add(JsonPrimitive(at))
                            },
                        )
                    }
                },
            )
            put("lastKeyAt", s.lastKeyAt)
            put("revokedAt", s.revokedAt)
            put("token", r.token)
            put("code", r.code)
            put("me", r.me?.toJson() ?: JsonNull)
            put("wearer", r.wearer?.toJson() ?: JsonNull)
            put("joinedAt", r.joinedAt)
            put("requestedAt", r.requestedAt)
            put(
                "sensor",
                r.sensor?.let {
                    buildJsonObject {
                        put("s", it.start)
                        put("e", it.end)
                        put("w", it.warmupEnd)
                    }
                } ?: JsonNull,
            )
            put("lastHeardAt", r.lastHeardAt)
            put("historyAskedAt", r.historyAskedAt)
            put("settings", r.settings?.let { JsonPrimitive(SettingsCodec.encode(it)) } ?: JsonNull)
        }.toString()
    }

    /** Null for anything that isn't a whole record (a damaged entry). */
    fun decode(crypto: NostrCrypto, text: String): FollowerRecord? =
        runCatching {
            val o = Json.parseToJsonElement(text).jsonObject
            if (o.int("v") != V) return null
            val follower = SecretKey.ofHex(o.str("f")!!)?.let(crypto::keyPair) ?: return null
            val epochs =
                o["epochs"]!!.jsonArray.associate { e ->
                    val eo = e.jsonObject
                    val key = SecretKey.ofHex(eo.str("g")!!)?.let(crypto::keyPair) ?: return null
                    app.mauri.sukrara.protocol.Wire.gk(key.public) to Epoch(eo.int("n")!!, key)
                }
            val stream =
                FollowerState(
                    follower = follower,
                    identity = PublicKeyHex.parse(o.str("i")),
                    status = FollowStatus.valueOf(o.str("status")!!),
                    sid = o.str("sid")?.takeIf { PublicKeyHex.parse(it) != null },
                    epochs = epochs,
                    devices = o.strings("devices"),
                    writers = o.strings("writers"),
                    relays = o.strings("relays"),
                    roster = o.str("roster")?.let(RosterCodec::decode),
                    rosterEpoch = o.int("rosterEpoch") ?: -1,
                    rosterCreatedAt = o.long("rosterCreatedAt") ?: -1,
                    sensorCreatedAt = o.long("sensorCreatedAt") ?: -1,
                    settingsU = o.long("settingsU") ?: -1,
                    creators = o["creators"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty(),
                    held =
                        o["held"]?.jsonObject?.mapValues { (_, v) ->
                            val a = v.jsonArray
                            a[0].jsonPrimitive.longOrNull!! to a[1].jsonPrimitive.content
                        }.orEmpty(),
                    alertsSeen =
                        o["alertsSeen"]?.jsonArray?.associate { e ->
                            val a = e.jsonArray
                            Triple(a[0].jsonPrimitive.content, a[1].jsonPrimitive.booleanOrNull!!, a[2].jsonPrimitive.intOrNull!!) to
                                a[3].jsonPrimitive.longOrNull!!
                        }.orEmpty(),
                    lastKeyAt = o.long("lastKeyAt") ?: 0,
                    revokedAt = o.long("revokedAt") ?: 0,
                )
            val sensor =
                (o["sensor"] as? JsonObject)?.let { SensorLife(it.long("s")!!, it.long("e")!!, it.long("w")!!) }
            FollowerRecord(
                stream = stream,
                token = o.str("token")!!,
                code = o.str("code")!!,
                me = Profile.of(o["me"]),
                wearer = Profile.of(o["wearer"]),
                joinedAt = o.long("joinedAt") ?: 0,
                requestedAt = o.long("requestedAt") ?: 0,
                sensor = sensor,
                lastHeardAt = o.long("lastHeardAt"),
                historyAskedAt = o.long("historyAskedAt") ?: 0,
                settings = o.str("settings")?.let(SettingsCodec::decode),
            )
        }.getOrNull()

    private fun strings(list: List<String>) = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.strings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    @Suppress("unused")
    private fun JsonElement.isNull() = this is JsonNull
}
