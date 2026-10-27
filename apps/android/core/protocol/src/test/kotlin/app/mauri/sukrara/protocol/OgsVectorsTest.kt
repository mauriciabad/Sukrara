// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol

import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.event.AlertCodec
import app.mauri.sukrara.protocol.event.InvalidPayload
import app.mauri.sukrara.protocol.event.Profile
import app.mauri.sukrara.protocol.event.ReadingsCodec
import app.mauri.sukrara.protocol.event.RosterCodec
import app.mauri.sukrara.protocol.event.RumorCodec
import app.mauri.sukrara.protocol.event.TreatmentCodec
import app.mauri.sukrara.protocol.testing.OgsVectors
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** contracts/protocol/ogs/ogs-draft1.json: tags, payload bytes, BLE frames / INFO, treatment payload rules. */
class OgsVectorsTest {
    private val v = OgsVectors()
    private val d = v.derivations

    @Test fun opaqueTags() {
        assertEquals(d.str("H('cgm2:glucose:'+sid)"), Wire.glucoseC(v.sid))
        val chunk = d.getValue("d_chunk(sid, hour)").jsonObject
        assertEquals(chunk.str("d"), Wire.chunkD(v.sid, chunk.str("hour").toLong()))
        assertEquals(d.str("d_roster(sid)"), Wire.rosterD(v.sid))
        assertEquals(d.str("c_treatments(sid)"), Wire.treatmentsC(v.sid))
        assertEquals(d.str("d_backup(I)"), Wire.backupD(v.key("I").public))
        assertEquals(d.str("gk(G1)"), Wire.gk(v.key("G1").public))
        assertEquals(d.str("gk(G2)"), Wire.gk(v.key("G2").public))
        val carbs = v.plaintexts.getValue("treatment-owner").jsonObject.str("content").let(TreatmentCodec::decode)
        assertEquals(d.str("d_treatment(sid, carbs.id)"), Wire.treatmentD(v.sid, carbs.id))
    }

    /** Decode → encode gives back the reference's exact plaintext bytes: our encoders write what the PoC writes. */
    @Test fun payloadsReEncodeToTheReferenceBytes() {
        var checked = 0
        v.plaintexts.forEach { (name, p) ->
            val o = p.jsonObject
            val ev = v.events.getValue(name)
            val content = o["content"]?.jsonPrimitive?.content ?: o.getValue("rumor").jsonObject.str("content")
            val again = when {
                "rumor" in o -> RumorCodec.encode(RumorCodec.decode(o.getValue("rumor").jsonObject.str("kind").toInt(), content)!!)
                ev.kind == Wire.KIND_LIVE -> ReadingsCodec.encodeLive(ReadingsCodec.decodeLive(content))
                ev.kind == Wire.KIND_ALERT -> AlertCodec.encode(AlertCodec.decode(content))
                ev.tag("c") == Wire.glucoseC(v.sid) -> ReadingsCodec.decodeChunk(content).let { ReadingsCodec.encodeChunk(it.hour, it.readings) }
                ev.tag("c") == Wire.treatmentsC(v.sid) -> TreatmentCodec.encode(TreatmentCodec.decode(content))
                else -> RosterCodec.encode(RosterCodec.decode(content))
            }
            // The reference writes a roster without a wearer profile as `"profile":null`; the PoC (and V1) leave it out.
            assertEquals(name, content.replace(",\"profile\":null}", "}"), again)
            checked++
        }
        assertEquals(v.plaintexts.size, checked)
    }

    @Test fun bleFramesForEveryMtu() {
        val ble = v.root.getValue("ble").jsonObject
        val byMtu = ble.getValue("frames_by_att_mtu").jsonObject
        byMtu.forEach { (mtu, frames) ->
            val expected = frames.jsonArray.map { it.jsonPrimitive.content }
            val payload = expected.map { Hex.decode(it)!!.copyOfRange(BleFraming.HEADER, Hex.decode(it)!!.size) }.reduce { a, b -> a + b }
            assertEquals("mtu $mtu", expected, BleFraming.frames(ble.str("msg_id").toInt(), payload, mtu.toInt()).map(Hex::encode))
            assertEquals(ble.getValue("message"), Json.parseToJsonElement(payload.decodeToString()))
        }
    }

    @Test fun infoAndHello() {
        val ble = v.root.getValue("ble").jsonObject
        val nonce = ble.getValue("info_m").jsonObject.str("nonce")
        assertEquals(ble.getValue("info_m").jsonObject.str("m"), BleMessages.infoM(v.sid, nonce))
        assertEquals(ble.getValue("info"), json(BleMessages.info(v.sid, nonce, null, v.now)))
        val pair = (ble.getValue("info_pairing") as JsonArray)[1].jsonObject
        val pairing = BleMessages.Pairing(
            v.key("I").public,
            pair.getValue("relays").jsonArray.map { it.jsonPrimitive.content },
            pair.getValue("pair").jsonObject.str("t"),
            pair.getValue("pair").jsonObject.str("x").toLong(),
            Profile.of(pair.getValue("pair").jsonObject["profile"]),
        )
        assertEquals(ble.getValue("info_pairing"), json(BleMessages.info(v.sid, nonce, pairing, v.now)))
        assertEquals(ble.getValue("info"), json(BleMessages.info(v.sid, nonce, pairing, pairing.expiry)))
        assertEquals(ble.getValue("hello"), json(BleMessages.hello(nonce, null)))
        val hello = BleMessages.parse(BleMessages.hello(nonce, null))!!
        assertEquals("HELLO", hello.type)
        assertEquals(BleMessages.Hello(nonce, null), BleMessages.parseHello(hello.body))
        val info = (ble.getValue("info") as JsonArray)[1].jsonObject
        assertEquals(BleMessages.Owner.Ours, BleMessages.owner(info, null, v.sid, listOf(nonce)))
        assertEquals(BleMessages.Owner.Other, BleMessages.owner(info, null, v.sid, listOf("ff")))
        assertEquals(BleMessages.Owner.Unknown, BleMessages.owner(info, null, null, listOf(nonce)))
        assertEquals(BleMessages.Owner.Other, BleMessages.owner(pair, v.key("X").public, v.sid, listOf(nonce)))
    }

    @Test fun treatmentPayloadRules() {
        val tp = v.root.getValue("treatment_payloads").jsonObject
        val pen = TreatmentCodec.decode(tp.getValue("pen-entry").toString())
        assertEquals(1.5, pen.primed!!, 0.0)
        assertEquals("SYNTHETIC-PEN-1", pen.pen)
        assertEquals(tp.getValue("pen-entry"), Json.parseToJsonElement(TreatmentCodec.encode(pen)))
        tp.getValue("invalid").jsonArray.forEachIndexed { i, bad ->
            try {
                TreatmentCodec.decode(bad.toString())
                fail("invalid[$i] accepted")
            } catch (_: InvalidPayload) {
            }
        }
    }

    @Test fun expiredBacklogEventsAreNotResent() {
        val live = v.events.getValue("live")
        assertFalse(live.isExpired(v.now))
        assertTrue(live.isExpired(live.expiration!!))
    }

    private fun json(bytes: ByteArray) = Json.parseToJsonElement(bytes.decodeToString())
}
