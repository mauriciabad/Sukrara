// CGM research spikes — https://github.com/mauriciabad/cgm-reading-mac-spike
// Copyright (C) 2026 Maurici Abad
// SPDX-License-Identifier: GPL-3.0-or-later
package dev.spike.sensors.aidex

import dev.spike.sensors.dexcom.G7MessagesTest
import dev.spike.sensors.harness.Trace
import dev.spike.sensors.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every step of the Aidex X protocol against the GATT trace recorded in Juggluco's `aidexx/decrypt.cpp`
 * (serial 22222A2WC8): the derived keys must equal the bytes that actually went over the air, and the recorded
 * ciphertexts must decrypt to the recorded plaintexts.
 */
class AidexXTest {
    private val trace = Trace.resource("aidex-x")
    private val serial = String(trace["serial"], Charsets.US_ASCII)
    private val iv = AidexX.iv(serial)

    @Test fun `the IV and the ask-key come out of the serial number alone`() {
        assertEquals("72b718f89470e73422d8c6ad2c228db8", iv.hex())
        assertEquals(trace["ask-key"].hex(), AidexX.askKey(serial).hex())
        // A second serial from the same source, as a check that the mapping is not fitted to one string.
        assertEquals("afc443924ea220e6b8e98eb505f0f77e", AidexX.iv("22222A532X").hex())
        assertEquals("7fdb4d90ffabb142ccf8a15b58402c91", AidexX.askKey("22222A532X").hex())
    }

    @Test fun `the session key is read from the sensor and checked with its CRC`() {
        val session = AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])
        assertNotNull(session)
        assertEquals("1c152cd42daa9e503300fe6baf908046", session!!.hex())

        // One flipped bit in the read and the CRC-8 no longer matches: a wrong serial is caught here.
        val broken = trace["session-read"].copyOf().also { it[0] = (it[0] + 1).toByte() }
        assertNull(AidexX.sessionKey(trace["notified-key"], iv, broken))
    }

    @Test fun `the recorded commands and replies decrypt to what the vendor app saw`() {
        val session = AidexX.sessionKey(trace["notified-key"], iv, trace["session-read"])!!
        // Commands: our encryption of the opcode plus its CRC must equal the recorded ciphertext.
        assertEquals(trace["cmd-device-info"].hex(), AidexX.command(session, iv, AidexX.OP_DEVICE_INFO).hex())
        assertEquals(trace["cmd-start-time"].hex(), AidexX.command(session, iv, AidexX.OP_START_TIME).hex())
        assertEquals(trace["cmd-glucose"].hex(), AidexX.command(session, iv, AidexX.OP_GLUCOSE).hex())

        // Replies: the device info says version 1.8.2.0, 15 days, model "GX-01S".
        val info = AidexX.reply(session, iv, trace["rx-device-info"])!!
        assertEquals("10010000010802000f0047582d3031530000", info.hex())
        assertEquals("GX-01S", String(info, 10, 6, Charsets.US_ASCII))

        val start = AidexX.reply(session, iv, trace["rx-start-time"])!!
        assertEquals("2101ea07020f0c21360400", start.hex())   // 2026-02-15 12:33:54, tz +4 quarter-hours

        // The live glucose reply in the trace carries the same record as the advertisement recorded with it.
        val live = AidexX.reply(session, iv, trace["rx-glucose"])!!
        val g = AidexX.Glucose.parseReply(live)!!
        assertEquals(21600, g.minutesSinceStart)
        assertEquals(92, g.mgdl)
    }

    @Test fun `a glucose reply decodes to the value the vendor app logged`() {
        // The vendor app's own log for this plaintext: timeOffset 19348, trend -5, glucose 119, quality 77.
        val plain = trace["plain-glucose"].let { it.copyOf(it.size - 2) }
        val g = AidexX.Glucose.parseReply(plain)!!
        assertEquals(19348, g.minutesSinceStart)
        assertEquals(119, g.mgdl)
        assertEquals(-0.5f, g.trend, 1e-6f)
        assertEquals(77, g.quality)
        assertTrue(g.valid)
        assertTrue(g.usable)
        assertFalse(g.warmingUp)
    }

    @Test fun `an advertisement gives three readings without connecting, and its CRC-32 is checked`() {
        val advert = AidexX.Advertisement.parse(trace["advertisement"])!!
        assertTrue("the recorded advertisement must pass its own CRC", advert.crcOk)
        assertEquals(21600, advert.latest.minutesSinceStart)
        assertEquals(92, advert.latest.mgdl)
        assertEquals(62, advert.latest.quality)
        assertEquals(listOf(84, 78), advert.previous)
        assertEquals("AiDEX X-22222A2WC8", advert.name)

        val tampered = trace["advertisement"].copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertFalse(AidexX.Advertisement.parse(tampered)!!.crcOk)
    }

    @Test fun `the driver turns an advertisement into a reading and a session`() {
        val events = G7MessagesTest.Recorder()
        val driver = AidexXDriver(serial, events, now = { 1_000_000_000_000 })
        val reading = driver.onAdvertisement(trace["advertisement"])!!
        assertEquals(92, reading.mgdl)
        assertEquals(1_000_000_000_000, reading.timeMs)       // 21600 min after the start it just derived
        val session = events.sessions.single()
        assertEquals(1_000_000_000_000 - 21600 * 60_000L, session.startMs)
        assertEquals(session.startMs!! + 15 * 24 * 60 * 60_000L, session.endMs)
    }

    @Test fun `a wrong serial means no session key and a clear failure`() {
        val events = G7MessagesTest.Recorder()
        val driver = AidexXDriver("22222A532X", events)
        driver.onConnected(G7MessagesTest.RecordingLink(ArrayList()))
        driver.onNotification(AidexX.KEY_UNBONDED, trace["notified-key"])
        driver.onDataRead(trace["session-read"])             // encrypted for the other sensor's IV
        assertTrue(events.statuses.any { it is dev.spike.sensors.DriverStatus.Failed })
    }
}
