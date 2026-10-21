// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.core

import java.io.File
import java.nio.ByteBuffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** BLE framing limits (P13), blinded INFO and the owner check (P12), against spec/test-vectors/ogs-draft1.json. */
class BleTest {
    private val vec = JSONObject(File("../../../spec/test-vectors/ogs-draft1.json").readText())
    private val ble = vec.getJSONObject("ble")

    private fun frame(id: Int, idx: Int, count: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(6 + payload.size).putShort(id.toShort()).putShort(idx.toShort()).putShort(count.toShort()).put(payload).array()

    @Test fun framesMatchTheVectorsAndReassemble() {
        val msg = ble.getJSONArray("message")
        val byMtu = ble.getJSONObject("frames_by_att_mtu")
        for (mtu in byMtu.keys()) {
            val want = byMtu.getJSONArray(mtu).let { a -> List(a.length()) { a.getString(it) } }
            val payload = msg.toString().toByteArray()
            val got = Ble.frames(7, payload, mtu.toInt()).map { Proto.hex(it) }
            assertEquals("frame count at MTU $mtu", want.size, got.size)
            val r = Ble.Reassembler()
            val out = want.map { r.add(Proto.unhex(it)) }
            assertTrue(out.dropLast(1).all { it == null })
            assertEquals(msg.getJSONObject(1).getString("id"), out.last()!!.getJSONObject(1).getString("id"))
        }
    }

    @Test fun idleBufferIsDropped() {
        var t = 0L
        val r = Ble.Reassembler { t }
        val p = """["HELLO",{"v":2}]""".toByteArray()
        assertNull(r.add(frame(1, 0, 2, p.copyOfRange(0, 5))))
        t = Ble.IDLE_MS + 1
        assertNull("the rest of a message idle > 30 s doesn't complete it", r.add(frame(1, 1, 2, p.copyOfRange(5, p.size))))
        assertEquals(1, r.dropped)
    }

    @Test fun oversizedMessageIsDropped() {
        val r = Ble.Reassembler()
        val chunk = ByteArray(500) { 'x'.code.toByte() }
        val count = Ble.MAX_MESSAGE / 500 + 2
        var out: JSONArray? = null
        for (i in 0 until count) out = r.add(frame(9, i, count, chunk)) ?: out
        assertNull(out)
        assertEquals(1, r.dropped)
    }

    @Test fun atMostSixteenBuffers() {
        val r = Ble.Reassembler()
        for (id in 0 until Ble.MAX_BUFFERS + 4) r.add(frame(id, 0, 2, "[".toByteArray()))
        // the oldest buffers went: completing message 0 now yields nothing, the newest still completes
        assertNull(r.add(frame(0, 1, 2, "\"HELLO\",{}]".toByteArray())))
        assertEquals("HELLO", r.add(frame(Ble.MAX_BUFFERS + 3, 1, 2, "\"HELLO\",{}]".toByteArray()))!!.getString(0))
    }

    @Test fun malformedHeadersAreRefused() {
        val r = Ble.Reassembler()
        assertNull(r.header(ByteArray(5)))
        assertNull(r.header(frame(1, 2, 2)))
        assertNull(r.header(frame(1, 0, 0)))
        assertEquals(Triple(1, 0, 1), r.header(frame(1, 0, 1)))
    }

    @Test fun rateLimitPerCentral() {
        var t = 0L
        val lim = Ble.RateLimit(messages = 3, bytes = 1000, windowMs = 60_000) { t }
        repeat(3) { assertTrue(lim.message()) }
        assertFalse("4th message in the same minute", lim.message())
        t += 20_000
        assertTrue("a third of the window refills one", lim.message())
        assertTrue(lim.frame(900))
        assertFalse("byte budget", lim.frame(500))
    }

    @Test fun blindedInfoMatchesTheVectors() {
        val im = ble.getJSONObject("info_m")
        assertEquals(im.getString("m"), Ble.infoM(im.getString("sid"), im.getString("nonce")))
        val profile = Profile("Alex", null)
        val info = Ble.info("ab".repeat(32), im.getString("sid"), im.getString("nonce"), listOf("wss://x"), null, 0, profile)
        assertEquals(ble.getJSONArray("info").getJSONObject(1).toString(), info.toString())
        assertFalse("no identity without pairing", info.has("i"))
        val pairing = Ble.info("ab".repeat(32), im.getString("sid"), im.getString("nonce"), listOf("wss://x"), "tok", Proto.nowS() + 600, profile)
        assertEquals("ab".repeat(32), pairing.getString("i"))
        assertEquals("tok", pairing.getJSONObject("pair").getString("t"))
        assertEquals("deadbeef", Ble.nonceOf(JSONObject().put("nonce", "deadbeef")))
        assertNull(Ble.nonceOf(JSONObject().put("nonce", "not hex")))
    }

    @Test fun ownerCheck() {
        val im = ble.getJSONObject("info_m")
        val sid = im.getString("sid"); val n = im.getString("nonce")
        val iPub = vec.getJSONObject("keys").getJSONObject("I").getString("pub")
        val info = ble.getJSONArray("info").getJSONObject(1)
        assertEquals(Ble.Owner.OURS, Ble.owner(info, iPub, sid, listOf(n)))
        assertEquals("replayed INFO (not our nonce)", Ble.Owner.OTHER, Ble.owner(info, iPub, sid, listOf("00".repeat(16))))
        assertEquals("another stream's phone", Ble.Owner.OTHER, Ble.owner(info, iPub, "ef".repeat(32), listOf(n)))
        assertEquals("request pending, phone not pairing", Ble.Owner.UNKNOWN, Ble.owner(info, iPub, null, emptyList()))
        val pairing = ble.getJSONArray("info_pairing").getJSONObject(1)
        assertEquals(Ble.Owner.OURS, Ble.owner(pairing, iPub, null, emptyList()))
        assertEquals("another wearer pairing", Ble.Owner.OTHER, Ble.owner(pairing, "cd".repeat(32), null, emptyList()))
    }

    @Test fun expiredEventsLeaveTheBacklog() {
        val ev = { x: Long? -> JSONObject().put("tags", JSONArray().apply { if (x != null) put(JSONArray().put("expiration").put(x.toString())) }).toString() }
        assertTrue(Ble.expired(ev(100), now = 200))
        assertFalse(Ble.expired(ev(300), now = 200))
        assertFalse("no expiration tag", Ble.expired(ev(null), now = 200))
        assertEquals(100L, Ble.expiration(ev(100)))
    }

    /** B§5/B§6: the HELLO identity proof names its follower and device, and nothing else (vector `ble.hello_id`). */
    @Test fun helloIdentityProof() {
        val hi = ble.getJSONObject("hello_id")
        val sid = hi.getString("sid"); val nonce = hi.getString("nonce"); val f = hi.getString("follower")
        val sig = hi.getJSONArray("hello").getJSONObject(1).getString("id")
        val keys = vec.getJSONObject("keys")
        val others = listOf("W", "X").map { keys.getJSONObject(it).getString("pub") }
        assertEquals(hi.getString("content"), Ble.idContent(sid, nonce, hi.getString("dev")))
        assertEquals(f to "mac", Ble.identify(sig, sid, nonce, others + f))
        assertNull(Ble.identify(sig, sid, nonce, others))
        assertNull("another nonce", Ble.identify(sig, sid, "00".repeat(16), listOf(f)))
        assertNull("another sid", Ble.identify(sig, "0".repeat(32), nonce, listOf(f)))
        assertNull("not a signature", Ble.identify("zz", sid, nonce, listOf(f)))
        // the proof is a NIP-01 event signature: the vector's live event checks out with the same verifier
        val live = ble.getJSONArray("message").getJSONObject(1)
        assertTrue(Schnorr.verify(Proto.unhex(live.getString("id")), Proto.unhex(live.getString("pubkey")), Proto.unhex(live.getString("sig"))))
        assertFalse(Schnorr.verify(Proto.unhex(live.getString("id")), Proto.unhex(f), Proto.unhex(live.getString("sig"))))
    }
}
