// Sukrara — https://github.com/mauriciabad/sukrara
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package app.mauri.sukrara.sensor.dexcom

import app.mauri.sukrara.sensor.SensorProfile
import app.mauri.sukrara.sensor.ble.FailReason
import app.mauri.sukrara.sensor.ble.LinkStatus
import app.mauri.sukrara.sensor.brand.Advert
import app.mauri.sukrara.sensor.brand.BrandEvents
import app.mauri.sukrara.sensor.brand.BrandLink
import app.mauri.sukrara.sensor.brand.Trace
import app.mauri.sukrara.sensor.brand.epochSec
import app.mauri.sukrara.sensor.brand.hex
import app.mauri.sukrara.sensor.brand.unhex
import app.mauri.sukrara.sensor.pairing.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dexcom G6 / ONE: the handshake keyed by the typed transmitter id, frames from DiaBLE (MIT). Untested on hardware. */
class G6DriverTest {
    private val trace = Trace.resource("g6-session")
    private val nowMs = 1_800_000_000_000L
    private val nowS = nowMs / 1000
    private val token = ByteArray(8) { (0x10 + it).toByte() }
    private val chars = setOf(Dexcom.AUTHENTICATION, Dexcom.CONTROL, Dexcom.BACKFILL)

    private fun driver(events: BrandEvents) =
        G6Driver(Pairing.scanned(SensorProfile.DexcomG6, "8GABCD", 1L), events, random = { token.copyInto(it) }, clockMs = { nowMs })

    /** Authenticated and bonded, the transmitter's clock at 100 000 s, its session started an hour ago. */
    private fun streaming(events: BrandEvents, link: BrandLink = BrandLink()): G6Driver =
        driver(events).also {
            it.onConnected(link, chars, 0)
            it.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
            it.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("2500a086010090780100")))
        }

    @Test
    fun keyIsTheTransmitterIdTwice() {
        assertEquals("30303847414243443030384741424344", G6Messages.key("8GABCD").hex()) // ASCII "008GABCD008GABCD"
        val hash = Dexcom.challengeHash(G6Messages.key("8GABCD"), unhex("0001020304050607"))
        assertFalse(hash.hex() == Dexcom.challengeHash(G6Messages.key("8GABCE"), unhex("0001020304050607")).hex())
    }

    @Test
    fun diablesFramesPassTheCrcAndTheBackfillReplyDecodes() {
        for ((label, b) in trace.entries) assertTrue(label, G6Messages.crcOk(b))
        val reply = G6Messages.BackfillReply.parse(trace["backfill-reply"])!!
        assertEquals(0x0036867e, reply.startSeconds)
        assertEquals(0x00368c5a, reply.endSeconds)
        assertEquals(58, reply.length)
    }

    @Test
    fun glucoseReplyDecodesAndACorruptOneIsDropped() {
        // 0x31: status 0, sequence 1, time 1000 s, 123 mg/dL, state 6 (ok), trend +1.2 mg/dL/min.
        val frame = G6Messages.withCrc(unhex("310001000000e80300007b00060c"))
        val g = G6Messages.Glucose.parse(frame)!!
        assertEquals(1000, g.timeSeconds)
        assertEquals(123, g.mgdl)
        assertEquals(1.2, g.trend!!, 1e-9)
        assertNull(G6Messages.Glucose.parse(frame.copyOf().also { it[10] = (it[10] + 1).toByte() }))
    }

    @Test
    fun handshakeVerifiesTheTransmitterThenReadsTheSessionAndGlucose() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = true)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        assertEquals("write 3535 01${token.hex()}02", link.characteristicWrites.single())

        val key = G6Messages.key("8GABCD")
        val challenge = unhex("0102030405060708")
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x03) + Dexcom.challengeHash(key, token) + challenge)
        assertEquals("write 3535 04${Dexcom.challengeHash(key, challenge).hex()}", link.characteristicWrites.last())

        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
        assertEquals(
            listOf("write 3534 ${G6Messages.timeRequest().hex()}", "write 3534 ${G6Messages.eGlucoseRequest().hex()}"),
            link.characteristicWrites.takeLast(2),
        )
        // a value before the transmitter's clock is known is not used
        d.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("3100010000000a8601007b00060c")))
        assertTrue(events.readings.isEmpty())

        // 0x25: transmitter clock 100 000 s, session started at 96 400 s → an hour ago
        d.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("2500a086010090780100")))
        assertEquals(listOf(nowS - 3_600), events.starts)

        // measured at transmitter time 99 850 s: 150 s ago, still the current value
        d.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("3100020000000a8601007b00060c")))
        assertEquals(123, events.readings.single().mgdl)
        assertEquals(nowS - 150, events.readings.single().epochSec)
    }

    @Test
    fun aStaleValueIsHistory() {
        val events = BrandEvents()
        val d = streaming(events)
        // transmitter time 99 000 s: 1000 s before its clock → history, never the current value
        d.onGlucose(G6Messages.Glucose(sequence = 5, timeSeconds = 99_000, mgdl = 140, state = 6, trend = null))
        assertTrue(events.readings.isEmpty())
        assertEquals(listOf(nowS - 1_000), events.history.map { it.epochSec })
    }

    @Test
    fun aWrongIdIsDroppedBeforeAnsweringAndNeedsThePerson() {
        val events = BrandEvents()
        val link = BrandLink()
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x03) + ByteArray(16))
        assertEquals(1, link.characteristicWrites.size) // only our auth request: nothing revealed
        assertEquals(1, link.closed)
        assertEquals(LinkStatus.Failed(FailReason.WrongId), events.last())
    }

    @Test
    fun unbondedTransmitterBondsThenStreams() {
        val events = BrandEvents()
        val link = BrandLink(isBonded = false)
        val d = driver(events)
        d.onConnected(link, chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x02))
        assertTrue(link.ops.contains("bond"))
        assertEquals(LinkStatus.Pairing, events.last())
        d.onBondChanged(true)
        assertEquals(LinkStatus.Authorized, events.last())
        d.onBondChanged(false)
        assertEquals(LinkStatus.Failed(FailReason.BondFailed), events.last())
    }

    @Test
    fun refusedAuthenticationIsAFailure() {
        val events = BrandEvents()
        val d = driver(events)
        d.onConnected(BrandLink(), chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x00, 0x00))
        assertEquals(LinkStatus.Failed(FailReason.Refused), events.last())
    }

    @Test
    fun noSessionIsSaid() {
        val events = BrandEvents()
        val d = driver(events)
        d.onConnected(BrandLink(), chars, 0)
        d.onNotification(Dexcom.AUTHENTICATION, byteArrayOf(0x05, 0x01, 0x01))
        d.onNotification(Dexcom.CONTROL, G6Messages.withCrc(unhex("2500a0860100ffffffff00")))
        assertEquals(LinkStatus.NoSession, events.last())
        assertTrue(events.starts.isEmpty())
    }

    @Test
    fun matchesItsOwnTransmitterName() {
        val d = driver(BrandEvents())
        assertTrue(d.matches(Advert("AA", "DexcomCD")))
        assertFalse(d.matches(Advert("AA", "DexcomXY")))
        assertEquals("DexcomCD", d.scan.single().name)
    }
}
