// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.direct.dexcom

import app.mauri.sukrara.core.SensorProfiles
import app.mauri.sukrara.direct.Advert
import app.mauri.sukrara.direct.BrandEvents
import app.mauri.sukrara.direct.BrandLink
import app.mauri.sukrara.direct.BrandPrefs
import app.mauri.sukrara.direct.QUIET
import app.mauri.sukrara.direct.SensorPairing
import app.mauri.sukrara.direct.Trace
import app.mauri.sukrara.direct.hex
import app.mauri.sukrara.direct.unhex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dexcom G6 / ONE (investigation 23, step 5): the key the transmitter id makes, the challenge answer, the layouts
 * against DiaBLE's recorded frames and their CRCs, and the handshake's decisions. Untested on hardware.
 */
class G6DriverTest {
    private val trace = Trace.resource("g6-session")
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000
    private val token = ByteArray(8) { (0x10 + it).toByte() }
    private val chars = setOf(Dexcom.AUTHENTICATION, Dexcom.CONTROL, Dexcom.BACKFILL)

    private fun driver(events: BrandEvents) = G6Driver(SensorPairing.forBrand(SensorProfiles.DEXCOM_G6, "8GABCD"), events, BrandPrefs(),
        random = { token.copyInto(it) }, clock = { nowMs }, log = QUIET)

    @Test fun keyIsTheTransmitterIdTwice() {
        assertEquals("30303847414243443030384741424344", G6Messages.key("8GABCD").hex())   // ASCII "008GABCD008GABCD"
        val hash = Dexcom.challengeHash(G6Messages.key("8GABCD"), unhex("0001020304050607"))
        assertEquals(8, hash.size)
        assertFalse(hash.hex() == Dexcom.challengeHash(G6Messages.key("8GABCE"), unhex("0001020304050607")).hex())
    }

    @Test fun diablesFramesPassTheCrcAndTheBackfillReplyDecodes() {
        for ((label, b) in trace.entries) assertTrue(label, G6Messages.crcOk(b))
        val reply = G6Messages.BackfillReply.parse(trace["backfill-reply"])!!
        assertEquals(0x0036867e, reply.startSeconds)
        assertEquals(0x00368c5a, reply.endSeconds)
        assertEquals(58, reply.length)
    }

    @Test fun glucoseReplyDecodesAndACorruptOneIsDropped() {
        // 0x31: status 0, sequence 1, time 1000 s, 123 mg/dL, state 6 (ok), trend +1.2 mg/dL/min.
        val frame = G6Messages.withCrc(unhex("310001000000e80300007b00060c"))
        val g = G6Messages.Glucose.parse(frame)!!
        assertEquals(1, g.sequence); assertEquals(1000, g.timeSeconds); assertEquals(123, g.mgdl)
        assertEquals(6, g.state); assertEquals(1.2f, g.trend!!, 1e-6f); assertTrue(g.usable)
        assertEquals(null, G6Messages.Glucose.parse(frame.copyOf().also { it[10] = (it[10] + 1).toByte() }))
    }

    @Test fun handshakeVerifiesTheTransmitterThenReadsTheSessionAndGlucose() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = true)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals("write 3535 01${token.hex()}02", link.characteristicWrites.single())

        val key = G6Messages.key("8GABCD")
        val challenge = unhex("0102030405060708")
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x03) + Dexcom.challengeHash(key, token) + challenge)
        assertEquals("write 3535 04${Dexcom.challengeHash(key, challenge).hex()}", link.characteristicWrites.last())

        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))   // authenticated, bonded
        assertEquals(listOf("write 3534 ${G6Messages.timeRequest().hex()}", "write 3534 ${G6Messages.eGlucoseRequest().hex()}"),
            link.characteristicWrites.takeLast(2))

        // 0x25: transmitter clock 100 000 s, session started at 96 400 s → an hour ago
        d.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("2500a086010090780100")))
        assertEquals(listOf(nowS - 3_600), events.starts)

        d.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("3100010000000a8601007b00060c")))
        assertEquals(123, events.readings.single().mgdl)
        assertEquals(nowS, events.readings.single().epochSec)
    }

    @Test fun aWrongIdIsDroppedBeforeAnsweringAndNeedsTheUser() {
        val events = BrandEvents()
        val link = BrandLink()
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x03) + ByteArray(16))
        assertEquals(1, link.characteristicWrites.size)          // only our auth request: nothing revealed
        assertEquals(1, link.closed)
        assertEquals("failed", events.last().first)
    }

    @Test fun unbondedTransmitterBondsThenStreams() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x02))
        assertTrue(link.ops.contains("bond"))
        assertEquals("pairing", events.last().first)
        d.onBondChanged(true)
        assertEquals("reading", events.last().first)
    }

    @Test fun refusedAuthenticationIsAFailure() {
        val events = BrandEvents()
        val d = driver(events)
        d.onConnected(BrandLink(), chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x00, 0x00))
        assertEquals("failed", events.last().first)
    }

    @Test fun matchesItsOwnTransmitterName() {
        val d = driver(BrandEvents())
        assertTrue(d.matches(Advert("AA", "DexcomCD")))
        assertFalse(d.matches(Advert("AA", "DexcomXY")))
        assertEquals("DexcomCD", d.scan.single().name)
    }
}
