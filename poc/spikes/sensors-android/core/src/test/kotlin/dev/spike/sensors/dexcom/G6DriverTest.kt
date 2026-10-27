// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.dexcom

import dev.spike.sensors.DriverStatus
import dev.spike.sensors.harness.Trace
import dev.spike.sensors.hex
import dev.spike.sensors.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * Dexcom G6 / ONE. The pieces that can be checked without hardware: the key the transmitter id makes, the challenge
 * answer, the message layouts (against DiaBLE's recorded frames and their CRCs), and the handshake's decisions.
 */
class G6DriverTest {
    private val trace = Trace.resource("g6-session")

    @Test fun `the key is just the transmitter id, twice`() {
        // ASCII "008GABCD008GABCD" — the user reads 8GABCD off the transmitter, and that is the whole secret.
        assertEquals("30303847414243443030384741424344", G6Messages.key("8GABCD").hex())
        assertEquals(16, G6Messages.key("8GABCD").size)
    }

    @Test fun `the challenge answer is AES over the challenge twice`() {
        val key = G6Messages.key("8GABCD")
        val hash = Dexcom.challengeHash(key, "0001020304050607".unhex())
        assertEquals(8, hash.size)
        // Deterministic: the same key and challenge always give the same 8 bytes.
        assertEquals(hash.hex(), Dexcom.challengeHash(key, "0001020304050607".unhex()).hex())
        // And a different transmitter id gives a different answer.
        assertFalse(hash.hex() == Dexcom.challengeHash(G6Messages.key("8GABCE"), "0001020304050607".unhex()).hex())
    }

    @Test fun `DiaBLE's recorded frames pass the CRC and the backfill reply decodes`() {
        for (entry in trace.entries) assertTrue(entry.label, G6Messages.crcOk(entry.bytes))
        val reply = G6Messages.BackfillReply.parse(trace["backfill-reply"])!!
        assertEquals(0x0036867e, reply.startSeconds)
        assertEquals(0x00368c5a, reply.endSeconds)
        assertEquals(58, reply.length)
    }

    @Test fun `a glucose reply decodes, and a corrupt one is dropped`() {
        // 0x31: status 0, sequence 1, time 1000 s, 123 mg/dL, state 6 (ok), trend +1.2 mg/dL/min.
        val frame = G6Messages.withCrc("31000100000 0e80300007b0006 0c".replace(" ", "").unhex())
        val g = G6Messages.Glucose.parse(frame)!!
        assertEquals(1, g.sequence)
        assertEquals(1000, g.timeSeconds)
        assertEquals(123, g.mgdl)
        assertEquals(6, g.state)
        assertEquals(1.2f, g.trend!!, 1e-6f)
        assertTrue(g.usable)
        assertFalse(g.displayOnly)

        val corrupt = frame.copyOf().also { it[10] = (it[10] + 1).toByte() }
        assertEquals(null, G6Messages.Glucose.parse(corrupt))
    }

    @Test fun `the handshake verifies the transmitter before answering, and refuses a wrong id`() {
        val events = G7MessagesTest.Recorder()
        val writes = ArrayList<String>()
        val link = G7MessagesTest.RecordingLink(writes)
        val random = SecureRandom.getInstance("SHA1PRNG").apply { setSeed(7L) }
        val driver = G6Driver("8GABCD", events, random = random)
        driver.onConnected(link)

        val token = writes.single().unhex().copyOfRange(1, 9)
        val key = G6Messages.key("8GABCD")
        // The transmitter proves itself with the hash of our token; then it sends its own challenge.
        val challenge = "0102030405060708".unhex()
        driver.onNotification(Dexcom.AUTHENTICATION,
            byteArrayOf(0x03) + Dexcom.challengeHash(key, token) + challenge)
        assertEquals(Dexcom.challengeHash(key, challenge).hex(), writes.last().unhex().copyOfRange(1, 9).hex())

        // A transmitter that answers with the wrong hash is dropped before we reveal anything.
        val other = G6Driver("8GABCD", G7MessagesTest.Recorder(), random = random)
        val otherWrites = ArrayList<String>()
        val otherLink = G7MessagesTest.RecordingLink(otherWrites)
        other.onConnected(otherLink)
        other.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x03) + ByteArray(16))
        assertEquals(1, otherWrites.size)
        assertEquals(1, otherLink.disconnects)
    }

    @Test fun `an unauthenticated transmitter is reported as a failure`() {
        val events = G7MessagesTest.Recorder()
        val driver = G6Driver("8GABCD", events)
        driver.onConnected(G7MessagesTest.RecordingLink(ArrayList()))
        driver.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x00, 0x00))
        assertTrue(events.statuses.any { it is DriverStatus.Failed })
    }
}
