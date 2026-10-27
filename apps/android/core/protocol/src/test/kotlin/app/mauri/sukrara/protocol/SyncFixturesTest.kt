// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.LivePayload
import app.mauri.sukrara.protocol.event.RosterPayload
import app.mauri.sukrara.protocol.event.Rumor
import app.mauri.sukrara.protocol.event.SensorLife
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.TreatmentPayload
import app.mauri.sukrara.protocol.event.WireReading
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.FollowerModel
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The JSON files in contracts/protocol/sync: owner → follower event sequences made by [EventFactory] with throwaway keys, and the
 * follower state they must leave. Android checks them here; the Mac (`SyncFixtureTests`) must pass the same files.
 * Regenerate with `-Dsukrara.regen=true` (events are re-encrypted, expectations must not change).
 */
class SyncFixturesTest {
    private val crypto = SdkTestCrypto()
    private val v = OgsVectors()
    private val f = EventFactory(crypto)
    private val i = v.key("I")
    private val d = v.key("D")
    private val w = v.key("W")
    private val g1 = v.key("G1")
    private val g2 = v.key("G2")
    private val fol = v.key("F")
    private val d2 = throwaway("sukrara/sync/D2")
    private val now = v.now
    private val relays = listOf("wss://relay.example.org")
    private val names = listOf("join-readings", "treatments-writer", "settings-mirror", "rotation")

    private fun throwaway(seed: String) = crypto.keyPair(SecretKey.ofBytes(Hex.sha256(seed.toByteArray()))!!)!!

    private fun keyTo(epoch: Int, g: KeyPair, devices: List<KeyPair>) =
        f.gift(i, fol.public, Rumor.KeyDelivery(v.sid, epoch, crypto.nsec(g.secret), devices.map { it.public.hex }, relays), now - 200 + epoch, now + Wire.TTL_KEY_DELIVERY)

    private fun roster(epoch: Int, g: KeyPair, devices: List<KeyPair>, writers: List<KeyPair> = emptyList(), at: Long = now - 100) =
        f.roster(i, g.public, v.sid, RosterPayload(epoch, devices.map { it.public.hex }, writers.map { it.public.hex }, relays, null), at)

    private fun live(dev: KeyPair, g: KeyPair, t: Long, sgv: Int, rate: Double?, sensor: SensorLife? = null) =
        f.live(dev, g.public, LivePayload(WireReading(t, sgv, rate, "libre3"), sensor), t + 5)

    private fun tr(id: String, kind: String, a: Double, u: Long, origin: String, deleted: Boolean = false) =
        TreatmentPayload(id, kind, a, now - 600, "", u, deleted, origin)

    /** The named sequence: (step name, event, expected verdict) in feeding order. */
    private fun sequence(name: String): List<Triple<String, SignedEvent, String>> {
        val hour = Wire.hourOf(now) - Wire.HOUR
        val sensor = SensorLife(now - 3 * Wire.DAY, now + 12 * Wire.DAY, now - 3 * Wire.DAY + 3600)
        val base = listOf(
            Triple("key-1", keyTo(1, g1, listOf(d)), "accepted:key"),
        )
        return when (name) {
            "join-readings" -> base + listOf(
                Triple("roster-1", roster(1, g1, listOf(d)), "accepted:roster"),
                Triple(
                    "chunk",
                    f.chunk(d, g1.public, v.sid, hour, listOf(WireReading(hour + 60, 110, 0.5, "libre3"), WireReading(hour + 360, 115, null, "libre3")), now - 50),
                    "accepted:chunk",
                ),
                Triple("live", live(d, g1, now - 30, 121, -1.25, sensor), "accepted:live"),
                Triple("live-again", live(d, g1, now - 30, 121, -1.25, sensor), "accepted:live"),
                Triple("sensor-new", f.live(d, g1.public, LivePayload(null, SensorLife(now - 60, now + 15 * Wire.DAY, now + 3540)), now - 10), "accepted:sensor"),
                Triple("live-outsider", live(v.key("X"), g1, now - 20, 300, null), "rejected:author-not-device"),
            )
            "treatments-writer" -> base + listOf(
                Triple("roster-writer", roster(1, g1, listOf(d), listOf(w)), "accepted:roster"),
                Triple("owner-carbs", f.treatment(d, g1.public, v.sid, tr(ID_A, "carbs", 30.0, 1000, "phone"), now - 90), "accepted:treatment"),
                Triple("writer-rapid", f.treatment(w, g1.public, v.sid, tr(ID_B, "rapid", 4.5, 2000, "follower"), now - 80), "accepted:treatment"),
                Triple("writer-edit", f.treatment(w, g1.public, v.sid, tr(ID_B, "rapid", 5.0, 3000, "follower"), now - 70), "accepted:treatment"),
                Triple("writer-hijack", f.treatment(w, g1.public, v.sid, tr(ID_A, "carbs", 90.0, 4000, "follower"), now - 60), "rejected:writer-not-creator"),
                Triple("owner-delete", f.treatment(d, g1.public, v.sid, tr(ID_A, "carbs", 30.0, 5000, "phone", deleted = true), now - 50), "accepted:treatment"),
                Triple("owner-older", f.treatment(d, g1.public, v.sid, tr(ID_A, "carbs", 25.0, 900, "phone"), now - 40), "ignored:treatment-older"),
            )
            "settings-mirror" -> base + listOf(
                Triple("roster-1", roster(1, g1, listOf(d), listOf(w)), "accepted:roster"),
                Triple("settings-100", settings(d, 100, 70), "accepted:settings"),
                Triple("settings-90", settings(d, 90, 60), "ignored:settings-older"),
                Triple("settings-writer", settings(w, 500, 50), "rejected:author-not-device"),
                Triple("settings-120", settings(d, 120, 75), "accepted:settings"),
            )
            "rotation" -> base + listOf(
                Triple("roster-1", roster(1, g1, listOf(d)), "accepted:roster"),
                Triple("live-1", live(d, g1, now - 300, 140, 0.0), "accepted:live"),
                Triple("key-2", keyTo(2, g2, listOf(d2)), "accepted:key"),
                // the replaced phone (or the showcase) refreshes its epoch-1 roster: the epoch-2 key outranks it
                Triple("roster-1-refresh", roster(1, g1, listOf(d), at = now - 60), "ignored:roster-older"),
                Triple("live-before-roster-2", live(d2, g2, now - 55, 139, 0.0), "accepted:live"),
                Triple("roster-2", roster(2, g2, listOf(d2), at = now - 50), "accepted:roster"),
                Triple("live-old-device", live(d, g2, now - 40, 141, 0.0), "rejected:author-not-device"),
                Triple("live-old-epoch", live(d, g1, now - 35, 142, 0.0), "rejected:author-not-device"),
                Triple("live-new-device", live(d2, g2, now - 30, 143, 0.0), "accepted:live"),
                Triple("roster-1-late", roster(1, g1, listOf(d), at = now - 20), "ignored:roster-older"),
            )
            else -> error(name)
        }
    }

    private fun settings(dev: KeyPair, u: Long, low: Int) =
        f.settings(dev, g1.public, v.sid, SettingsPayload(u, JsonObject(mapOf("low" to JsonPrimitive(low))), JsonObject(emptyMap())), now - 100 + u / 10)

    private fun follower() = FollowerModel(crypto, FollowerState(follower = fol, identity = i.public))

    private fun generate(name: String): JsonObject {
        val seq = sequence(name)
        val m = follower()
        seq.forEach { (step, ev, expect) -> assertEquals("$name/$step", expect, m.feed(ev, now).code) }
        return buildJsonObject {
            put("v", 1)
            put("about", "Owner → follower sequence '$name' (contracts/protocol/sync/README.md). Throwaway keys, synthetic data.")
            put("keys", buildJsonObject { listOf("I" to i, "D" to d, "D2" to d2, "W" to w, "F" to fol, "G1" to g1, "G2" to g2).forEach { (n, k) -> put(n, buildJsonObject { put("sec", k.secret.revealHex()); put("pub", k.public.hex) }) } })
            put("sid", v.sid)
            put("follower", "F")
            put("identity", i.public.hex)
            put("now", now)
            put("events", buildJsonObject { seq.forEach { (step, ev) -> put(step, Json.parseToJsonElement(ev.toJson())) } })
            put("steps", buildJsonArray { seq.forEach { (step, _, expect) -> add(buildJsonObject { put("event", step); put("expect", expect) }) } })
            put("expected", m.stateJson())
        }
    }

    @Test fun fixturesReplayToTheirExpectedState() {
        if (System.getProperty("sukrara.regen") == "true") {
            val dir = File(Contracts.root, "protocol/sync").apply { mkdirs() }
            names.forEach { File(dir, "$it.json").writeText(pretty.encodeToString(JsonElement.serializer(), generate(it)) + "\n") }
        }
        names.forEach { name ->
            val fx = ContractFiles.json("protocol/sync/$name.json")
            val m = follower()
            val events = fx.getValue("events").jsonObject
            fx.getValue("steps").jsonArray.forEach { s ->
                val step = s.jsonObject
                val ev = SignedEvent.parse(events.getValue(step.str("event")).toString())!!
                assertEquals("$name/${step.str("event")}", step.str("expect"), m.feed(ev, fx.str("now").toLong()).code)
            }
            assertEquals(name, fx.getValue("expected"), m.stateJson())
        }
    }

    private companion object {
        const val ID_A = "a0000000-0000-4000-8000-00000000000a"
        const val ID_B = "b0000000-0000-4000-8000-00000000000b"
        val pretty = Json { prettyPrint = true }
    }
}
