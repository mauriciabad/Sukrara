// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.share.ble

import app.mauri.sukrara.protocol.PublicKeyHex
import app.mauri.sukrara.protocol.SignedEvent
import app.mauri.sukrara.protocol.ble.BleFraming
import app.mauri.sukrara.protocol.ble.BleMessages
import app.mauri.sukrara.protocol.ble.HelloProof
import app.mauri.sukrara.protocol.ble.Reassembler
import app.mauri.sukrara.share.OwnerFixtures.crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The follower's side of the Bluetooth link on fake frames: HELLO + proof, pacing, the "which owner" gate. */
class NearbyLinkTest {
    private val f = crypto.generate()
    private val wearer = crypto.generate().public
    private val sid = "74767bf125c92e2efb705d29cb173f5bafda20bce46bf81e1a0a603e854c913a"
    private var now = 0L
    private val following = NearbyLink.Identity(wearer, sid, f)

    private fun frames(effects: List<NearbyLink.Effect>) = effects.filterIsInstance<NearbyLink.Effect.Write>().map { it.frame }

    /** Writes everything queued (each write answered OK) and returns the whole messages, in order. */
    private fun drain(link: NearbyLink, first: List<NearbyLink.Effect>): Pair<List<ByteArray>, List<NearbyLink.Effect>> {
        val rx = Reassembler { now }
        val messages = mutableListOf<ByteArray>()
        val other = mutableListOf<NearbyLink.Effect>()
        var pending = first
        while (true) {
            val writes = frames(pending)
            other += pending.filter { it !is NearbyLink.Effect.Write }
            assertTrue("one write at a time", writes.size <= 1)
            val w = writes.firstOrNull() ?: break
            rx.add(w)?.let(messages::add)
            pending = link.wrote(true)
        }
        return messages to other
    }

    private fun info(nonce: String, s: String = sid) = BleMessages.info(s, nonce, null, 0)

    @Test fun helloCarriesTheProofOnceTheStreamIsKnown() {
        val pending = NearbyLink(NearbyLink.Identity(wearer), crypto) { now }
        val (m1, _) = drain(pending, pending.connected(23, "aa"))
        val hello1 = BleMessages.parseHello(BleMessages.parse(m1.single())!!.body)!!
        assertNull(hello1.id)

        val link = NearbyLink(following, crypto) { now }
        val (m2, _) = drain(link, link.connected(23, "bb"))
        val hello2 = BleMessages.parseHello(BleMessages.parse(m2.single())!!.body)!!
        assertEquals(f.public to "phone", HelloProof.identify(crypto, hello2.id!!, sid, "bb", listOf(f.public)))
        assertNull(HelloProof.identify(crypto, hello2.id!!, sid, "cc", listOf(f.public)))
    }

    @Test fun nothingButHelloIsWrittenUntilThePhoneProvesItIsTheWearers() {
        val link = NearbyLink(following, crypto) { now }
        drain(link, link.connected(185, "aa"))
        val ev = event()
        assertEquals(listOf(NearbyLink.Effect.Sent(1, false)), link.send(ev, 1))

        // another wearer's phone: its m doesn't match our sid
        val other = link.received(BleFraming.frames(1, info("aa", "other-sid"), 185).single())
        assertEquals(listOf(NearbyLink.Effect.Info(BleMessages.Owner.Other)), other)
        assertFalse(link.verified)

        // a matching m, but for a nonce we never sent: still not ours
        link.received(BleFraming.frames(2, info("ff"), 185).single())
        assertFalse(link.verified)

        assertEquals(listOf(NearbyLink.Effect.Info(BleMessages.Owner.Ours)), link.received(BleFraming.frames(3, info("aa"), 185).single()))
        assertTrue(link.verified)
        val (messages, effects) = drain(link, link.send(ev, 2))
        assertEquals("EVENT", BleMessages.parse(messages.single())!!.type)
        assertEquals(listOf(NearbyLink.Effect.Sent(2, true)), effects)
    }

    @Test fun beforeTheFirstKeyAPhoneIsUnknownAndNeverWrittenTo() {
        val link = NearbyLink(NearbyLink.Identity(wearer), crypto) { now }
        drain(link, link.connected(185, "aa"))
        assertEquals(
            listOf(NearbyLink.Effect.Info(BleMessages.Owner.Unknown)),
            link.received(BleFraming.frames(1, info("aa"), 185).single()),
        )
        assertEquals(listOf(NearbyLink.Effect.Sent(5, false)), link.send(event(), 5))
    }

    @Test fun eventsArriveReassembledAndFramesGoOneAtATime() {
        val link = NearbyLink(following, crypto) { now }
        drain(link, link.connected(23, "aa"))
        BleFraming.frames(1, info("aa"), 23).forEach { link.received(it) }
        assertTrue(link.verified)
        val ev = event()
        val incoming = BleFraming.frames(9, BleMessages.event(ev), 23)
        val effects = incoming.flatMap(link::received)
        assertEquals(listOf(NearbyLink.Effect.Event(ev)), effects)

        val first = link.send(ev, 7)
        assertEquals(1, frames(first).size)
        // a failed write fails the message and drops what's queued
        assertEquals(listOf(NearbyLink.Effect.Sent(7, false)), link.wrote(false))
        assertEquals(emptyList<NearbyLink.Effect>(), link.wrote(true))
    }

    @Test fun aDisconnectFailsWhatWasQueued() {
        val link = NearbyLink(following, crypto) { now }
        drain(link, link.connected(23, "aa"))
        link.received(BleFraming.frames(1, info("aa"), 185).single())
        link.send(event(), 3)
        assertEquals(listOf(NearbyLink.Effect.Sent(3, false)), link.disconnected())
        assertFalse(link.verified)
        assertFalse(link.isConnected)
    }

    @Test fun keepsTheLastFourNonces() {
        val link = NearbyLink(following, crypto) { now }
        drain(link, link.connected(185, "a1"))
        listOf("a2", "a3", "a4", "a5").forEach { drain(link, link.hello(it)) }
        assertEquals(listOf("a2", "a3", "a4", "a5"), link.nonces)
        link.received(BleFraming.frames(1, info("a1"), 185).single())
        assertFalse(link.verified)
    }

    @Test fun noncesAreFreshLowercaseHex() {
        val a = NearbyLink.nonce()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertTrue(a != NearbyLink.nonce())
    }

    private fun event() =
        SignedEvent(
            id = "1".repeat(64),
            pubkey = PublicKeyHex(wearer.hex).hex,
            createdAt = 1,
            kind = 21078,
            tags = emptyList(),
            content = "x".repeat(300),
            sig = "2".repeat(128),
        )
}
