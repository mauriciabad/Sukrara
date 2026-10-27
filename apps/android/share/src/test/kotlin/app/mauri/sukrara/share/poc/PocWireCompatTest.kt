// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.poc

import app.mauri.sukrara.core.Avatar
import app.mauri.sukrara.core.Proto
import app.mauri.sukrara.core.SensorInfo
import app.mauri.sukrara.core.model.Reading
import app.mauri.sukrara.core.model.SourceId
import app.mauri.sukrara.core.testing.Contracts
import app.mauri.sukrara.owner.Epoch
import app.mauri.sukrara.owner.Follower
import app.mauri.sukrara.owner.InviteToken
import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.SecretKey
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.event.AlertPayload
import app.mauri.sukrara.protocol.event.EventFactory
import app.mauri.sukrara.protocol.event.SettingsPayload
import app.mauri.sukrara.protocol.event.TreatmentPayload
import app.mauri.sukrara.protocol.follow.FollowerState
import app.mauri.sukrara.protocol.testing.ContractFiles
import app.mauri.sukrara.protocol.testing.FollowerModel
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import app.mauri.sukrara.share.OwnerFixtures
import app.mauri.sukrara.share.OwnerFixtures.crypto
import app.mauri.sukrara.share.owner.usecase.HandleInbox
import app.mauri.sukrara.share.owner.usecase.PublishReadings
import app.mauri.sukrara.share.owner.usecase.RestoreIdentity
import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.nostrdevkit.sdk.Event
import org.nostrdevkit.sdk.Keys
import app.mauri.sukrara.core.Profile as PocProfile
import app.mauri.sukrara.core.Reading as PocReading
import app.mauri.sukrara.owner.OwnerState as PocOwnerState

/**
 * CHANGES P2: the owner's followers keep working when the owner switches from Sukrara Showcase 0.11.53 to V1 and
 * restores the identity. The 0.11.53 side is the showcase's own protocol code (Proto.kt, Derive.kt, Avatar.kt,
 * owner/OwnerState.kt compiled unchanged from poc/, see share/build.gradle.kts) plus [PocFollower].
 *
 * - [aV1FollowerAcceptsA0_11_53Wearer]: contracts/protocol/sync/poc-0.11.53-wearer.json (made by 0.11.53's builders,
 *   regenerate with `-Psukrara.regen=true`) replays through V1's StreamFollower; the Mac replays the same file.
 * - [existingFollowersKeepWorkingAfterTheRestoreIntoV1]: a 0.11.53 stream (epoch 2, followers, writer, invite) is
 *   restored by V1 from the showcase's own backup + roster; the showcase's followers accept everything V1 publishes.
 */
class PocWireCompatTest {
    private val ctx = OwnerFixtures.context()
    private val v = OgsVectors()
    private val i = v.key("I")
    private val d = v.key("D")
    private val w = v.key("W")
    private val fol = v.key("F")
    private val g1 = v.key("G1")
    private val g2 = v.key("G2")
    private val d2 = crypto.keyPair(SecretKey.ofBytes(Proto.sha256("sukrara/sync/D2".toByteArray()))!!)!!
    private val relays = listOf("wss://relay.example.org")
    private val profile = PocProfile("Ana", Avatar.of(JSONObject("""{"v":1,"c":2,"s":"drop","a":30,"n":12345}""")))

    private fun poc(k: KeyPair): Keys = Keys.parse(k.secret.revealHex())

    private fun v1(e: Event): SignedEvent = SignedEvent.parse(e.asJson())!!

    /** OwnerEngine.deliverKey (owner/OwnerEngine.kt:701-707), body field for field. */
    private fun pocKey(i: Keys, to: String, sid: String, n: Int, g: Keys, devices: List<String>, relays: List<String>): Event =
        Proto.gift(
            i, to, Proto.RUMOR_TO_FOLLOWER,
            JSONObject().put("v", 2).put("type", "key").put("sid", sid).put("epoch", n)
                .put("g", g.secretKey().toBech32()).put("devices", JSONArray(devices)).put("relays", JSONArray(relays)),
        )

    // ------------------------------------------------------------------------------------------------ A: V1 follows 0.11.53

    /** The 0.11.53 wearer's sequence: (step, event, the verdict V1 must give). Made with the showcase's builders. */
    private fun pocSequence(now: Long): List<Triple<String, Event, String>> {
        Proto.clock = { now }
        val sid = v.sid
        val hour = Proto.hourOf(now) - 3600
        val sensor = SensorInfo("", now - 3 * Proto.DAY, now + 12 * Proto.DAY, now - 3 * Proto.DAY + 3600)
        val newSensor = SensorInfo("", now - 60, now + 15 * Proto.DAY, now + 3540)
        val dK = poc(d)
        // the bodies are what 0.11.53 sends: alerts SPEC §4, Treatment.wire() (treatments/Treatments.kt:43), MirroredSettings.content
        val alert = JSONObject().put("v", 2).put("type", "alert").put("id", "00112233445566ff").put("k", "high").put("state", "fire")
            .put("at", now - 20).put("t", now - 30).put("sgv", 192).put("r", -0.4).put("th", 180).put("n", 1).put("tr", 0)
        val carbs = JSONObject().put("v", 1).put("id", "c0000000-0000-4000-8000-00000000000c").put("k", "carbs").put("a", 30.0)
            .put("t", now - 600).put("n", "").put("u", now - 600).put("del", false).put("o", "phone")
        val settings = JSONObject().put("v", 2).put("type", "settings").put("u", now - 14)
            .put("graph", JSONObject().put("low", 70).put("high", 180).put("countdownHours", 8))
            .put("alerts", JSONObject().put("alerts", JSONObject().put("low", JSONObject().put("on", true).put("mgdl", 70))))
        return listOf(
            Triple("key-1", pocKey(poc(i), fol.public.hex, sid, 1, poc(g1), listOf(d.public.hex), relays), "accepted:key"),
            Triple("roster-1", Proto.rosterEvent(poc(i), g1.public.hex, sid, 1, listOf(d.public.hex), relays, now - 100, listOf(w.public.hex), profile), "accepted:roster"),
            Triple(
                "chunk",
                Proto.chunkEvent(dK, g1.public.hex, sid, hour, listOf(PocReading(hour + 60, 110, 0.5, "libre3"), PocReading(hour + 360, 115, null, "libre3")), now - 50),
                "accepted:chunk",
            ),
            Triple("live", Proto.liveEvent(dK, g1.public.hex, PocReading(now - 30, 121, -1.25, "libre3"), now - 25, sensor), "accepted:live"),
            Triple("sensor-new", Proto.sensorEvent(dK, g1.public.hex, newSensor, now - 22), "accepted:sensor"),
            Triple("alert", Proto.alertEvent(dK, g1.public.hex, alert, now - 20), "accepted:alert"),
            Triple("treatment", Proto.treatmentEvent(dK, g1.public.hex, sid, carbs, now - 15), "accepted:treatment"),
            Triple("settings", Proto.settingsEvent(dK, g1.public.hex, sid, settings, now - 14), "accepted:settings"),
            // a phone change on 0.11.53: epoch 2 with the new device D2
            Triple("key-2", pocKey(poc(i), fol.public.hex, sid, 2, poc(g2), listOf(d2.public.hex), relays), "accepted:key"),
            Triple("roster-2", Proto.rosterEvent(poc(i), g2.public.hex, sid, 2, listOf(d2.public.hex), relays, now - 8, listOf(w.public.hex), profile), "accepted:roster"),
            Triple("live-2", Proto.liveEvent(poc(d2), g2.public.hex, PocReading(now - 5, 125, 0.0, "libre3"), now - 4, newSensor), "accepted:live"),
            Triple("roster-1-refresh", Proto.rosterEvent(poc(i), g1.public.hex, sid, 1, listOf(d.public.hex), relays, now - 3), "ignored:roster-older"),
        )
    }

    private fun v1Follower() = FollowerModel(crypto, FollowerState(follower = fol, identity = i.public))

    private fun generate(): JsonObject {
        val now = System.currentTimeMillis() / 1000   // 0.11.53's gift wraps take the wall clock for the rumor
        val seq = pocSequence(now)
        val m = v1Follower()
        seq.forEach { (step, ev, expect) -> assertEquals(step, expect, m.feed(v1(ev), now).code) }
        return buildJsonObject {
            put("v", 1)
            put("about", "A 0.11.53 wearer (Sukrara Showcase) → V1 follower, events made by the showcase's own Proto.kt (contracts/protocol/sync/README.md). Throwaway keys, synthetic data.")
            put("generator", "apps/android/share PocWireCompatTest from poc/spikes/showcase-android core/Proto.kt (0.11.53)")
            put("keys", buildJsonObject { listOf("I" to i, "D" to d, "D2" to d2, "W" to w, "F" to fol, "G1" to g1, "G2" to g2).forEach { (n, k) -> put(n, buildJsonObject { put("sec", k.secret.revealHex()); put("pub", k.public.hex) }) } })
            put("sid", v.sid)
            put("follower", "F")
            put("identity", i.public.hex)
            put("now", now)
            put("events", buildJsonObject { seq.forEach { (step, ev) -> put(step, Json.parseToJsonElement(ev.asJson())) } })
            put("steps", buildJsonArray { seq.forEach { (step, _, expect) -> add(buildJsonObject { put("event", step); put("expect", expect) }) } })
            put("expected", m.stateJson())
        }
    }

    @Test fun aV1FollowerAcceptsA0_11_53Wearer() {
        if (System.getProperty("sukrara.regen") == "true") {
            File(Contracts.root, "protocol/sync/$FIXTURE.json").writeText(pretty.encodeToString(JsonElement.serializer(), generate()) + "\n")
        }
        val fx = ContractFiles.json("protocol/sync/$FIXTURE.json")
        val now = fx.str("now").toLong()
        val events = fx.getValue("events").jsonObject
        val v1 = v1Follower()
        val showcase = PocFollower(poc(fol), i.public.hex)
        fx.getValue("steps").jsonArray.forEach { s ->
            val step = s.jsonObject.str("event")
            val json = events.getValue(step).toString()
            assertEquals(step, s.jsonObject.str("expect"), v1.feed(SignedEvent.parse(json)!!, now).code)
            // the transcribed 0.11.53 follower agrees with its own wearer (it is the reference for part B)
            assertEquals("0.11.53 follower, $step", s.jsonObject.str("expect"), showcase.feed(json))
        }
        assertEquals(fx.getValue("expected"), v1.stateJson())
    }

    @Test fun theTranscribed0_11_53FollowerRejectsWhatItRejects() {
        val now = System.currentTimeMillis() / 1000
        val seq = pocSequence(now).associate { (step, ev) -> step to ev.asJson() }
        val f = PocFollower(poc(fol), i.public.hex)
        assertEquals("accepted:key", f.feed(seq.getValue("key-1")))
        // sealed by a device key, not the identity (FollowerEngine.kt:451)
        assertEquals("rejected:not-sealed-by-identity", f.feed(pocKey(poc(d), fol.public.hex, v.sid, 3, poc(g2), listOf(d.public.hex), relays).asJson()))
        // another stream id (:457)
        assertEquals("rejected:other-sid", f.feed(pocKey(poc(i), fol.public.hex, "ff".repeat(32), 3, poc(g2), listOf(d.public.hex), relays).asJson()))
        assertEquals("accepted:roster", f.feed(seq.getValue("roster-1")))
        // a reading signed by a key outside the roster (:544)
        val outsider = Proto.liveEvent(poc(v.key("X")), g1.public.hex, PocReading(now - 30, 300, null), now - 20, null).asJson()
        assertEquals("rejected:author-not-in-roster", f.feed(outsider))
        assertTrue(!f.wouldReceive(outsider))
        // an older roster (:607)
        assertEquals("ignored:roster-older", f.feed(Proto.rosterEvent(poc(i), g1.public.hex, v.sid, 1, listOf(d.public.hex), relays, now - 500).asJson()))
    }

    // ------------------------------------------------------------------------------------------------ B: 0.11.53 follows V1

    @Test fun existingFollowersKeepWorkingAfterTheRestoreIntoV1() {
        val now = System.currentTimeMillis() / 1000
        Proto.clock = { now }
        val mac = Keys.generate()
        val offline = Keys.generate()
        val removed = Keys.generate()
        val writer = Keys.generate()
        // the showcase's own state: a phone change behind it (epoch 2), two followers, one removed, a writer, an open invite
        val showcase = PocOwnerState(
            Keys.generate(), Keys.generate(), Proto.newSid(), mutableListOf(Epoch(1, Keys.generate()), Epoch(2, Keys.generate())),
            followers = mutableListOf(
                Follower(Proto.pub(mac), "Mac", now - 40 * Proto.DAY),
                Follower(Proto.pub(offline), "Sis", now - 30 * Proto.DAY),
                Follower(Proto.pub(removed), "Old", now - 60 * Proto.DAY, revoked = now - 20 * Proto.DAY),
            ),
            invites = mutableListOf(InviteToken(Proto.newToken(), now + Proto.DAY, "Dad")),
            writers = mutableSetOf(Proto.pub(mac)),
            profile = profile,
        )
        val e2 = showcase.current
        val iPub = showcase.iPub
        // what each follower holds after its approval on the showcase (deliveries + roster as 0.11.53 sends them)
        val roster2 = Proto.rosterEvent(showcase.i, e2.pub, showcase.sid, 2, listOf(showcase.dPub), showcase.relays, now - 3600, showcase.writers.toList(), profile)
        fun approvedOnShowcase(k: Keys) = PocFollower(k, iPub).apply {
            showcase.epochs.forEach { e -> assertEquals("accepted:key", feed(pocKey(showcase.i, Proto.pub(k), showcase.sid, e.n, e.g, listOf(showcase.dPub), showcase.relays).asJson())) }
            assertEquals("accepted:roster", feed(roster2.asJson()))
        }
        val macFollower = approvedOnShowcase(mac)
        val offlineFollower = approvedOnShowcase(offline)

        // V1 restores from what the relays hold: the showcase's own backup and roster
        val identity = crypto.keyPair(SecretKey.ofHex(showcase.i.secretKey().toHex())!!)!!
        val found = listOf(v1(Proto.backupEvent(showcase.i, showcase.backupBody(), now - 3600)), v1(roster2))
        val restored = RestoreIdentity(ctx).restore(identity, found, 1, now) as RestoreIdentity.Result.Restored
        val s = restored.step.state
        assertEquals(2, restored.followers)
        assertEquals(showcase.sid, s.sid)
        assertEquals(3, s.current.n)
        val newDevice = s.device(crypto).public.hex
        assertNotEquals(showcase.dPub, newDevice)

        val removedFollower = PocFollower(removed, iPub)
        restored.step.publish.forEach { ev ->
            val json = ev.toJson()
            if (macFollower.wouldReceive(json)) assertTrue(json, macFollower.feed(json).startsWith("accepted"))
            assertTrue(removedFollower.feed(json).let { it.startsWith("ignored") || it == "accepted:revoked" })
        }
        assertEquals(3, macFollower.rosterEpoch)
        assertEquals(listOf(newDevice), macFollower.devices)
        assertEquals(listOf(Proto.pub(mac)), macFollower.writers)
        assertEquals(Proto.RELAYS, macFollower.relays)
        assertEquals("Ana", macFollower.wearerName)
        assertTrue(!macFollower.revoked && removedFollower.epochs.isEmpty())

        // V1's data after the switch: readings (live + an earlier hour's chunk), an alert, settings and a treatment
        val readings = PublishReadings(ctx)
        val data = readings.onReadings(s, listOf(reading(now - 3700, 100, null), reading(now - 60, 140, 1.25)), now) +
            readings.publishAlert(s, AlertPayload("00112233445566ff", "low", true, now, now - 60, 62, -1.0, 70.0, 1, 0), now) +
            EventFactory(crypto).settings(s.device(crypto), s.readKey(crypto).public, s.sid, SettingsPayload(now, JsonObject(mapOf("low" to JsonPrimitive(70))), JsonObject(emptyMap())), now) +
            EventFactory(crypto).treatment(s.device(crypto), s.readKey(crypto).public, s.sid, TreatmentPayload("c0000000-0000-4000-8000-00000000000c", "carbs", 30.0, now - 600, "", now - 600, false, "phone"), now)
        val verdicts = data.map { ev -> ev.toJson().let { assertTrue(it, macFollower.wouldReceive(it)); macFollower.feed(it) } }
        assertEquals(listOf("accepted:alert", "accepted:chunk", "accepted:live", "accepted:settings", "accepted:treatment"), verdicts.distinct().sorted())
        assertTrue(verdicts.toString(), verdicts.all { it.startsWith("accepted") })
        assertEquals(140 to 1.25, macFollower.readings[now - 60])
        assertEquals(100 to null, macFollower.readings[now - 3700])
        assertIntegerFields(data, s.readKey(crypto).secret.revealHex(), newDevice)

        // a follower offline during the restore missed its key: the new roster's unknown gk makes 0.11.53 ask (rekey)
        val roster3 = restored.step.publish.first { it.tag("d") == Proto.rosterD(s.sid) }.toJson()
        assertEquals("ignored:unknown-epoch", offlineFollower.feed(roster3))
        val have = offlineFollower.epochs.values.maxBy { it.n }.gk
        val rekey = Proto.gift(offline, iPub, Proto.RUMOR_TO_OWNER, JSONObject().put("v", 2).put("type", "rekey").put("have", have))
        val answer = HandleInbox(ctx)(s, v1(rekey), now).step.publish
        assertEquals(listOf("accepted:key"), answer.map { offlineFollower.feed(it.toJson()) })
        assertEquals("accepted:roster", offlineFollower.feed(roster3))
        assertEquals("accepted:live", offlineFollower.feed(data.first { it.kind == Proto.LIVE }.toJson()))
    }

    private fun reading(t: Long, mgdl: Int, rate: Double?) = Reading(Instant.ofEpochSecond(t), mgdl, rate, SourceId.Libre3)

    /**
     * The 0.11.53 Mac follower reads numbers with `as? Int` (poc/spikes/menubar NostrV2.swift): V1 must write the integer
     * fields as JSON integers, never `140.0`.
     */
    private fun assertIntegerFields(events: List<SignedEvent>, readKeyHex: String, device: String) {
        val g = Keys.parse(readKeyHex)
        events.forEach { ev ->
            val body = Proto.dec(g, device, ev.content)
            listOf("v", "t", "sgv", "h", "at", "n", "tr", "u").filter { body.opt(it) is Number }.forEach { k ->
                assertTrue("${ev.kind} $k = ${body.get(k)}", body.get(k) is Int || body.get(k) is Long)
            }
            body.optJSONArray("r")?.let { rows ->
                for (r in 0 until rows.length()) {
                    val row = rows.getJSONArray(r)
                    assertTrue("chunk row $row", row.get(0) is Int && row.get(1) is Int && (row.isNull(2) || row.get(2) is Int))
                }
            }
        }
    }

    private companion object {
        const val FIXTURE = "poc-0.11.53-wearer"
        val pretty = Json { prettyPrint = true }
    }
}
