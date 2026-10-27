// Sukrara — https://github.com/mauriciabad/Sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.protocol.testing

import app.mauri.sukrara.protocol.KeyPair
import app.mauri.sukrara.protocol.NostrCrypto
import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.UnsignedEvent
import app.mauri.sukrara.protocol.Wire
import app.mauri.sukrara.protocol.ble.HelloProof
import app.mauri.sukrara.protocol.testing.OgsVectors.Companion.str
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every [NostrCrypto] implementation must do, checked against the OGS vectors (the Python reference's bytes).
 * Subclassed by the protocol module's JVM test crypto and by the app's adapter in `:share`.
 */
abstract class NostrCryptoContractTest {
    abstract val crypto: NostrCrypto
    private val v = OgsVectors()

    @Test fun everyVectorEventVerifiesExceptTheTamperedOne() {
        v.events.forEach { (name, ev) -> assertEquals(name, name != "live-tampered", crypto.verify(ev)) }
    }

    @Test fun dataEventsDecryptToTheirPlaintexts() {
        var checked = 0
        v.plaintexts.forEach { (name, p) ->
            val o = p.jsonObject
            if ("read_key" !in o) return@forEach
            val readKey = v.secretsByPub.getValue(o.str("read_key"))
            val plain = crypto.decrypt(readKey.secret, PublicKeyHex(o.str("author")), v.events.getValue(name).content)
            assertEquals(name, o.str("content"), plain)
            checked++
        }
        assertTrue("decrypted $checked", checked >= 10)
    }

    @Test fun wrapsOpenToTheirRumorAndSealSigner() {
        var checked = 0
        v.plaintexts.forEach { (name, p) ->
            val o = p.jsonObject
            if ("recipient" !in o) return@forEach
            val recipient = v.secretsByPub[o.str("recipient")] ?: return@forEach
            val u = crypto.unwrap(recipient.secret, v.events.getValue(name))
            assertNotNull(name, u)
            val rumor = o.getValue("rumor").jsonObject
            assertEquals(name, o.str("seal_signer"), u!!.sender.hex)
            assertEquals(name, rumor.str("content"), u.content)
            assertEquals(name, rumor.str("kind").toInt(), u.kind)
            checked++
        }
        assertTrue("unwrapped $checked", checked >= 8)
    }

    @Test fun aWrapForSomeoneElseDoesNotOpen() {
        assertNull(crypto.unwrap(v.key("X").secret, v.events.getValue("key-1")))
    }

    @Test fun aTamperedWrapDoesNotOpen() {
        val ev = v.events.getValue("key-1")
        val bad = ev.copy(content = ev.content.replaceRange(40, 41, if (ev.content[40] == 'A') "B" else "A"))
        assertFalse(crypto.verify(bad))
        assertNull(crypto.unwrap(v.key("F").secret, bad))
    }

    @Test fun unwrapRejectsARumorWhoseAuthorIsNotTheSealSigner() {
        val f = v.key("F")
        val i = v.key("I")
        assertEquals(f.public, crypto.unwrap(i.secret, handMadeWrap(f, rumorAuthor = f.public, to = i.public))?.sender)
        assertNull(crypto.unwrap(i.secret, handMadeWrap(f, rumorAuthor = v.key("X").public, to = i.public)))
    }

    /** NIP-59 by hand: a rumor claiming [rumorAuthor], sealed (kind 13) by [sealer], wrapped by a one-time key. */
    private fun handMadeWrap(sealer: KeyPair, rumorAuthor: PublicKeyHex, to: PublicKeyHex): SignedEvent {
        val content = """{"v":2,"type":"rekey","have":"00000000"}"""
        val createdAt = v.now
        val serial = buildJsonArray {
            add(JsonPrimitive(0))
            add(JsonPrimitive(rumorAuthor.hex))
            add(JsonPrimitive(createdAt))
            add(JsonPrimitive(Wire.KIND_TO_OWNER))
            add(JsonArray(emptyList()))
            add(JsonPrimitive(content))
        }.toString()
        val id = MessageDigest.getInstance("SHA-256").digest(serial.toByteArray()).joinToString("") { "%02x".format(it) }
        val rumor = buildJsonObject {
            put("id", id)
            put("pubkey", rumorAuthor.hex)
            put("created_at", createdAt)
            put("kind", Wire.KIND_TO_OWNER)
            put("tags", JsonArray(emptyList()))
            put("content", content)
        }.toString()
        val seal = crypto.sign(sealer, UnsignedEvent(13, crypto.encrypt(sealer.secret, to, rumor), emptyList(), createdAt))
        val oneTime = crypto.generate()
        return crypto.sign(oneTime, UnsignedEvent(Wire.KIND_WRAP, crypto.encrypt(oneTime.secret, to, seal.toJson()), listOf(listOf("p", to.hex)), createdAt))
    }

    @Test fun signedEventsVerifyAndKeepTheirFields() {
        val d = v.key("D")
        val ev = crypto.sign(d, UnsignedEvent(Wire.KIND_LIVE, "x\"y\n", listOf(listOf("gk", "a10d4e6e")), 1_792_087_200))
        assertTrue(crypto.verify(ev))
        assertEquals(d.public.hex, ev.pubkey)
        assertEquals(1_792_087_200, ev.createdAt)
        assertEquals("x\"y\n", ev.content)
    }

    @Test fun nsecMatchesTheVectorAndRoundTrips() {
        val g1 = v.key("G1")
        assertEquals(v.derivations.str("nsec(G1)"), crypto.nsec(g1.secret))
        assertEquals(g1.secret, crypto.parseNsec(crypto.nsec(g1.secret)))
        assertNull(crypto.parseNsec("nsec1notakey"))
        assertEquals(g1.public, crypto.keyPair(g1.secret)!!.public)
    }

    @Test fun ncryptsecRoundTripsAndRefusesAWrongPassword() {
        val i = v.key("I")
        val code = crypto.ncryptsec(i.secret, "correct horse", 16, 1)
        assertTrue(code.startsWith("ncryptsec1"))
        assertEquals(i.secret, crypto.parseNcryptsec(code, "correct horse"))
        assertNull(crypto.parseNcryptsec(code, "wrong"))
    }

    @Test fun helloProofFromTheVectorIdentifiesTheFollowerAndDevice() {
        val h = v.root.getValue("ble").jsonObject.getValue("hello_id").jsonObject
        val sig = h.getValue("hello").let { (it as JsonArray)[1].jsonObject.str("id") }
        val f = PublicKeyHex(h.str("follower"))
        val found = HelloProof.identify(crypto, sig, h.str("sid"), h.str("nonce"), listOf(v.key("W").public, f))
        assertEquals(f to h.str("dev"), found)
        val mine = HelloProof.sign(crypto, v.key("F"), h.str("sid"), h.str("nonce"), "phone")
        assertEquals(f to "phone", HelloProof.identify(crypto, mine, h.str("sid"), h.str("nonce"), listOf(f)))
        assertNull(HelloProof.identify(crypto, mine, h.str("sid"), "00", listOf(f)))
    }
}
