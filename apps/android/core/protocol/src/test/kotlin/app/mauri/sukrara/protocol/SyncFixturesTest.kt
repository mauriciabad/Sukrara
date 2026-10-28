// Sukrara — https://github.com/mauriciabad/sukrara
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
import app.mauri.sukrara.protocol.follow.FollowResult
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.FollowerModel
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

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
    private val g3 = throwaway("sukrara/sync/G3")
    private val names = listOf("join-readings", "treatments-writer", "settings-mirror", "rotation", "revoke-reapprove")

    /**
     * One fixture step: feed [event] and expect [expect] (and, when [rekey] is set, a rekey request whose `have` is that
     * gk; none otherwise), or — [event] null — restart the follower: it keeps its persisted state but forgets the event
     * ids it has seen and its rekey throttle (the Mac keeps those in memory only), so a relay replay is judged again.
     */
    private data class Step(val name: String, val event: SignedEvent?, val expect: String? = null, val rekey: String? = null)

    private fun step(name: String, event: SignedEvent, expect: String, rekey: String? = null) = Step(name, event, expect, rekey)

    private fun restart(name: String) = Step(name, null)

    private fun throwaway(seed: String) = crypto.keyPair(SecretKey.ofBytes(Hex.sha256(seed.toByteArray()))!!)!!

    private fun keyTo(epoch: Int, g: KeyPair, devices: List<KeyPair>) =
        f.gift(
            i,
            fol.public,
            Rumor.KeyDelivery(v.sid, epoch, crypto.nsec(g.secret), devices.map { it.public.hex }, relays),
            now - 200 + epoch,
            now + Wire.TTL_KEY_DELIVERY,
        )

    private fun roster(epoch: Int, g: KeyPair, devices: List<KeyPair>, writers: List<KeyPair> = emptyList(), at: Long = now - 100) =
        f.roster(i, g.public, v.sid, RosterPayload(epoch, devices.map { it.public.hex }, writers.map { it.public.hex }, relays, null), at)

    private fun live(dev: KeyPair, g: KeyPair, t: Long, sgv: Int, rate: Double?, sensor: SensorLife? = null) =
        f.live(dev, g.public, LivePayload(WireReading(t, sgv, rate, "libre3"), sensor), t + 5)

    private fun tr(id: String, kind: String, a: Double, u: Long, origin: String, deleted: Boolean = false) =
        TreatmentPayload(id, kind, a, now - 600, "", u, deleted, origin)

    /** The named sequence in feeding order. */
    private fun sequence(name: String): List<Step> {
        val hour = Wire.hourOf(now) - Wire.HOUR
        val sensor = SensorLife(now - 3 * Wire.DAY, now + 12 * Wire.DAY, now - 3 * Wire.DAY + 3600)
        val base =
            listOf(
                step("key-1", keyTo(1, g1, listOf(d)), "accepted:key"),
            )
        return when (name) {
            "join-readings" -> {
                base +
                    listOf(
                        step("roster-1", roster(1, g1, listOf(d)), "accepted:roster"),
                        step(
                            "chunk",
                            f.chunk(
                                d,
                                g1.public,
                                v.sid,
                                hour,
                                listOf(WireReading(hour + 60, 110, 0.5, "libre3"), WireReading(hour + 360, 115, null, "libre3")),
                                now - 50,
                            ),
                            "accepted:chunk",
                        ),
                        step("live", live(d, g1, now - 30, 121, -1.25, sensor), "accepted:live"),
                        step("live-again", live(d, g1, now - 30, 121, -1.25, sensor), "accepted:live"),
                        step(
                            "sensor-new",
                            f.live(d, g1.public, LivePayload(null, SensorLife(now - 60, now + 15 * Wire.DAY, now + 3540)), now - 10),
                            "accepted:sensor",
                        ),
                        step("live-outsider", live(v.key("X"), g1, now - 20, 300, null), "rejected:author-not-device"),
                    )
            }

            "treatments-writer" -> {
                base +
                    listOf(
                        step("roster-writer", roster(1, g1, listOf(d), listOf(w)), "accepted:roster"),
                        step(
                            "owner-carbs",
                            f.treatment(d, g1.public, v.sid, tr(ID_A, "carbs", 30.0, 1000, "phone"), now - 90),
                            "accepted:treatment",
                        ),
                        step(
                            "writer-rapid",
                            f.treatment(w, g1.public, v.sid, tr(ID_B, "rapid", 4.5, 2000, "follower"), now - 80),
                            "accepted:treatment",
                        ),
                        step(
                            "writer-edit",
                            f.treatment(w, g1.public, v.sid, tr(ID_B, "rapid", 5.0, 3000, "follower"), now - 70),
                            "accepted:treatment",
                        ),
                        step(
                            "writer-hijack",
                            f.treatment(w, g1.public, v.sid, tr(ID_A, "carbs", 90.0, 4000, "follower"), now - 60),
                            "rejected:writer-not-creator",
                        ),
                        step(
                            "owner-delete",
                            f.treatment(d, g1.public, v.sid, tr(ID_A, "carbs", 30.0, 5000, "phone", deleted = true), now - 50),
                            "accepted:treatment",
                        ),
                        step(
                            "owner-older",
                            f.treatment(d, g1.public, v.sid, tr(ID_A, "carbs", 25.0, 900, "phone"), now - 40),
                            "ignored:treatment-older",
                        ),
                    )
            }

            "settings-mirror" -> {
                base +
                    listOf(
                        step("roster-1", roster(1, g1, listOf(d), listOf(w)), "accepted:roster"),
                        step("settings-100", settings(d, 100, 70), "accepted:settings"),
                        step("settings-90", settings(d, 90, 60), "ignored:settings-older"),
                        step("settings-writer", settings(w, 500, 50), "rejected:author-not-device"),
                        step("settings-120", settings(d, 120, 75), "accepted:settings"),
                    )
            }

            "rotation" -> {
                base +
                    listOf(
                        step("roster-1", roster(1, g1, listOf(d)), "accepted:roster"),
                        step("live-1", live(d, g1, now - 300, 140, 0.0), "accepted:live"),
                        step("key-2", keyTo(2, g2, listOf(d2)), "accepted:key"),
                        // the replaced phone refreshes its epoch-1 roster: the epoch-2 key outranks it
                        step("roster-1-refresh", roster(1, g1, listOf(d), at = now - 60), "ignored:roster-older"),
                        step("live-before-roster-2", live(d2, g2, now - 55, 139, 0.0), "accepted:live"),
                        step("roster-2", roster(2, g2, listOf(d2), at = now - 50), "accepted:roster"),
                        step("live-old-device", live(d, g2, now - 40, 141, 0.0), "rejected:author-not-device"),
                        step("live-old-epoch", live(d, g1, now - 35, 142, 0.0), "rejected:author-not-device"),
                        step("live-new-device", live(d2, g2, now - 30, 143, 0.0), "accepted:live"),
                        step("roster-1-late", roster(1, g1, listOf(d), at = now - 20), "ignored:roster-older"),
                    )
            }

            "revoke-reapprove" -> {
                revokeReapprove()
            }

            else -> {
                error(name)
            }
        }
    }

    /**
     * The wearer removes F (revoked notice), then invites it again with the same F key (key delivery of the epoch made
     * by the removal). Relays keep both wraps 30 days, so after a restart they come back in any order: the older revoked
     * notice must not silence F again; a newer one does. Also: no rekey for an old backfill under an unknown epoch, a
     * rekey (`have` = newest held gk, ≤ 1 per gk per 5 min) for a newer event, and an entry by a writer the roster doesn't
     * list yet is judged again once the roster adds it.
     */
    private fun revokeReapprove(): List<Step> {
        val hour = Wire.hourOf(now) - Wire.HOUR
        val revoked = f.gift(i, fol.public, Rumor.Revoked, now - 150, now + Wire.TTL_KEY_DELIVERY)
        val revokedAgain = f.gift(i, fol.public, Rumor.Revoked, now - 10, now + Wire.TTL_KEY_DELIVERY)
        val key1 = keyTo(1, g1, listOf(d))
        val key2 =
            f.gift(
                i,
                fol.public,
                Rumor.KeyDelivery(v.sid, 2, crypto.nsec(g2.secret), listOf(d.public.hex), relays),
                now - 90,
                now + Wire.TTL_KEY_DELIVERY,
            )
        val writerEntry = f.treatment(w, g2.public, v.sid, tr(ID_B, "rapid", 2.0, 2000, "follower"), now - 70)
        val liveUnknown = live(d, g3, now - 20, 130, 0.5)
        return listOf(
            step("key-1", key1, "accepted:key"),
            step("roster-1", roster(1, g1, listOf(d)), "accepted:roster"),
            step("live-1", live(d, g1, now - 300, 120, 0.0), "accepted:live"),
            step("revoked", revoked, "accepted:revoked"),
            step("live-while-revoked", live(d, g2, now - 140, 125, 0.0), "ignored:revoked"),
            step("key-2-reapproved", key2, "accepted:key"),
            step("roster-2", roster(2, g2, listOf(d), at = now - 85), "accepted:roster"),
            step("live-2", live(d, g2, now - 80, 118, -0.5), "accepted:live"),
            step("writer-entry", writerEntry, "rejected:author-not-in-roster"),
            restart("restart"),
            step("revoked", revoked, "ignored:revoked-before-latest-key"),
            step("key-1", key1, "ignored:key-known"),
            step("key-2-reapproved", key2, "ignored:key-known"),
            step("roster-2-writer", roster(2, g2, listOf(d), listOf(w), at = now - 60), "accepted:roster"),
            step("writer-entry", writerEntry, "accepted:treatment"),
            step(
                "backfill-unknown-epoch",
                f.chunk(d, g3.public, v.sid, hour, listOf(WireReading(hour + 60, 99, null, "libre3")), now - 120),
                "ignored:unknown-epoch",
            ),
            step("live-unknown-epoch", liveUnknown, "ignored:unknown-epoch", rekey = Wire.gk(g2.public)),
            step("live-unknown-epoch", liveUnknown, "ignored:unknown-epoch"),
            step("revoked-again", revokedAgain, "accepted:revoked"),
            restart("restart-2"),
            step("key-2-reapproved", key2, "ignored:key-known"),
            step("revoked-again", revokedAgain, "ignored:revoked-already"),
            step("revoked", revoked, "ignored:revoked-before-latest-key"),
            step("live-after-revoked-again", live(d, g2, now - 5, 117, 0.0), "ignored:revoked"),
        )
    }

    /** Every `"mirror": true` row of contracts/settings/settings.json, at its wire key (checked by [everyMirroredSettingIsInTheFixture]). */
    private fun settings(dev: KeyPair, u: Long, low: Int): SignedEvent {
        val graph =
            Json
                .parseToJsonElement(
                    """{"veryLow":54,"low":$low,"tight":140,"high":180,"veryHigh":250,"extremelyHigh":300,"defaultHours":12,"ringCount":2,
               "slots":"TIR:86400:1,TIR:604800:1,GMI:1209600:1","ringRange":"TIGHT","ringOrder":"TIGHT,TARGET,HIGH,VERY_HIGH,VERY_LOW,LOW",
               "ringMarks":true,"side":false,"marks":"rapid:point+line+amount+time,long:point+amount+time,carbs:point,note:point",
               "columnCrown":true,"lines":"100,120","gridHours":3,"lastPlace":"END","lastElse":"GMI:1209600:1","countdownHours":8}""",
                ).jsonObject
        val alerts =
            Json
                .parseToJsonElement(
                    """{"alerts":{"very_low":{"on":true,"mgdl":55,"repeatMin":5},"low":{"on":true,"mgdl":$low,"repeatMin":15},
               "high":{"on":true,"mgdl":180,"repeatMin":30},"very_high":{"on":true,"mgdl":250,"repeatMin":15},
               "extremely_high":{"on":false,"mgdl":300,"repeatMin":0},"falling_fast":{"on":true,"rate":2.5,"repeatMin":10},
               "rising_fast":{"on":false,"rate":2.0,"repeatMin":0}},
               "muteAfterInsulin":{"on":true,"min":60},"muteFallingHigh":{"on":false,"min":20},"muteAfterCarbs":{"on":true,"min":15}}""",
                ).jsonObject
        return f.settings(dev, g1.public, v.sid, SettingsPayload(u, graph, alerts), now - 100 + u / 10)
    }

    /** The settings fixture carries every mirrored setting, so both platforms check each one wearer → follower. */
    @Test fun everyMirroredSettingIsInTheFixture() {
        val got =
            ContractFiles
                .json("protocol/sync/settings-mirror.json")
                .getValue("expected")
                .jsonObject
                .getValue("settings")
                .jsonObject
        val kinds = listOf("very_low", "low", "high", "very_high", "extremely_high", "falling_fast", "rising_fast")
        val paths =
            ContractFiles
                .json("settings/settings.json")
                .getValue("settings")
                .jsonArray
                .map { it.jsonObject }
                .filter { it["mirror"]?.jsonPrimitive?.content == "true" }
                .map { it.str("wire") }
                .flatMap { w -> if ("{kind}" in w) kinds.map { w.replace("{kind}", it) } else listOf(w) }
        val missing =
            paths.filter { path ->
                path.split('.').fold(got as JsonElement?) { cur, key -> (cur as? JsonObject)?.get(key) } == null
            }
        assertEquals(emptyList<String>(), missing)
    }

    private fun follower() = FollowerModel(crypto, FollowerState(follower = fol, identity = i.public))

    private fun generate(name: String): JsonObject {
        val seq = sequence(name)
        val m = follower()
        seq.forEach { s ->
            if (s.event == null) {
                m.restart()
            } else {
                val r = m.feed(s.event, now)
                assertEquals("$name/${s.name}", s.expect, r.code)
                assertEquals("$name/${s.name} rekey", s.rekey, (r as? FollowResult.Ignored)?.rekey?.have)
            }
        }
        return buildJsonObject {
            put("v", 1)
            put("about", "Owner → follower sequence '$name' (contracts/protocol/sync/README.md). Throwaway keys, synthetic data.")
            put(
                "keys",
                buildJsonObject {
                    listOf("I" to i, "D" to d, "D2" to d2, "W" to w, "F" to fol, "G1" to g1, "G2" to g2, "G3" to g3).forEach { (n, k) ->
                        put(
                            n,
                            buildJsonObject {
                                put("sec", k.secret.revealHex())
                                put("pub", k.public.hex)
                            },
                        )
                    }
                },
            )
            put("sid", v.sid)
            put("follower", "F")
            put("identity", i.public.hex)
            put("now", now)
            put("events", buildJsonObject { seq.forEach { s -> s.event?.let { put(s.name, Json.parseToJsonElement(it.toJson())) } } })
            put(
                "steps",
                buildJsonArray {
                    seq.forEach { s ->
                        add(
                            buildJsonObject {
                                if (s.event == null) {
                                    put("restart", true)
                                } else {
                                    put("event", s.name)
                                    put("expect", s.expect)
                                    s.rekey?.let { put("rekey", it) }
                                }
                            },
                        )
                    }
                },
            )
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
                if ("restart" in step) {
                    m.restart()
                    return@forEach
                }
                val ev = SignedEvent.parse(events.getValue(step.str("event")).toString())!!
                val r = m.feed(ev, fx.str("now").toLong())
                assertEquals("$name/${step.str("event")}", step.str("expect"), r.code)
                assertEquals(
                    "$name/${step.str("event")} rekey",
                    step["rekey"]?.let {
                        (it as JsonPrimitive).content
                    },
                    (r as? FollowResult.Ignored)?.rekey?.have,
                )
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
